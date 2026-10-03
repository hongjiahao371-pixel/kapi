package kapi.ledger.llm

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * 端侧截图文字识别（OCR）。2026-10-04 加，动机是用户那句
 * "端侧的截图识别……也不一定非得云端"。
 *
 * 选型是 **ML Kit 文字识别中文 bundled 变体**：
 *  - 模型（约 20MB）直接打进 APK，**运行时不联网、不依赖 Play 服务**——
 *    国内 ROM（realme/OPPO 这两台都没有可用的 GMS）也能跑，这正是选它
 *    而不是 Play Services 变体的原因；
 *  - 识别质量对账单这种"大字号+高对比"的截图绰绰有余，识别出的全文交给
 *    [kapi.core.llm.LocalTextParser]——它本来就是在无障碍读屏的账单全文上
 *    长大的，字段（金额/方向/商户/时间）和截图页完全同源。
 *
 * 云端大模型（[LlmClient]）没有被替代，而是**降级为兜底**：端侧读不出
 * 可用金额、或置信度太低时才走云端（见 AddTransactionScreen 的识别链）。
 * 日常高频路径零流量、零费用、零等待。
 */
object OcrRecognizer {

    private const val TAG = "OcrRecognizer"

    /** 长边压到这个值再喂模型：ML Kit 对超大图会自己缩，但先缩好省内存也省时间。 */
    private const val MAX_EDGE = 1600

    class OcrFailed(msg: String, cause: Throwable? = null) : Exception(msg, cause)

    data class OcrText(val text: String, val blockCount: Int)

    /** 识别一张截图，返回按版面拼好的全文。 */
    suspend fun recognize(context: Context, uri: Uri): OcrText = withContext(Dispatchers.IO) {
        val bitmap = decodeScaled(context, uri)
        val image = InputImage.fromBitmap(bitmap, 0)
        val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        try {
            val visionText: Text = recognizer.process(image).await()
            val text = visionText.text
            if (text.isBlank()) {
                throw OcrFailed("这张图里没认出文字")
            }
            Log.i(TAG, "端侧 OCR 完成：${bitmap.width}x${bitmap.height}，${text.length} 字")
            OcrText(text, visionText.textBlocks.size)
        } catch (e: OcrFailed) {
            throw e
        } catch (e: Exception) {
            // ML Kit 内部错误（罕见：解码失败/内存不足）转成友好提示，不让上层只能看到类名。
            throw OcrFailed("端侧识别没跑起来：${e.javaClass.simpleName}", e)
        } finally {
            recognizer.close()
            bitmap.recycle()
        }
    }

    /** 两遍解码（先量尺寸再采样），长边压到 [MAX_EDGE]。逻辑同 ScreenshotLoader，只是产物是 Bitmap。 */
    private fun decodeScaled(context: Context, uri: Uri): Bitmap {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        try {
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                ?: throw OcrFailed("打不开这张图，重新选一次")
        } catch (e: SecurityException) {
            throw OcrFailed("没有读取这张图的权限，重新选一次", e)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw OcrFailed("这张图解不开（格式不支持或文件已损坏）")
        }
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= MAX_EDGE && bounds.outHeight / (sample * 2) >= MAX_EDGE) {
            sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = try {
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
                ?: throw OcrFailed("打不开这张图，重新选一次")
        } catch (e: SecurityException) {
            throw OcrFailed("没有读取这张图的权限，重新选一次", e)
        } ?: throw OcrFailed("这张图解不开，换一张试试")
        val longEdge = maxOf(decoded.width, decoded.height)
        return if (longEdge <= MAX_EDGE) decoded else {
            val ratio = MAX_EDGE.toFloat() / longEdge
            val scaled = Bitmap.createScaledBitmap(
                decoded,
                (decoded.width * ratio).toInt().coerceAtLeast(1),
                (decoded.height * ratio).toInt().coerceAtLeast(1),
                true,
            )
            decoded.recycle()
            scaled
        }
    }
}
