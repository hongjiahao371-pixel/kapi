package kapi.nasimport

import kapi.core.io.*
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.pdfbox.text.TextPosition
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.math.abs

object BankPdfReader {
    fun inspect(bytes:ByteArray):SmartBillParser.Table {
        require(bytes.size<=BillFileReader.MAX_BYTES) {"PDF 超过 20 MB"}
        Loader.loadPDF(bytes).use {doc ->
            require(!doc.isEncrypted && doc.currentAccessPermission.canExtractContent()) {"请导出无密码、允许读取文字的银行 PDF"}
            require(doc.numberOfPages in 1..50) {"PDF 页数超过 50 页，请分批导入"}
            val pages=mutableListOf<MutableList<BankPdfTable.Glyph>>();var count=0
            val stripper=object:PDFTextStripper() {
                var rotation=0

                override fun startPage(page:PDPage) {super.startPage(page);rotation=page.rotation;pages+=mutableListOf<BankPdfTable.Glyph>()}
                override fun processTextPosition(t:TextPosition) {
                    require(++count<=500000) {"PDF 文字过多"}
                    val m=t.textMatrix
                    val angle=(Math.toDegrees(kotlin.math.atan2(m.shearY.toDouble(),m.scaleX.toDouble()))+360)%360
                    val delta=abs(angle-rotation)
                    if(minOf(delta,360-delta)<0.05)
                        pages.last()+=BankPdfTable.Glyph(t.unicode,t.xDirAdj,t.yDirAdj,t.widthDirAdj)
                }
            }
            stripper.getText(doc)
            return BankPdfTable.inspect(pages.map {BankPdfTable.Page(it)})
        }
    }
}
object Main {
    @JvmStatic fun main(args:Array<String>) {
        val json=Json {ignoreUnknownKeys=true;encodeDefaults=true}
        val result=try {
            val bytes=System.`in`.readNBytes(64*1024*1024+1)
            require(bytes.size<=64*1024*1024) {"数据过多，请分批导入"}
            NasImportMain.execute(json.decodeFromString<NasImportMain.Request>(bytes.toString(Charsets.UTF_8)),BankPdfReader::inspect)
        } catch(e:Exception) {NasImportMain.Result(error=e.message?.take(240) ?: "PDF 或账单无法读取，请重新导出")}
        print(json.encodeToString(result))
    }
}
