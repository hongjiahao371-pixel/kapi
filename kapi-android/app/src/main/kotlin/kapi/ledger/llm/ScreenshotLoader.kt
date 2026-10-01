package kapi.ledger.llm

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import android.util.Log
import java.io.ByteArrayOutputStream

/**
 * 截图识别用的图片准备。
 *
 * 存在的理由只有一个：**不能把原图直接发出去**。一张 1080×2400 的截图，
 * JPEG 质量 80 大约 300KB，base64 再膨胀三分之一——一次识别就是 400KB 的
 * 请求体，90 秒超时，慢一点的端点直接超时；而识别账单根本不需要那么多像素。
 *
 * 长边压到 [MAX_EDGE] 就够模型读清金额和商户了，发出去通常只有 60~90KB。
 */
object ScreenshotLoader {

    const val MAX_EDGE = 1280
    private const val JPEG_QUALITY = 80
    private const val TAG = "ScreenshotLoader"

    data class Prepared(val base64: String, val mimeType: String, val width: Int, val height: Int)

    class LoadFailed(msg: String, cause: Throwable? = null) : Exception(msg, cause)

    fun load(context: Context, uri: Uri): Prepared {
        val resolver = context.contentResolver
        Log.i(TAG, "开始读图 uri=$uri authority=${runCatching { uri.authority }.getOrNull()}")

        // 第一遍：只读尺寸，不解像素。
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        try {
            openStream(resolver, uri).use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                throw LoadFailed("这张图解不开（可能是格式不支持，或者文件已损坏）")
            }
        } catch (e: SecurityException) {
            throw LoadFailed("没有读取这张图的权限，重新选一次", e)
        } catch (e: Exception) {
            if (e is LoadFailed) throw e
            throw LoadFailed("读取图片失败：${e.javaClass.simpleName}", e)
        }

        // 第二遍：按算好的采样率真解码。
        val opts = BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(
                bounds.outWidth, bounds.outHeight, MAX_EDGE, MAX_EDGE
            )
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = try {
            openStream(resolver, uri).use { BitmapFactory.decodeStream(it, null, opts) }
        } catch (e: SecurityException) {
            throw LoadFailed("没有读取这张图的权限，重新选一次", e)
        } catch (e: Exception) {
            throw LoadFailed("解码图片失败：${e.javaClass.simpleName}", e)
        } ?: throw LoadFailed("这张图解不开，换一张试试")

        val scaled = try {
            if (decoded.width <= MAX_EDGE && decoded.height <= MAX_EDGE) {
                decoded
            } else {
                // inSampleSize 只能取 2 的幂，长边仍可能超限，再精确缩一次。
                val ratio = MAX_EDGE.toFloat() / maxOf(decoded.width, decoded.height)
                val w = (decoded.width * ratio).toInt().coerceAtLeast(1)
                val h = (decoded.height * ratio).toInt().coerceAtLeast(1)
                Bitmap.createScaledBitmap(decoded, w, h, true).also { decoded.recycle() }
            }
        } catch (e: OutOfMemoryError) {
            // 低内存设备上解码大图会真的 OOM。这不是"错误",是正常的环境限制,
            // 兜一层说明比让 App 崩掉强。
            throw LoadFailed("图片太大，这台设备处理不了。换一张小点的截图")
        }

        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        val bytes = out.toByteArray()
        val mime = scaled.mimeType() ?: "image/jpeg"
        val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        Log.i(
            TAG,
            "截图已压缩：${scaled.width}x${scaled.height} " +
                "${bytes.size / 1024}KB -> base64 ${b64.length / 1024}KB ($mime)",
        )
        val result = Prepared(b64, mime, scaled.width, scaled.height)
        scaled.recycle()
        return result
    }

    /**
     * 拿输入流。
     *
     * `contentResolver.openInputStream` 对某些 photo picker 返回的 URI 会**返回
     * null 而不是抛异常**（实测在 Android 16 模拟器上就发生过），而
     * `openFileDescriptor` 对同一批 URI 是好的。所以两条路都得走。
     *
     * 走 fd 的时候要自己包一层 InputStream 并保证 fd 被关掉——
     * 忘了 close 会泄漏，而且这个泄漏在单测里看不见。
     */
    private fun openStream(
        resolver: android.content.ContentResolver,
        uri: Uri,
    ): java.io.InputStream {
        resolver.openInputStream(uri)?.let { return it }
        val fd = resolver.openFileDescriptor(uri, "r")
            ?: throw LoadFailed("打不开这张图片（相册可能刚被清理了，重新选一次）")
        return try {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(fd)
        } catch (e: Exception) {
            runCatching { fd.close() }
            throw e
        }
    }

    private fun Bitmap.mimeType(): String? =
        if (config == Bitmap.Config.RGB_565) null else "image/jpeg"
}

/**
 * 算 `inSampleSize`。
 *
 * 必须是 **2 的幂**：BitmapFactory 内部是按位运算跳行的，给非幂值的行为未定义，
 * 有些机型会直接解出全尺寸——那就没有省下任何内存，只是白算了一遍。
 *
 * 这函数只有五行，但它同时决定两件相反的事：算大了图片糊、模型读不出金额；
 * 算小了…其实只是多传点字节，不会出错。所以宁可**偏小**（偏清晰）。
 */
fun calculateInSampleSize(srcWidth: Int, srcHeight: Int, reqWidth: Int, reqHeight: Int): Int {
    // 目标尺寸也必须防住，不能只防源图。reqWidth/reqHeight 为 0 或负数时，
    // 循环条件 `halfH / sample >= reqHeight` 恒真，sample 一路翻倍到溢出成 0，
    // 下一轮 `halfH / 0` 直接 ArithmeticException。
    // 写单测时特意喂了 (100,100,0,0) 才逮到——界面上永远传 1280，
    // 但"某个调用方算错了阈值"这种崩溃不该由一个纯函数来承担。
    if (srcWidth <= 0 || srcHeight <= 0 || reqWidth <= 0 || reqHeight <= 0) return 1
    var sample = 1
    if (srcHeight > reqHeight || srcWidth > reqWidth) {
        val halfH = srcHeight / 2
        val halfW = srcWidth / 2
        // 只要半尺寸还够大，就再砍一半。条件用 `>=` 而不是 `>`：
        // 砍到正好等于目标尺寸是安全的（结果不模糊），砍过头才亏。
        while (halfH / sample >= reqHeight && halfW / sample >= reqWidth) {
            sample *= 2
        }
    }
    return sample
}
