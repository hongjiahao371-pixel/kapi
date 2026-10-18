package kapi.core.io

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle

/** Local, deterministic import. Unknown direction/currency is never guessed by a model. */
object SmartBillParser {
    const val SOURCE_BANK_TABLE="BILL_BANK_TABLE"
    const val SOURCE_KAPI="BILL_KAPI"
    const val SOURCE_SUISHOUJI="BILL_SUISHOUJI"
    const val SOURCE_QIANJI_TEMPLATE="BILL_QIANJI_TEMPLATE"
    const val SOURCE_ICOST="BILL_ICOST"
    data class SheetReport(val name:String,val records:Int,val status:String)
    fun allowsCategoryPreservation(source:String)=source !in setOf(BankBillParser.SOURCE_WECHAT,BankBillParser.SOURCE_ALIPAY,BankPdfTable.SOURCE,SOURCE_BANK_TABLE)
    enum class Field(val label: String) {
        TIME("时间"), CLOCK("时分秒"), DIRECTION("收支"), AMOUNT("金额"), INCOME("收入金额"), EXPENSE("支出金额"),
        MERCHANT("商户"), NOTE("备注"), GOODS("商品"), TYPE("交易分类 / 类型"), CATEGORY("分类"), SUBCATEGORY("子分类"),
        ACCOUNT("账户"), TO_ACCOUNT("转入账户"), STATUS("状态"), ID("交易号"), NATURE("交易性质（核对）"), CURRENCY("币种"), TIME_EVIDENCE("时间依据")
    }
    data class Table(val headers: List<String>, val records: List<List<String>>, val mapping: Map<Field,Int>, val source: String,
        val sheetReports:List<SheetReport> = emptyList(),val identityHeaders:List<String>?=null,val footer:List<List<String>> = emptyList())
    data class Issue(val row: Int, val reason: String)
    data class Preview(val table: Table, val rows: List<TransferRow>, val issues: List<Issue>, val neutral: Int, val unpaid: Int, val duplicates: Int,val checks:List<String> = emptyList()) {
        val incomeMinor: Long get() = rows.filter { it.direction=="INCOME" && it.toAccount==null }.fold(0L) { a,r -> Math.addExact(a,r.amountMinor) }
        val expenseMinor: Long get() = rows.filter { it.direction=="EXPENSE" && it.toAccount==null }.fold(0L) { a,r -> Math.addExact(a,r.amountMinor) }
        val sourceName get() = when(table.source) { SOURCE_BANK_TABLE -> "银行明细"; BankPdfTable.SOURCE -> "工商银行 PDF"; BankBillParser.SOURCE_WECHAT -> "微信"; BankBillParser.SOURCE_ALIPAY -> "支付宝"; SOURCE_KAPI -> "咔皮记账"; SOURCE_SUISHOUJI -> "随手记"; SOURCE_QIANJI_TEMPLATE -> "钱迹模板"; SOURCE_ICOST -> "iCost"; else -> "通用账单" }
    }
    private fun normalized(s: String) = s.trim().removePrefix(LedgerCsv.BOM).lowercase().replace(Regex("[\\s_（）()/\\-]"), "")
    private val aliases = mapOf(
        Field.TIME to listOf("支付时间","付款时间","退款成功时间","退款时间","交易时间","交易创建时间","发生时间","记账时间","时间","日期","交易日期","date","time","datetime","transactiondate","occurredat","timestamp"),
        Field.CLOCK to listOf("时分秒","时刻","clock"),
        Field.DIRECTION to listOf("收/支","收支","收支类型","收支方向","交易方向","类型","direction","type","交易类型"),
        Field.AMOUNT to listOf("金额(元)","金额","交易金额","实际金额","amount","money","amt"),
        Field.INCOME to listOf("收入金额","收入金额(元)","交易金额(收入)","收入","入账金额","income","credit"), Field.EXPENSE to listOf("支出金额","支出金额(元)","交易金额(支出)","支出","消费金额","expense","debit"),
        Field.MERCHANT to listOf("交易对方","商户","商家","交易对象","交易场所","对方户名","对方账号与户名","对方","merchant","payee"),
        Field.NOTE to listOf("摘要","备注","说明","描述","note","memo","description"), Field.GOODS to listOf("商品名称","商品说明","商品","消费内容","项目","goods","item"),
        Field.TYPE to listOf("交易分类","交易类型"),
        Field.CATEGORY to listOf("分类","一级分类","类别","分类名称","category"), Field.SUBCATEGORY to listOf("子分类","二级分类","子类","子类别","subcategory"),
        Field.ACCOUNT to listOf("账户","帐户","账户1","支出账户","收入账户","账户名称","资金账户","支付账户","付款方式","支付方式","收付款方式","转出账户","fromaccount","account"),
        Field.TO_ACCOUNT to listOf("转入账户","账户2","toaccount","destinationaccount"),
        Field.STATUS to listOf("交易状态","当前状态","状态","status"), Field.ID to listOf("交易单号","交易号","交易订单号","支付宝交易号","微信支付交易单号","账单ID","transactionid","externalkey"),
        Field.NATURE to listOf("交易性质（核对）"),
        Field.CURRENCY to listOf("币别","币种","账户币种","货币","currency"), Field.TIME_EVIDENCE to listOf("时间依据","timeevidencejson")
    )
    fun infer(headers: List<String>): Map<Field,Int> = buildMap {
        val clean = headers.map(::normalized)
        aliases.forEach { (f,names) -> names.firstNotNullOfOrNull { n -> clean.indexOf(normalized(n)).takeIf { it>=0 } }?.let { put(f,it) } }
        val date=clean.indexOfFirst {it in setOf("日期","date")};val clock=clean.indexOfFirst {it in setOf("时间","time")}
        if(date>=0 && clock>=0 && get(Field.TIME)==clock) {put(Field.TIME,date);put(Field.CLOCK,clock)}
        if(headers.map{it.trim()}==listOf("时间","收支","金额","商户","分类","账户","转出账户","备注")) put(Field.TO_ACCOUNT,6)
        if(headers.any {it.startsWith("_归账_")}) {
            clear()
            Field.entries.forEach {f -> headers.indexOf("_归账_"+f.name).takeIf {it>=0}?.let {put(f,it)}}
        }
        else {BankTableSchema.apply(headers,this);ImportProfiles.apply(headers,this)}
    }
    /** RFC4180 including quoted line breaks; also supports TSV and semicolon exports. */
    fun records(text: String, delimiter: Char): List<List<String>> {
        require(text.length<=20*1024*1024) { "账单超过 20 MB，请按月份分批导入" }
        val out = ArrayList<List<String>>(); var row=ArrayList<String>(); val cell=StringBuilder(); var quote=false; var i=0
        while(i<text.length) {
            val c=text[i]
            when {
                c=='"' && (quote || cell.isEmpty()) -> {
                    if(quote && i+1<text.length && text[i+1]=='"') { cell.append('"'); i++ } else quote=!quote
                }
                c==delimiter && !quote -> { row.add(cell.toString()); cell.setLength(0) }
                (c=='\n' || c=='\r') && !quote -> {
                    row.add(cell.toString()); cell.setLength(0); if(row.any { it.isNotBlank() }) out.add(row); row=ArrayList()
                    if(c=='\r' && i+1<text.length && text[i+1]=='\n') i++
                    require(out.size<=100_000) { "账单超过十万行，请分批导入" }
                }
                else -> cell.append(c)
            }
            require(cell.length<=128*1024 && row.size<=256) { "账单单元格或列数过大" }; i++
        }
        require(!quote) { "文件有未闭合的引号，请重新导出" }
        row.add(cell.toString()); if(row.any { it.isNotBlank() }) out.add(row)
        return out
    }
    fun inspect(text: String): Table {
        data class Candidate(val rows:List<List<String>>,val index:Int,val map:Map<Field,Int>,val score:Int)
        val candidates= listOf(',', '\t', ';').mapNotNull { d ->
            val rows=runCatching { records(text,d) }.getOrNull() ?: return@mapNotNull null
            rows.take(100).mapIndexedNotNull { index,h ->
                val m=infer(h); val money=Field.AMOUNT in m || Field.INCOME in m || Field.EXPENSE in m
                if(Field.TIME !in m || !money) null else Candidate(rows,index,m,m.size)
            }.maxByOrNull { it.score }
        }
        val c=candidates.maxByOrNull { it.score }
        if(c==null) {
            val raw= listOf(',', '\t', ';').mapNotNull { runCatching { records(text,it) }.getOrNull() }.maxByOrNull { it.firstOrNull()?.size ?: 0 }
            if(raw!=null && raw.size>1 && raw.first().size>=2 && raw.first().any { it.any(Char::isLetter) }) return Table(raw.first(),raw.drop(1),infer(raw.first()),"BILL_GENERIC")
            error("未识别到时间和金额列。请导出 CSV、TSV、XLS 或 XLSX；可先把列名改为「时间、收支、金额」")
        }
        val h=c.rows[c.index].map { it.trim().removePrefix(LedgerCsv.BOM) }
        val meta=c.rows.take(c.index).flatten().joinToString(" ")
        val source=ImportProfiles.detect(h)?.source ?: when {
            h.containsAll(listOf("日期","类型","金额","一级分类","二级分类","账户1","账户2","货币","账本","退款","优惠","手续费","图片链接1","图片附件1")) -> SOURCE_ICOST
            meta.contains("咔皮记账导出") || h.containsAll(listOf("一级分类","二级分类","计入收支","所属账本","分摊明细")) -> SOURCE_KAPI
            meta.contains("随手记导出") || h.containsAll(listOf("交易类型","一级分类","支出账户","成员","商家")) || h.containsAll(listOf("交易类型","一级分类","收入账户","成员","商家")) ||
                h.containsAll(listOf("交易类型","日期","转出账户","转入账户","成员","商家","项目")) -> SOURCE_SUISHOUJI
            meta.contains("钱迹模板") || h.containsAll(listOf("账户1","账户2","账单标记","手续费","优惠券")) -> SOURCE_QIANJI_TEMPLATE
            BankTableSchema.recognizes(h) -> SOURCE_BANK_TABLE
            meta.contains("支付宝") || h.any { it.trim() in listOf("交易创建时间","成功退款(元)","支付宝交易号") } -> BankBillParser.SOURCE_ALIPAY
            meta.contains("微信支付") || h.any { it.trim()=="微信支付交易单号" } || (h.any { it.trim()=="交易单号" } && h.any { it.trim()=="当前状态" } && h.any { it.trim()=="商品" }) -> BankBillParser.SOURCE_WECHAT
            h.containsAll(listOf("交易日期","交易金额","账户余额","摘要")) -> SOURCE_BANK_TABLE
            else -> "BILL_GENERIC"
        }
        val records=c.rows.drop(c.index+1).filterNot{BankTableSchema.isFooter(h,it,c.map)}
        return ImportIntelligence.decorate(Table(h,records,c.map,source,footer=c.rows.drop(c.index+1).filter{BankTableSchema.isFooter(h,it,c.map)}))
    }
    private val dead=listOf("交易关闭","支付失败","交易失败","已关闭","已取消","支付中止","订单关闭","未付款","待付款","待支付","等待付款","待确认","处理中","pending","failed","cancelled","canceled","unpaid")
    fun preview(table: Table, mapping: Map<Field,Int>? = null, zone: ZoneId=ZoneId.of("Asia/Shanghai")): Preview {
        val columns = mapping ?: table.mapping
        val creationTimeColumn = if(mapping == null && table.source == BankBillParser.SOURCE_ALIPAY)
            table.headers.indexOfFirst { normalized(it) == normalized("交易创建时间") }.takeIf { it >= 0 } else null
        require(Field.TIME in columns) { "请指定时间列" }
        require(Field.AMOUNT in columns || Field.INCOME in columns || Field.EXPENSE in columns) { "请指定金额或收入/支出金额列" }
        require(columns.values.all { it in table.headers.indices }) { "列映射已失效，请重新选择文件" }
        val rows=ArrayList<TransferRow>(); val issues=ArrayList<Issue>(); val seen=HashMap<String,TransferRow>(); var neutral=0; var unpaid=0; var duplicate=0
        val check=ImportIntelligence.totalsCheck(table,columns)
        if(check?.startsWith("失败：")==true) return Preview(table,emptyList(),listOf(Issue(0,check)),0,0,0,listOf(check))
        table.sheetReports.filter {it.records>0 && it.status.startsWith("暂不支持")}.forEach {
            issues+=Issue(0,"工作表「"+it.name+"」"+it.records+" 行未导入："+it.status)
        }
        table.records.forEachIndexed { i,cells ->
            fun v(f: Field): String = columns[f]?.let { cells.getOrNull(it) }?.trim()?.removePrefix("'").orEmpty()
            fun originalColumn(name:String)=table.headers.indexOf(name).takeIf { it>=0 }?.let { cells.getOrNull(it)?.trim() }.orEmpty()
            if(cells.size==1 && table.headers.size>1) return@forEachIndexed // export footnotes, never transaction-shaped rows
            // Preserve the source's accounting choice rather than turning excluded bills into expenses.
            if(originalColumn("计入收支").lowercase() in setOf("否","不计入","false","0") ||
                originalColumn("账单标记").lowercase() in setOf("不计收支","不计收支&预算","nc","nc&nb")) { neutral++;return@forEachIndexed }
            if(table.source==SOURCE_BANK_TABLE && v(Field.NATURE)=="IGNORE") {neutral++;return@forEachIndexed}
            val status=v(Field.STATUS).lowercase()
            // Escrow purchases have already been paid even while the buyer has not confirmed receipt.
            val paidEscrow=table.source==BankBillParser.SOURCE_ALIPAY && status in setOf("等待确认收货","待确认收货","等待买家确认收货","等待卖家发货","待发货","待收货")
            if(!paidEscrow && dead.any { status.contains(it) }) { unpaid++; return@forEachIndexed }
            val rawDir=v(Field.DIRECTION).lowercase()
            val declared=when(rawDir) { "支出","支","expense","out","debit","付款"->"EXPENSE"; "收入","收","income","in","credit","收款"->"INCOME"; "划转","转账","transfer","信用卡还款"->"TRANSFER"; "还款" -> if(table.source==SOURCE_QIANJI_TEMPLATE) "TRANSFER" else null; else->null }
            val sheetType=originalColumn("_归账_表类型").takeIf {it.isNotBlank()}
            val dir=declared ?: if(rawDir.isBlank()) sheetType else null
            if(rawDir in listOf("其他","其它","中性","不计收支")) { neutral++; return@forEachIndexed }
            try {
                require(originalColumn("银行待核对提示").isBlank()) { originalColumn("银行待核对提示") }
                require(sheetType!="UNSUPPORTED") {"借贷及应收应付关系暂不支持迁移"}
                require(sheetType==null || declared==null || declared==sheetType) {"交易类型与工作表「"+originalColumn("_归账_工作表")+"」不一致"}
                require(rawDir.isBlank() || declared!=null) {"交易类型「"+rawDir+"」暂不支持，不能按金额正负作为收支导入"}
                val excludeFromBudget=originalColumn("账单标记").lowercase() in setOf("不计预算","nb") || originalColumn("计入预算").lowercase() in setOf("否","不计入","false","0")
                for(name in listOf("退款","退款金额","已报销金额","报销金额")) {
                    require(optionalMoney(originalColumn(name)) in listOf(null,0L)) {"原表含"+name+"，需要原账单关联，暂不直接计入收支"}
                }
                val timeLabels=if(directionForTime(rawDir)=="INCOME") listOf("退款成功时间","退款时间","到账时间","收款时间") else listOf("支付时间","付款时间","扣款时间")
                val timeOverride=if(mapping==null) timeLabels.firstNotNullOfOrNull { label -> table.headers.indexOfFirst { normalized(it)==normalized(label) }.takeIf { it>=0 }?.let { cells.getOrNull(it)?.trim()?.takeIf { value -> value !in setOf("","/","-","--") } } } else null
                val primaryTime = timeOverride ?: v(Field.TIME)
                // Only known Alipay exports may use creation time for a missing payment time.
                // A nonempty malformed value and explicit user mappings remain strict.
                val time = if(primaryTime in setOf("", "/", "-", "--") && creationTimeColumn != null)
                    cells.getOrNull(creationTimeColumn)?.trim()?.removePrefix("'").orEmpty() else primaryTime
                val importTimeText=listOf(time,v(Field.CLOCK)).filter { it.isNotBlank() }.joinToString(" ")
                val at=parseTime(importTimeText,zone) ?: error("时间格式无效")
                val currency=v(Field.CURRENCY).uppercase()
                require(currency in listOf("","CNY","RMB","人民币","人民币元","元","¥","￥")) { "币种 $currency 需要先换算，不能按人民币导入" }
                val income=optionalMoney(v(Field.INCOME)); val expense=optionalMoney(v(Field.EXPENSE))
                val main=normalizeMoney(v(Field.AMOUNT)); var amount:Long; var direction:String
                if((income ?: 0)>0 || (expense ?: 0)>0 || (main.isBlank() && (Field.INCOME in columns || Field.EXPENSE in columns))) {
                    require(income==null || income>=0); require(expense==null || expense>=0)
                    require(!((income ?: 0)>0 && (expense ?: 0)>0)) { "同一行同时有收入和支出" }
                    if((income ?: 0)>0) { amount=income!!; direction="INCOME" } else if((expense ?: 0)>0) { amount=expense!!; direction="EXPENSE" } else error("金额为空或为零")
                    require(dir==null || dir==direction) { "收支列与金额列矛盾" }
                } else {
                    val signed=parseMoney(main) ?: error("金额无效、溢出或超过两位小数")
                    require(signed!=0L) { "金额为零" }; amount=kotlin.math.abs(signed)
                    // No silent rounding or inference from an unsigned positive amount.
                    direction=dir ?: when { main.startsWith("-")->"EXPENSE"; main.startsWith("+") || table.source==SOURCE_BANK_TABLE->"INCOME"; else->error("收支方向不明确，请指定收支列") }
                    require(direction!="INCOME" || signed>0) { "收入列与负金额矛盾" }
                }
                require(amount in 1..99_999_999_999L) { "金额超出支持范围" }
                if(table.source==SOURCE_BANK_TABLE) {
                    val nature=v(Field.NATURE)
                    require(nature in setOf("","NORMAL","TRANSFER","IGNORE")) {"交易性质只能选择 NORMAL、TRANSFER 或 IGNORE"}
                    val warning=ImportIntelligence.natureWarning(originalColumn("摘要"))
                    require(warning==null || nature.isNotEmpty() || dir=="TRANSFER") {warning ?: "交易性质待核对"}
                    if(nature=="TRANSFER") {require(v(Field.ACCOUNT).isNotBlank() && v(Field.ACCOUNT)!="/") {"划转请明确填写资金转出和转入账户"};direction="TRANSFER"}
                }
                val fee=optionalMoney(originalColumn("手续费")) ?: 0L
                val coupon=optionalMoney(originalColumn("优惠券")) ?: 0L
                require((optionalMoney(originalColumn("优惠")) ?: 0L)==0L) {"此格式优惠金额的口径未确认，请核对后调整"}
                require(fee>=0 && coupon>=0) {"手续费或优惠券不能为负"}
                require((fee==0L && coupon==0L) || table.source==SOURCE_QIANJI_TEMPLATE) {"此格式手续费或优惠金额的口径未确认，请核对后调整"}
                require(fee==0L || direction=="TRANSFER") {"手续费仅支持钱迹模板的转账和信用卡还款"}
                require(coupon==0L || direction=="EXPENSE") {"优惠券仅支持钱迹模板的支出"}
                require(fee+coupon<amount) {"手续费或优惠券应小于账单金额"}
                amount-=fee+coupon
                val nativeCsv=table.headers==LedgerCsv.HEADER || table.headers==LedgerCsv.HEADER.take(8) || table.headers==LedgerCsv.HEADER.take(10) ||
                    table.headers==listOf("时间","收支","金额","商户","分类","账户","转出账户","备注")
                val to=if(direction=="TRANSFER" || nativeCsv) v(Field.TO_ACCOUNT).takeIf { it.isNotBlank() && it!="/" } else null
                require(direction!="TRANSFER" || to!=null) { "划转缺少转入账户" }
                val explicitKey=v(Field.ID).takeIf { it.matches(Regex("[A-Za-z0-9:_-]{12,100}")) }?.let {
                    when { it.startsWith("WECHAT_TXN:") || it.startsWith("ALIPAY_TXN:") -> it; table.source==BankBillParser.SOURCE_WECHAT -> "WECHAT_TXN:$it"; table.source==BankBillParser.SOURCE_ALIPAY -> "ALIPAY_TXN:$it"; table.source in setOf(SOURCE_KAPI,SOURCE_SUISHOUJI,SOURCE_QIANJI_TEMPLATE) -> table.source+":"+it; else -> if(it.contains(':')) it else "BILL_GENERIC:"+java.security.MessageDigest.getInstance("SHA-256").digest((table.identityHeaders ?: table.headers).map(::normalized).sorted().joinToString("\u0001").toByteArray()).take(8).joinToString("") { b -> "%02x".format(b) }+":"+it }
                }
                val category=listOf(v(Field.CATEGORY),v(Field.SUBCATEGORY)).filter { it.isNotBlank() && it!="/" }.distinct().joinToString("/").takeIf { it.isNotBlank() }
                    ?: originalColumn("交易分类").takeIf {table.source==BankBillParser.SOURCE_ALIPAY && it.isNotBlank() && it!="/"}
                val bankMemo=if(table.source==SOURCE_BANK_TABLE) originalColumn("交易地点/附言").takeIf{it.any{c->c !in "*—-/ "}} else null
                val originalNote=(listOf(v(Field.GOODS),v(Field.TYPE),v(Field.NOTE))+listOfNotNull(bankMemo)).filter { it.isNotBlank() && it!="/" }.distinct().joinToString("｜").takeIf { it.isNotBlank() }
                val capture=if(table.source==SOURCE_KAPI && v(Field.NOTE).contains('\n')) kapi.core.llm.LocalTextParser.parse(v(Field.NOTE),at)?.takeIf { it.best?.amountMinor==amount && it.direction.name==direction } else null
                val key=explicitKey ?: if(table.source==SOURCE_KAPI) kapi.core.auto.PaymentId.importIdentity(v(Field.NOTE)) else null
                val context=(listOf("所属账本","账本","标签","成员","项目","计入预算","分摊明细","账单标记","手续费","优惠券").mapNotNull { name -> originalColumn(name).takeIf { it.isNotBlank() && it!="/" }?.let { "$name：$it" } }+
                    (if(table.source==SOURCE_KAPI) listOfNotNull(category?.let { "原分类：$it" }) else emptyList())).joinToString("\n").takeIf {it.isNotBlank()}
                val note=listOfNotNull(originalNote,context, v(Field.NATURE).takeIf {it.isNotBlank()}?.let {"交易性质经用户核对："+when(it){"TRANSFER"->"账户划转";"NORMAL"->"普通收支";else->"不计收支"}}).joinToString("\n").takeIf { it.isNotBlank() }
                fun columnTime(vararg names:String):Long? = names.firstNotNullOfOrNull { name -> table.headers.indexOfFirst { normalized(it)==normalized(name) }.takeIf { it>=0 }?.let { cells.getOrNull(it)?.trim()?.let { value -> kapi.core.auto.TransactionTime.timestamp(value,zone) } } }
                val savedEvidence=v(Field.TIME_EVIDENCE).takeIf { it.isNotBlank() }?.let { requireNotNull(kapi.core.auto.TransactionTime.decode(it)) { "时间依据格式无效" } }
                val precision=when { Regex("\\d{1,2}[:：]\\d{2}[:：]\\d{2}").containsMatchIn(importTimeText) -> "SECOND"; importTimeText.contains(':') || importTimeText.contains('：') -> "MINUTE"; else -> "DATE" }
                val evidence=savedEvidence ?: kapi.core.auto.TransactionTime.Evidence(at,"IMPORT_TIME",precision,
                    paidAt=columnTime("支付时间","付款时间","扣款时间"),createdAt=columnTime("交易创建时间","创建时间","下单时间"),refundAt=columnTime("退款成功时间","退款时间","到账时间","收款时间"))
                val row=TransferRow(at,if(direction=="TRANSFER") "EXPENSE" else direction,amount,
                    merchant=BankTableSchema.merchant(columns[Field.MERCHANT]?.let{table.headers[it]},v(Field.MERCHANT)) ?: capture?.best?.merchant,note=note,category=category,
                    account=(if(table.source==SOURCE_KAPI && direction=="TRANSFER") originalColumn("转出账户").takeIf { it.isNotBlank() } ?: v(Field.ACCOUNT) else v(Field.ACCOUNT)).takeIf { it.isNotBlank() && it!="/" },toAccount=to,source=table.source,externalKey=key,timeEvidence=evidence,excludeFromBudget=excludeFromBudget)
                // Without a platform ID, preserve separate identical purchases; preview can flag them later.
                if(key!=null) { val identity="$key:${row.direction}";val previous=seen[identity];if(previous!=null) { if(previous.amountMinor==row.amountMinor && previous.occurredAt==row.occurredAt) duplicate++ else issues+=Issue(i+1,"同一交易号有不同金额或时间，请核对编号列后重新导入");return@forEachIndexed };seen[identity]=row }
                rows+=row
                if(fee>0) rows+=row.copy(amountMinor=fee,toAccount=null,category="手续费",externalKey=key?.let {it+":FEE"},note=listOfNotNull(note,"转账手续费（原金额包含手续费）").joinToString("\n"))
            } catch(e: Exception) { issues+=Issue(i+1,e.message ?: "内容无效") }
        }
        return Preview(table.copy(mapping=columns),rows,issues,neutral,unpaid,duplicate,listOfNotNull(check)).also { it.incomeMinor; it.expenseMinor }
    }
    private fun directionForTime(raw:String)=if(raw in setOf("收入","收","income","in","credit","收款")) "INCOME" else "EXPENSE"
    /** Format-only import evidence: no merchant, amount value, date, account or transaction ID. */
    fun diagnosticLines(p:Preview):List<String> = listOf("source=${p.table.source} records=${p.table.records.size} parsed=${p.rows.size} issues=${p.issues.size} neutral=${p.neutral} unpaid=${p.unpaid} duplicates=${p.duplicates}") + p.issues.take(12).map {issue->
        val cells=p.table.records.getOrNull(issue.row-1).orEmpty()
        fun shape(f:Field):String {
            val raw=p.table.mapping[f]?.let {cells.getOrNull(it)}.orEmpty().take(128)
            val marks=raw.filter {Character.getType(it)==Character.FORMAT.toInt()}.map {"U+%04X".format(it.code)}.distinct().joinToString(",")
            return "${f.name}:len=${raw.length},fraction=${Regex("\\.([0-9]+)").find(raw)?.groupValues?.get(1)?.length ?: 0},scientific=${Regex("[0-9][Ee][+-]?[0-9]").containsMatchIn(raw)},marks=[$marks]"
        }
        val kind=when {issue.reason.contains("时间") -> "TIME_FORMAT";issue.reason.contains("金额") -> "MONEY_FORMAT";else->"OTHER_VALIDATION"}
        "row=${issue.row} failure=$kind ${shape(Field.TIME)} ${shape(Field.AMOUNT)} ${shape(Field.INCOME)} ${shape(Field.EXPENSE)}"
    }
    private fun optionalMoney(s:String):Long? = if(s.isBlank() || s in listOf("/","-")) null else parseMoney(s) ?: error("收入/支出金额无效")
    private fun numericText(raw:String)=java.text.Normalizer.normalize(raw,java.text.Normalizer.Form.NFKC)
        .replace(Regex("[\\uFEFF\\u200B-\\u200F\\u2060\\u202A-\\u202E\\u2066-\\u2069]"),"").trim().removePrefix("'")
    private fun normalizeMoney(raw:String)=numericText(raw).replace('−','-').replace('–','-')
    fun parseMoney(raw:String):Long? = runCatching {
        val s=normalizeMoney(raw).replace(Regex("^[¥￥]\\s*"),"")
        require(s.matches(Regex("[+-]?(?:[0-9]+|[0-9]{1,3}(?:,[0-9]{3})+)(?:\\.[0-9]+)?")))
        BigDecimal(s.replace(",","")).movePointRight(2).setScale(0,RoundingMode.UNNECESSARY).longValueExact().also { require(it in -99_999_999_999L..99_999_999_999L) }
    }.getOrNull()
    fun parseTime(raw:String,zone:ZoneId):Long? {
        val clean=numericText(raw)
        runCatching { return Instant.parse(clean).toEpochMilli() }
        runCatching { return OffsetDateTime.parse(clean).toInstant().toEpochMilli() }
        val spaced=clean.replace('/','-').replace('年','-').replace('月','-').replace("日","").replace('：',':').replace('T',' ').replace(Regex("\\s+")," ")
        val s=spaced.substringBefore(' ').replace('.','-')+spaced.substringAfter(' ',"").let {if(it.isEmpty()) "" else " $it"}
        for(p in listOf("uuuu-M-d H:m:s","uuuu-M-d H:m","uuuu-M-d","uuuuMMdd H:m:s","uuuuMMdd H:m","uuuuMMdd")) {
            val builder=java.time.format.DateTimeFormatterBuilder().appendPattern(p)
            if(p.endsWith(":s")) builder.appendFraction(java.time.temporal.ChronoField.NANO_OF_SECOND,0,9,true)
            val f=builder.toFormatter().withResolverStyle(ResolverStyle.STRICT)
            runCatching { return (if(p in setOf("uuuu-M-d","uuuuMMdd")) LocalDate.parse(s,f).atStartOfDay() else LocalDateTime.parse(s,f)).atZone(zone).toInstant().toEpochMilli() }
        }
        return null
    }
}
