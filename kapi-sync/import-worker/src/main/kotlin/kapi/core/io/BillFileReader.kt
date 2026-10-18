package kapi.core.io

import java.math.BigDecimal
import java.math.RoundingMode
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.time.LocalDate
import java.util.zip.CRC32
import org.apache.commons.compress.archivers.zip.ZipFile
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/** Bounded archive decoding; no filesystem extraction, macros, formulas or external XML entities. */
object BillFileReader {
    const val MAX_BYTES=20*1024*1024
    private data class Decoded(val text:String,val reports:List<SmartBillParser.SheetReport> = emptyList())
    fun decode(bytes:ByteArray):String = read(bytes).text
    fun inspect(bytes:ByteArray):SmartBillParser.Table {
        val read=read(bytes)
        return SmartBillParser.inspect(read.text).copy(sheetReports=read.reports)
    }
    private fun read(bytes:ByteArray):Decoded {
        require(bytes.size<=MAX_BYTES) { "文件超过 20 MB，请分批导入" }
        require(!BankPdfTable.isPdf(bytes)) {"PDF 需要本地银行明细读取组件，请更新完整应用"}
        if(bytes.take(8)==listOf(0xD0,0xCF,0x11,0xE0,0xA1,0xB1,0x1A,0xE1).map(Int::toByte)) return legacyWorkbook(bytes)
        if(bytes.size<4 || bytes[0]!=80.toByte() || bytes[1]!=75.toByte()) return Decoded(text(bytes))
        val entries=LinkedHashMap<String,ByteArray>(); var total=0
        checkArchiveDirectory(bytes) // Bound metadata before a ZIP reader allocates its entry list.
        SeekableInMemoryByteChannel(bytes).use { channel ->
          ZipFile.builder().setSeekableByteChannel(channel).get().use { z ->
            val files=z.entries
            while(files.hasMoreElements()) {
                val e=files.nextElement()
                require(!e.generalPurposeBit.usesEncryption()) {"账单已加密，请先解压后导入"}
                require(e.method in setOf(0,8) && z.canReadEntryData(e)) {"不支持该压缩方式，请导出标准 ZIP/XLSX"}
                if(e.isDirectory) continue
                require(e.size in 0..MAX_BYTES.toLong() && total+e.size<=MAX_BYTES) {"账单解压后超过 20 MB，请分批导入"}
                require(!entries.containsKey(e.name)) {"压缩包包含重复文件名"}
                val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192);val crc=CRC32()
                z.getInputStream(e).use { input ->
                    while(true) {val n=input.read(buffer);if(n<0) break
                        require(total+out.size()+n<=MAX_BYTES) {"账单解压后超过 20 MB，请分批导入"}
                        crc.update(buffer,0,n);out.write(buffer,0,n)
                    }
                }
                require(out.size().toLong()==e.size && crc.value==e.crc) {"账单文件校验失败，请重新下载"}
                val data=out.toByteArray();total+=data.size;entries[e.name]=data
            }
          }
        }
        if("xl/workbook.xml" in entries) {
            val workbook=xml(entries.getValue("xl/workbook.xml"))
            val props=workbook.getElementsByTagName("workbookPr").item(0) as? Element
            val date1904=props?.getAttribute("date1904") in listOf("1","true")
            val strings=entries["xl/sharedStrings.xml"]?.let { b ->
                val nodes=xml(b).getElementsByTagName("si")
                (0 until nodes.length).map { nodes.item(it).textContent }
            }.orEmpty()
            val relationships=entries["xl/_rels/workbook.xml.rels"]?.let { xml(it).getElementsByTagName("Relationship") }
            val targets=buildMap {
                if(relationships!=null) for(i in 0 until relationships.length) {
                    val e=relationships.item(i) as Element
                    require(e.getAttribute("TargetMode")!="External") { "不支持外部工作表" }
                    val target=e.getAttribute("Target").removePrefix("/")
                    put(e.getAttribute("Id"),if(target.startsWith("xl/")) target else "xl/"+target.removePrefix("./"))
                }
            }
            val named=workbook.getElementsByTagName("sheet")
            val sheets=if(named.length>0) (0 until named.length).map { index ->
                val e=named.item(index) as Element
                val path=targets[e.getAttribute("r:id")] ?: "xl/worksheets/sheet"+(index+1)+".xml"
                BillWorkbookAdapter.Sheet(e.getAttribute("name"),sheet(requireNotNull(entries[path]) { "工作表文件缺失" },strings,date1904))
            } else entries.filterKeys { it.startsWith("xl/worksheets/") && it.endsWith(".xml") }.map { (name,data) ->
                BillWorkbookAdapter.Sheet(name,sheet(data,strings,date1904))
            }
            val combined=BillWorkbookAdapter.combine(sheets)
            return Decoded(combined.text,combined.reports)
        }
        require(entries.keys.none {it.endsWith(".pdf",true)}) {"压缩包内包含 PDF，请先解压后单独选择银行 PDF"}
        val bills=entries.filterKeys { it.endsWith(".csv",true) || it.endsWith(".tsv",true) }.values.map(::text)
        val matching=bills.filter { runCatching { SmartBillParser.inspect(it) }.isSuccess }
        require(matching.size<=1) { "压缩包包含多份账单，请选择其中一份导入" }
        return Decoded(matching.firstOrNull() ?: bills.singleOrNull().orEmpty())
    }
    private fun checkArchiveDirectory(bytes:ByteArray) {
        fun u16(i:Int)=(bytes[i].toInt() and 255) or ((bytes[i+1].toInt() and 255) shl 8)
        fun u32(i:Int)=(0..3).fold(0L) {v,n -> v or ((bytes[i+n].toLong() and 255) shl (n*8))}
        val start=(bytes.size-65557).coerceAtLeast(0)
        val end=(bytes.size-22 downTo start).firstOrNull {i -> u32(i)==0x06054b50L && i+22+u16(i+20)==bytes.size}
            ?: error("压缩包目录缺失，请重新下载账单")
        require(u16(end+4)==0 && u16(end+6)==0 && u16(end+8)==u16(end+10)) {"不支持分卷压缩包"}
        require(u16(end+10)<=512) {"压缩包文件过多"}
        val size=u32(end+12);val offset=u32(end+16)
        require(size<=MAX_BYTES && offset+size<=end) {"压缩包目录无效，请重新下载"}
        var pos=offset.toInt();val limit=(offset+size).toInt();var count=0
        while(pos<limit) {
            require(++count<=512 && pos+46<=limit && u32(pos)==0x02014b50L) {"压缩包目录无效或文件过多"}
            val length=46+u16(pos+28)+u16(pos+30)+u16(pos+32)
            require(pos+length<=limit) {"压缩包目录截断，请重新下载"}
            pos+=length
        }
        require(count==u16(end+10)) {"压缩包目录数量不符，请重新下载"}
    }
    private fun legacyWorkbook(bytes:ByteArray):Decoded {
        val settings=jxl.WorkbookSettings().apply {
            setEncoding("UTF-8");setLocale(java.util.Locale.ROOT);setSuppressWarnings(true)
            setDrawingsDisabled(true);setNamesDisabled(true);setPropertySets(false);setGCDisabled(true)
        }
        val workbook=try {jxl.Workbook.getWorkbook(bytes.inputStream(),settings)} catch(e:Exception) {
            throw IllegalArgumentException("XLS 无法读取，可能已加密、损坏或不是 Excel 账单；请导出无密码的 XLS/XLSX/CSV",e)
        }
        try {
            require(workbook.numberOfSheets<=64) { "工作表数量超过 64 张，请分批导入" }
            var cells=0;var chars=0;var totalRows=0
            val sheets=workbook.sheets.map { sheet ->
                require(sheet.columns<=256 && sheet.rows<=100_000) { "工作簿行列过多，请分批导入" }
                totalRows+=sheet.rows;require(totalRows<=100_000) { "账单超过十万行，请分批导入" }
                val numeric=mutableSetOf<Pair<Int,Int>>()
                val rows=(0 until sheet.rows).map { ri -> (0 until sheet.columns).map { ci ->
                    require(++cells<=1_000_000) { "工作簿单元格过多，请分批导入" }
                    val c=sheet.getCell(ci,ri)
                    require(c !is jxl.FormulaCell) { "工作簿含公式，请在表格软件里导出为纯值 CSV" }
                    val value=when(c) {
                        is jxl.DateCell -> {
                            val at=c.date.toInstant().atZone(java.time.ZoneOffset.UTC)
                            val pattern=(c.dateFormat as? java.text.SimpleDateFormat)?.toPattern().orEmpty()
                            val hasClock=c.isTime || pattern.any {it in "HhKs"}
                            val seconds=pattern.contains('s')
                            at.format(java.time.format.DateTimeFormatter.ofPattern(if(c.isTime) (if(seconds) "HH:mm:ss" else "HH:mm") else if(hasClock) (if(seconds) "yyyy-MM-dd HH:mm:ss" else "yyyy-MM-dd HH:mm") else "yyyy-MM-dd"))
                        }
                        is jxl.NumberCell -> {numeric.add(ri to ci);BigDecimal.valueOf(c.value).stripTrailingZeros().toPlainString()}
                        else -> c.contents
                    }
                    chars+=value.length;require(value.length<=128*1024 && chars<=MAX_BYTES) { "工作簿内容过大，请分批导入" }
                    value
                }.toMutableList() }
                normalizeNumericMoney(rows,numeric)
                normalizeDates(rows,false)
                BillWorkbookAdapter.Sheet(sheet.name,rows.joinToString("\r\n") {row -> row.joinToString(",",transform=LedgerCsv::quote)})
            }
            val combined=BillWorkbookAdapter.combine(sheets)
            return Decoded(combined.text,combined.reports)
        } finally {workbook.close()}
    }
    private fun text(bytes:ByteArray):String {
        val charset=when {
            bytes.size>=2 && bytes[0]==(-1).toByte() && bytes[1]==(-2).toByte() -> Charsets.UTF_16LE
            bytes.size>=2 && bytes[0]==(-2).toByte() && bytes[1]==(-1).toByte() -> Charsets.UTF_16BE
            else -> Charsets.UTF_8
        }
        fun decode(c:Charset)=c.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString().removePrefix(LedgerCsv.BOM)
        return runCatching { decode(charset) }.getOrElse { decode(Charset.forName("GB18030")) }
    }
    private fun xml(bytes:ByteArray):org.w3c.dom.Document {
        // Android's factory does not expose every JAXP feature, so reject DTD/entity syntax before parsing too.
        val s=String(bytes,Charsets.UTF_8)
        require(!s.contains("<!DOCTYPE",true) && !s.contains("<!ENTITY",true)) { "不支持含外部实体的工作簿" }
        val f=DocumentBuilderFactory.newInstance(); f.isExpandEntityReferences=false
        runCatching { f.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true) }
        runCatching { f.setFeature("http://xml.org/sax/features/external-general-entities",false) }
        runCatching { f.setFeature("http://xml.org/sax/features/external-parameter-entities",false) }
        val builder=f.newDocumentBuilder(); builder.setEntityResolver { _,_ -> org.xml.sax.InputSource(java.io.StringReader("")) }
        return builder.parse(bytes.inputStream())
    }
    private fun sheet(bytes:ByteArray,shared:List<String>,date1904:Boolean):String {
        val nodes=xml(bytes).getElementsByTagName("row"); val rows=ArrayList<MutableList<String>>()
        val numeric=mutableSetOf<Pair<Int,Int>>()
        require(nodes.length<=100_000) { "账单超过十万行" }
        for(i in 0 until nodes.length) {
            val r=nodes.item(i) as Element; val cs=r.getElementsByTagName("c"); val values=mutableListOf<String>()
            for(j in 0 until cs.length) {
                val c=cs.item(j) as Element; val ref=c.getAttribute("r").takeWhile { it in 'A'..'Z' }
                val col=if(ref.isEmpty()) values.size else ref.fold(0) { a,ch -> a*26+(ch-'A'+1) }-1
                require(col in 0..255) { "工作簿列数过多" }; while(values.size<=col) values.add("")
                // A formula cell could contain a stale cached result. Require a value-only export.
                require(c.getElementsByTagName("f").length==0) { "工作簿含公式，请在表格软件里导出为纯值 CSV" }
                val v=c.getElementsByTagName("v").item(0)?.textContent.orEmpty()
                if(c.getAttribute("t") in setOf("","n") && v.toBigDecimalOrNull()!=null) numeric.add(rows.size to col)
                values[col]=when(c.getAttribute("t")) {
                    "s" -> shared.getOrNull(v.toIntOrNull() ?: -1).orEmpty()
                    "inlineStr" -> c.getElementsByTagName("is").item(0)?.textContent.orEmpty()
                    else -> v
                }
            }
            if(values.any { it.isNotBlank() }) rows+=values
        }
        normalizeNumericMoney(rows,numeric)
        normalizeDates(rows,date1904)
        return rows.joinToString("\r\n") { row -> row.joinToString(",",transform=LedgerCsv::quote) }
    }
    /** Only actual Excel numeric MONEY cells get IEEE-754 noise repair. Text/CSV stays exact. */
    private fun normalizeNumericMoney(rows:List<MutableList<String>>,numeric:Set<Pair<Int,Int>>) {
        val header=rows.firstOrNull {val m=SmartBillParser.infer(it);SmartBillParser.Field.TIME in m && m.keys.any {f->f in setOf(SmartBillParser.Field.AMOUNT,SmartBillParser.Field.INCOME,SmartBillParser.Field.EXPENSE)}} ?: return
        val moneyCols=SmartBillParser.infer(header).filterKeys {it in setOf(SmartBillParser.Field.AMOUNT,SmartBillParser.Field.INCOME,SmartBillParser.Field.EXPENSE)}.values.toSet()
        rows.forEachIndexed {index,row->moneyCols.forEach {col->
            if(index to col in numeric) {
                val n=row.getOrNull(col)?.toBigDecimalOrNull() ?: return@forEach
                if(n.scale() !in -16..32 || n.precision()>32 || n.abs()>BigDecimal("999999999.99")) return@forEach
                val cents=n.setScale(2,RoundingMode.HALF_UP)
                val ulp=BigDecimal.valueOf(Math.ulp(n.toDouble())).multiply(BigDecimal(4)).min(BigDecimal("0.000001"))
                row[col]=(if(n.subtract(cents).abs()<=ulp) cents else n).stripTrailingZeros().toPlainString()
            }
        }}
    }
    private fun normalizeDates(rows:List<MutableList<String>>,date1904:Boolean) {
        val header=rows.firstOrNull { val m=SmartBillParser.infer(it); SmartBillParser.Field.TIME in m && (SmartBillParser.Field.AMOUNT in m || SmartBillParser.Field.INCOME in m || SmartBillParser.Field.EXPENSE in m) }
        val mapping=header?.let(SmartBillParser::infer).orEmpty()
        val clockCol=mapping[SmartBillParser.Field.CLOCK]
        val timeCols=header?.mapIndexedNotNull { i,s -> if(i!=clockCol && SmartBillParser.infer(listOf(s)).containsKey(SmartBillParser.Field.TIME)) i else null }.orEmpty()
        rows.forEach { row -> timeCols.forEach { col ->
            val n=row.getOrNull(col)?.toBigDecimalOrNull()
            if(n!=null && n>=BigDecimal.ZERO && n<=BigDecimal(100000)) {
                val secs=n.multiply(BigDecimal(86400)).setScale(0,RoundingMode.HALF_UP).longValueExact()
                val origin=if(date1904) LocalDate.of(1904,1,1) else LocalDate.of(1899,12,30)
                row[col]=origin.atStartOfDay().plusSeconds(secs).format(java.time.format.DateTimeFormatter.ofPattern(if(clockCol!=null || n.stripTrailingZeros().scale()<=0) "yyyy-MM-dd" else "yyyy-MM-dd HH:mm:ss"))
            }
        } }
        rows.forEach { row -> clockCol?.let { col ->
            val n=row.getOrNull(col)?.toBigDecimalOrNull()
            if(n!=null && n>=BigDecimal.ZERO && n<BigDecimal.ONE) {
                val seconds=n.multiply(BigDecimal(86400)).setScale(0,RoundingMode.HALF_UP).longValueExact()
                if(seconds<86400) row[col]=java.time.LocalTime.ofSecondOfDay(seconds).toString().let { if(it.length==5) "$it:00" else it }
            }
        } }
    }
}
