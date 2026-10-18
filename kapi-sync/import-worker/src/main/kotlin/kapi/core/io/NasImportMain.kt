package kapi.core.io

import kapi.core.auto.PaymentId
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.Base64

/** Local NAS process boundary. Exactly the Android reader/parser/matcher; no network or DB writes. */
object NasImportMain {
    @Serializable data class Existing(val id:String,val ledgerId:String,val deleted:Boolean=false,val row:TransferRow)
    @Serializable data class Edit(val row:Int,val changes:Map<SmartBillParser.Field,String>)
    @Serializable data class Request(val mode:String,val file:String="",val ledgerId:String="",val rows:List<TransferRow> = emptyList(),val existing:List<Existing> = emptyList(),val edits:List<Edit> = emptyList(),val repairRow:Int=0,val mapping:Map<SmartBillParser.Field,Int> = emptyMap())
    @Serializable data class Candidate(val row:TransferRow,val match:String,val reason:String="",val existingId:String="",val existingDeleted:Boolean=false,val importId:String="",val existingRow:TransferRow?=null,val existingCount:Int=0)
    @Serializable data class Issue(val row:Int,val reason:String)
    @Serializable data class Sheet(val name:String,val records:Int,val status:String)
    @Serializable data class Result(val rows:List<TransferRow> = emptyList(),val candidates:List<Candidate> = emptyList(),val source:String="",val sourceName:String="",val incomeMinor:Long=0,val expenseMinor:Long=0,
        val issueCount:Int=0,val issues:List<Issue> = emptyList(),val neutral:Int=0,val unpaid:Int=0,val duplicates:Int=0,val sheets:List<Sheet> = emptyList(),val sourceRecords:Int=0,val correctedRows:Int=0,val repairValues:Map<SmartBillParser.Field,String> = emptyMap(),val error:String="",val fieldPrompt:String="",val mapping:Map<SmartBillParser.Field,Int> = emptyMap(),val profile:String="",val formatKey:String="",val checks:List<String> = emptyList())
    private fun buildJsonMapping(m:Map<SmartBillParser.Field,Int>):String = kotlinx.serialization.json.buildJsonObject {
        put("mapping",kotlinx.serialization.json.buildJsonObject {m.forEach {(f,i)->put(f.name,kotlinx.serialization.json.JsonPrimitive(i))}})
    }.toString()
    private val json=Json {ignoreUnknownKeys=true;encodeDefaults=true}
    @JvmStatic fun main(args:Array<String>) {
        val result=try {
            val bytes=System.`in`.readNBytes(64*1024*1024+1)
            require(bytes.size<=64*1024*1024) {"数据过多，请分批导入"}
            execute(json.decodeFromString<Request>(bytes.toString(Charsets.UTF_8)))
        } catch(e:Exception) {Result(error=e.message?.take(240) ?: "账单无法读取，请导出无密码的 XLS/XLSX/CSV")}
        print(json.encodeToString(result))
    }
    fun execute(r:Request, pdfReader:((ByteArray)->SmartBillParser.Table)?=null):Result { return when(r.mode) {
        "parse", "repair", "fields", "field-plan", "field-apply" -> {
            val bytes=Base64.getDecoder().decode(r.file)
            require(bytes.size<=BillFileReader.MAX_BYTES) {"文件超过 20 MB"}
            var table=if(BankPdfTable.isPdf(bytes)) requireNotNull(pdfReader) {"请更新 NAS 的 PDF 读取组件"}(bytes) else BillFileReader.inspect(bytes)
            if(r.mode=="field-plan") return Result(fieldPrompt=ImportFieldAI.prompt(table),mapping=table.mapping,profile=ImportIntelligence.profile(table),formatKey=ImportIntelligence.formatKey(table))
            if(r.mode=="field-apply" || r.mapping.isNotEmpty()) {
                val raw=buildJsonMapping(r.mapping)
                val accepted=ImportFieldAI.parse(raw,table)
                table=table.copy(mapping=accepted+table.mapping.filterKeys {it in setOf(SmartBillParser.Field.NATURE,SmartBillParser.Field.ACCOUNT,SmartBillParser.Field.TO_ACCOUNT) && it !in accepted})
            }
            require(r.edits.size<=10000 && r.edits.map {it.row}.distinct().size==r.edits.size) {"修正行重复或过多"}
            val p=if(SmartBillParser.Field.TIME !in table.mapping || table.mapping.keys.none {it in setOf(SmartBillParser.Field.AMOUNT,SmartBillParser.Field.INCOME,SmartBillParser.Field.EXPENSE)}) SmartBillParser.Preview(table,emptyList(),listOf(SmartBillParser.Issue(0,"请调整字段匹配，可用 AI 建议列匹配")),0,0,0) else if(r.edits.isEmpty()) SmartBillParser.preview(table) else ImportReview.repairMany(table,r.edits.associate {it.row to it.changes})
            if(r.mode=="fields") {
                require(p.issues.any {it.row==r.repairRow} && r.repairRow in 1..p.table.records.size) {"只能修正当前错误行"}
                val cells=p.table.records[r.repairRow-1]
                return Result(repairValues=ImportReview.editableFields.filter {it in p.table.mapping}.associateWith {cells.getOrNull(p.table.mapping.getValue(it)).orEmpty().take(128)})
            }
            require(p.rows.size<=10000) {"单次最多一万笔，请按月份分批导入"}
            Result(rows=p.rows,source=p.table.source,sourceName=p.sourceName,incomeMinor=p.incomeMinor,expenseMinor=p.expenseMinor,
                issueCount=p.issues.size,issues=p.issues.map {Issue(it.row,it.reason)},neutral=p.neutral,unpaid=p.unpaid,duplicates=p.duplicates,
                sheets=p.table.sheetReports.map {Sheet(it.name,it.records,it.status)},sourceRecords=ImportReview.sourceRecords(p),correctedRows=r.edits.size,mapping=table.mapping,profile=ImportIntelligence.profile(table),formatKey=ImportIntelligence.formatKey(table),checks=p.checks)
        }
        "match" -> {
            val strong=buildMap {
                r.existing.sortedBy { !it.deleted }.forEach { e ->
                    val row=e.row
                    row.externalKey?.let {put(it+":"+row.direction,e)}
                    val alias=PaymentId.importIdentity(row.note.orEmpty())
                    if(alias!=null && (row.externalKey==null || row.externalKey==alias ||
                        !(row.externalKey.startsWith("ALIPAY_TXN:") || row.externalKey.startsWith("WECHAT_TXN:")))) put(alias+":"+row.direction,e)
                }
            }
            val local=r.existing.filter {it.ledgerId==r.ledgerId && !it.deleted}
            val matcher=ImportDuplicateMatcher(local.map {it.row})
            Result(candidates=r.rows.map {row ->
                val exact=row.externalKey?.let {strong[it+":"+row.direction]}
                val hits=if(exact==null) matcher.suspected(row) else emptyList()
                val hit=hits.firstOrNull()
                val old=exact ?: hit?.let {local[it.index]}
                val restorable=exact?.deleted==true && exact.ledgerId==r.ledgerId
                Candidate(row,if(restorable) "DELETED" else if(exact!=null) "DUPLICATE" else if(hit!=null) "SUSPECTED" else "NEW",
                    if(restorable) "已删除，可勾选恢复原账单" else if(exact!=null) {if(row.source==BankPdfTable.SOURCE) "相同银行明细已存在" else "相同交易号已存在"} else hit?.reason?.label.orEmpty(),old?.id.orEmpty(),old?.deleted ?: false,
                    row.externalKey?.let {TransferFingerprint.stableImportId(it,row.direction)}.orEmpty(),old?.row,if(exact!=null) 1 else hits.size)
            })
        }
        else -> error("不支持的读取请求")
    }}
}
