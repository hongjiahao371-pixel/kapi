package kapi.ledger.sync

import android.util.Log
import kapi.ledger.AppContainer
import kapi.ledger.TokenStore
import kapi.ledger.data.KapiDatabase

/**
 * 同步的"门面"。UI 只跟这一层打交道，不直接碰 engine / token / client。
 *
 * 单独一层还有个实际好处：地址归一化、配对两步走、错误翻译这些只写一次，
 * 不会在「设置页」和「配对对话框」里各写一遍然后慢慢分叉。
 */
class SyncCoordinator(
    private val engine: SyncEngine,
    private val db: KapiDatabase,
    private val client: SyncClient,
    private val tokens: TokenStore,
) {

    sealed interface PairOutcome {
        data class Paired(val rowsOnServer: Int) : PairOutcome
        data class Failed(val message: String) : PairOutcome
    }

    val isPaired: Boolean get() = tokens.isPaired

    /**
     * 本机设备 id。没配 NAS 时也要有一个稳定的值——Lamport 戳的平局裁决靠它，
     * 用随机值的话每次冷启动同一台设备会以不同身份写入合并戳。
     */
    val deviceId: String
        get() = tokens.deviceId.ifBlank {
            LOCAL_DEVICE_ID.also { tokens.deviceId = it }
        }

    /**
     * 把用户敲的地址变成能用的 base URL。
     *
     * 用户照着提示敲 `192.168.1.10:8787` 是最自然的写法，而 OkHttp 遇到没有
     * scheme 的 URL 会直接抛 IllegalArgumentException，错误信息是纯英文的
     * "Expected URL scheme 'http' or 'https' but no scheme was found"——用户完全
     * 不知道是自己少敲了四个字。提示文案里给的示例本来就是不带 scheme 的，
     * 所以这里必须兜住。
     */
    fun normalizeBaseUrl(input: String): String {
        val trimmed = input.trim().trimEnd('/')
        if (trimmed.isEmpty()) return ""
        return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            trimmed
        } else {
            "http://$trimmed"
        }
    }

    /** 连通性探测。配对向导第 1 步，免鉴权。 */
    suspend fun probe(baseUrl: String): Result<Int> = runCatching {
        val h = client.health(normalizeBaseUrl(baseUrl))
        if (!h.ok) error("服务端返回了非 ok 的健康状态")
        h.rows
    }

    /** 配对。成功后把 baseUrl / token / deviceId 落到本地。 */
    suspend fun pair(baseUrlInput: String, pairCode: String): PairOutcome {
        val url = normalizeBaseUrl(baseUrlInput)
        if (url.isEmpty()) return PairOutcome.Failed("请先填写服务器地址")

        return try {
            // 先探活。分两步的体验差别很大：连不上和配对码错是两回事，
            // 错误文案必须指向用户接下来该干的事。
            val health = client.health(url)
            val paired = client.pair(url, pairCode.trim(), DEFAULT_DEVICE_NAME)
            tokens.baseUrl = url
            tokens.token = paired.token
            tokens.deviceId = paired.deviceId
            PairOutcome.Paired(health.rows)
        } catch (f: SyncFailure) {
            Log.w(TAG, "配对失败 (SyncFailure): $url", f)
            PairOutcome.Failed(f.detail)
        } catch (t: Throwable) {
            // 这一支必须留下堆栈。配对是"看起来像网络问题"的最常见故障点，
            // 而 OkHttp/URL 解析这类异常抛出时 message 常常是 null，
            // 光看界面上的文案永远定位不到。
            Log.e(TAG, "配对失败 ($url, ${pairCode.take(2)}****): ${t.javaClass.name}: ${t.message}", t)
            PairOutcome.Failed(
                t.message?.takeIf { it.isNotBlank() }
                    ?: "${t.javaClass.simpleName}：没有更多线索了，请把 NAS 地址原样填一次"
            )
        }
    }

    /** 立即同步。没配 NAS 时返回 NotPaired，不报错——那是正常状态。 */
    suspend fun syncNow(): SyncEngine.SyncResult = engine.sync(
        tokens.baseUrl, tokens.token, deviceId,
    )

    fun disconnect() {
        tokens.clear()
    }

    private companion object {
        const val TAG = "SyncCoordinator"
        const val LOCAL_DEVICE_ID = "local-device"
        const val DEFAULT_DEVICE_NAME = "Android 手机"
    }
}
