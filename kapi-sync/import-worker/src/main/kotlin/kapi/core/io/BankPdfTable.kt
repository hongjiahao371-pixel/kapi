package kapi.core.io

import java.math.BigDecimal
import java.security.MessageDigest
import kotlin.math.abs

/** Coordinates come from a local PDF text decoder. Only a verified ICBC electronic table is accepted. */
object BankPdfTable {
    const val SOURCE="BILL_BANK_ICBC"
    data class Glyph(val text:String,val x:Float,val y:Float,val width:Float)
    data class Page(val glyphs:List<Glyph>)
    private data class Line(val y:Float,val glyphs:List<Glyph>) {
        val text get()=glyphs.sortedBy {it.x}.joinToString("") {it.text}.replace(" ","")
    }
    private val labels=listOf("交易日期","账号","储种","序号","币种","钞汇","摘要","地区","收入/支出金额","余额","渠道")
    private val date=Regex("\\d{4}-\\d{2}-\\d{2}")
    private val cash=Regex("[+-]?(?:\\d{1,3}(?:,\\d{3})+|\\d+)\\.\\d{2}")
    private val headers=listOf("时间","收支","金额","备注","账户","币种","交易号","银行待核对提示")
    fun isPdf(bytes:ByteArray)=bytes.take(5).toByteArray().toString(Charsets.US_ASCII)=="%PDF-"
    private fun lines(glyphs:List<Glyph>):List<Line> {
        val grouped=mutableListOf<MutableList<Glyph>>()
        glyphs.filter {it.text.isNotBlank()}.sortedBy {it.y}.forEach {g ->
            if(grouped.isEmpty() || abs(grouped.last().first().y-g.y)>1.5f)grouped+=mutableListOf(g)
            else grouped.last()+=g
        }
        return grouped.map {Line(it.first().y,it.sortedBy {g->g.x})}
    }
    private fun money(s:String):Long {
        require(cash.matches(s)) {"金额列含有干扰文字或格式异常"}
        return BigDecimal(s.replace(",","")).movePointRight(2).longValueExact()
    }
    fun inspect(pages:List<Page>):SmartBillParser.Table {
        require(pages.size in 1..50) {"PDF 页数超过 50 页，请按月份导出"}
        require(pages.sumOf {it.glyphs.size}<=500000) {"PDF 文字过多，请分批导入"}
        val records=mutableListOf<List<String>>();val reports=mutableListOf<SmartBillParser.SheetReport>()
        val previous=mutableMapOf<String,Long>()
        var card:String?=null
        pages.forEachIndexed {pageIndex,page ->
            val all=lines(page.glyphs)
            require(all.any {it.text.contains("中国工商银行借记账户历史明细")}) {"暂支持工行借记账户电子历史明细；扫描件或其他银行 PDF 请先导出表格"}
            val currentCard=all.firstNotNullOfOrNull {Regex("卡号[:：]?(\\d{12,22})").find(it.text)?.groupValues?.get(1)}
            require(currentCard!=null && (card==null || card==currentCard)) {"PDF 卡号缺失或混有多个账户，请分别导入"}
            card=currentCard
            val header=all.firstOrNull {line->labels.all {line.text.contains(it)}}
                ?: error("第 ${pageIndex+1} 页表头不完整，未导入")
            fun center(label:String):Float {
                val start=header.text.indexOf(label);val gs=header.glyphs.filter {it.text.isNotBlank()}
                var offset=0;val hits=gs.filter {g->val a=offset;offset+=g.text.replace(" ","").length;a<start+label.length && offset>start}
                require(hits.isNotEmpty()) {"PDF 列位置无法识别"}
                return (hits.first().x+hits.last().x+hits.last().width)/2
            }
            val centers=labels.map(::center)
            require(centers.zipWithNext().all {it.first<it.second}) {"PDF 列顺序异常"}
            val bounds=centers.zipWithNext().map {(it.first+it.second)/2}
            fun inColumn(g:Glyph,column:Int):Boolean {
                val x=g.x+g.width/2
                return (column==0 || x>=bounds[column-1]) && (column==10 || x<bounds[column])
            }
            val footer=all.firstOrNull {it.y>header.y && it.text.contains("本页支出算术合计")}
                ?: error("第 ${pageIndex+1} 页缺少银行合计校验")
            val dates=all.filter {it.y>header.y && it.y<footer.y}.mapNotNull {line ->
                val text=line.glyphs.filter {inColumn(it,0)}.joinToString("") {it.text}.trim()
                if(date.matches(text)) line.y to text else null
            }
            val declaredCount=all.firstNotNullOfOrNull {Regex("本页交易笔数[:：](\\d+)").find(it.text)?.groupValues?.get(1)?.toIntOrNull()}
            require(declaredCount!=null && declaredCount==dates.size) {"第 ${pageIndex+1} 页笔数校验失败，未导入"}
            val expense=Regex("本页支出算术合计[:：]([\\d,.]+)").find(footer.text)?.groupValues?.get(1)?.let(::money)
            val income=Regex("本页收入算术合计[:：]([\\d,.]+)").find(footer.text)?.groupValues?.get(1)?.let(::money)
            require(expense!=null && income!=null) {"PDF 页合计格式异常"}
            var exp=0L;var inc=0L
            dates.forEachIndexed {index,(y,d) ->
                val end=dates.getOrNull(index+1)?.first ?: footer.y
                val row=page.glyphs.filter {it.y>=y-1.5f && it.y<end-1.5f}
                fun cell(column:Int)=lines(row.filter {inColumn(it,column)}).joinToString("") {it.text}
                val timeText=cell(0)
                val at=d+" "+timeText.removePrefix(d)
                require(Regex("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}").matches(at) && SmartBillParser.parseTime(at,java.time.ZoneId.of("Asia/Shanghai"))!=null) {"第 ${pageIndex+1} 页第 ${index+1} 笔时间无效"}
                val account=cell(1);require(account.matches(Regex("\\d{12,22}"))) {"PDF 账号列受干扰，请重新导出"}
                val currency=cell(4);require(currency=="人民币") {"暂不支持 PDF 非人民币账户"}
                val summary=cell(6);val channel=cell(10);val signed=money(cell(8));val balance=money(cell(9))
                require(signed!=0L && signed!=Long.MIN_VALUE && abs(signed)<=99999999999L) {"银行交易金额超出范围"}
                if(signed<0)exp=Math.addExact(exp,-signed) else inc=Math.addExact(inc,signed)
                previous[account]?.let {require(Math.addExact(it,signed)==balance) {"第 ${pageIndex+1} 页第 ${index+1} 笔余额连续校验失败"}}
                previous[account]=balance
                val warning=when {
                    signed<0 && summary in setOf("消费","缴费","无卡支付") -> ""
                    signed>0 && summary in setOf("工资","补贴","利息") -> ""
                    summary in setOf("退款","退货") -> "退款/退货需要核对原消费，不能直接作为普通收入"
                    else -> "「$summary」需要核对是否为账户划转、充值或借贷，未作为普通收支导入"
                }
                // The balance is identity evidence, never a bill amount. No real transaction ID is claimed.
                val fingerprint=listOf(account,at,signed.toString(),balance.toString()).joinToString("|")
                val id="BANK_ICBC:"+MessageDigest.getInstance("SHA-256").digest(fingerprint.toByteArray()).joinToString("") {"%02x".format(it)}
                records+=listOf(at,if(signed<0)"支出" else "收入",BigDecimal(abs(signed)).movePointLeft(2).toPlainString(),
                    "银行摘要：$summary｜渠道：$channel｜工行卡尾号：${currentCard.takeLast(4)}","工商银行（尾号${currentCard.takeLast(4)}）","CNY",id,if(warning.isBlank()) "" else "PDF 第 ${pageIndex+1} 页第 ${index+1} 笔 · $at · ${if(signed<0) "支出" else "收入"} ¥${BigDecimal(abs(signed)).movePointLeft(2).toPlainString()}：$warning")
            }
            require(exp==expense && inc==income) {"第 ${pageIndex+1} 页收支合计校验失败，未导入"}
            reports+=SmartBillParser.SheetReport("工行 PDF 第 ${pageIndex+1} 页",dates.size,"笔数、收支合计已核对")
        }
        require(records.isNotEmpty()) {"PDF 没有可读取的历史明细"}
        return SmartBillParser.Table(headers,records,SmartBillParser.infer(headers),SOURCE,reports)
    }
}
