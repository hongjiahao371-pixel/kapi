package kapi.ledger.sync

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * NAS 同步服务的 HTTP 客户端。
 *
 * 只依赖 `core` 里的纯逻辑类型，不掺任何 Android API，所以可以在 JVM 上测。
 */

@Serializable
data class HealthDto(
    val ok: Boolean = false,
    @SerialName("server_version") val serverVersion: String = "",
    @SerialName("db_seq") val dbSeq: Long = 0,
    @SerialName("device_count") val deviceCount: Int = 0,
    val rows: Int = 0,
    @SerialName("pair_code") val pairCode: String? = null,
)

@Serializable
data class PairRequest(val pair_code: String, val device_name: String)

@Serializable
data class PairDto(
    @SerialName("device_id") val deviceId: String,
    val token: String,
    val cursor: Long = 0,
)

@Serializable
data class ChangesDto(
    val changes: List<LogEntryDto> = emptyList(),
    val cursor: Long = 0,
    @SerialName("has_more") val hasMore: Boolean = false,
)

@Serializable
data class LogEntryDto(
    val seq: Long,
    val entity: String,
    @SerialName("entity_id") val entityId: String,
    val op: String,
    @SerialName("change_id") val changeId: String = "",
    val origin: String = "",
    val snapshot: kapi.core.sync.Snapshot? = null,
)

@Serializable
data class PushRequest(
    @SerialName("device_id") val deviceId: String,
    val changes: List<kapi.core.sync.OutboxChange>,
)

@Serializable
data class RejectedDto(
    @SerialName("change_id") val changeId: String,
    val entity: String = "",
    @SerialName("entity_id") val entityId: String = "",
    val reason: String = "",
    val fields: List<String> = emptyList(),
    val kind: String = "",
)

@Serializable
data class PushDto(
    val applied: List<String> = emptyList(),
    val rejected: List<RejectedDto> = emptyList(),
    val cursor: Long = 0,
)

/**
 * 分层错误。UI 靠这个区分"没配 NAS"（正常状态）和"配了但连不上"（要修）。
 *
 * **每一层都必须把文案同时交给 [detail] 和 [Exception]。**
 * 只放进 data class 字段的话 [Throwable.message] 会是 null，界面上就成了
 * 光秃秃的"连接失败"——而这些文案存在的全部意义就是告诉用户接下来该干什么。
 * 这个坑踩过一次：连不上 NAS 到底是因为 NAS 没开机、端口没映射、还是地址填错，
 * 界面上一个字都看不出来。
 */
sealed class SyncFailure : Exception() {
    /**
     * 给人看的一句话，必须说明"接下来该干什么"。
     *
     * 用抽象属性而不是构造参数，是因为每个子类都是 data class，构造参数
     * 已经占了位置；这里只需要一个统一的口子给界面和日志读。
     */
    abstract val detail: String

    /** 地址解析不了，或端口不通。多半是 IP 填错或容器没映射端口。 */
    data class Unreachable(override val detail: String) : SyncFailure()

    /** 连上了但鉴权失败：token 失效或设备被清空。要引导重新配对。 */
    data class Unauthorized(override val detail: String) : SyncFailure()

    /** 配对码错或过期。 */
    data class Pairing(override val detail: String) : SyncFailure()

    /** 服务端返回了我们不认识的错误。 */
    data class Protocol(override val detail: String) : SyncFailure()

    /** 网络抖动。可以原地重试。 */
    data class Transient(override val detail: String) : SyncFailure()
}

class SyncClient(
    private val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        explicitNulls = false
    },
    private val http: OkHttpClient = defaultHttpClient(),
) {
    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

    /**
     * 配对向导第 1 步：先打 health。
     *
     * 免鉴权，而且只暴露行数不暴露内容——这正是它能被用作连通性探测的原因。
     * 界面上这一步的错误信息直接决定用户会不会放弃，所以异常消息要写成
     * 「接下来该干什么」，而不是「connect ECONNREFUSED」。
     */
    suspend fun health(baseUrl: String): HealthDto =
        get("$baseUrl/v1/health")

    suspend fun pair(baseUrl: String, pairCode: String, deviceName: String): PairDto {
        val body = json.encodeToString(PairRequest(pairCode, deviceName))
        return post("$baseUrl/v1/pair", body, token = null, PairDto.serializer())
    }

    suspend fun changes(baseUrl: String, token: String, since: Long, limit: Int = 200): ChangesDto =
        getChanges("$baseUrl/v1/changes?since=$since&limit=$limit", token)

    suspend fun push(
        baseUrl: String,
        token: String,
        deviceId: String,
        changes: List<kapi.core.sync.OutboxChange>,
    ): PushDto {
        val body = json.encodeToString(PushRequest(deviceId, changes))
        return post("$baseUrl/v1/push", body, token, PushDto.serializer())
    }

    private suspend fun getChanges(url: String, token: String): ChangesDto {
        val req = Request.Builder().url(url).get()
            .header("Authorization", "Bearer $token")
            .build()
        return execute(req, ChangesDto.serializer())
    }

    // -----------------------------------------------------------------------

    private suspend fun get(url: String, token: String? = null): HealthDto {
        val req = Request.Builder().url(url).get()
            .also { token?.let { t -> it.header("Authorization", "Bearer $t") } }
            .build()
        return execute(req, HealthDto.serializer())
    }

    private suspend fun <T> post(
        url: String,
        body: String,
        token: String?,
        deserializer: DeserializationStrategy<T>,
    ): T {
        val req = Request.Builder().url(url)
            .post(body.toRequestBody(JSON_MEDIA))
            .also { token?.let { t -> it.header("Authorization", "Bearer $t") } }
            .build()
        return execute(req, deserializer)
    }

    /**
     * 用显式的 `DeserializationStrategy` 而不是 `inline fun <reified T>`。
     *
     * reified 版本里 `post` 调 `execute` 再调 `decodeFromString<T>`，T 要穿过两层
     * inline 传递，编译器在链上任何一环推断不出来时只会报
     * "Unresolved reference 'T'"，完全指不到真正的问题。传 serializer 没有这个问题，
     * 而且序列化器的选择提前到了编译期。
     *
     * 走 `enqueue` 而不是 `execute`：这个方法必须能从**任意**线程调用，包括主线程。
     * 早前它是阻塞的 `fun`，UI 里 `scope.launch { client.health(url) }` 一调就
     * 抛 `NetworkOnMainThreadException`——而 `rememberCoroutineScope()` 恰恰是
     * 绑定在主线程上的。改成 suspend + enqueue 之后，调用方忘记切线程也不会出事，
     * 这比在每个调用点包一层 `withContext(Dispatchers.IO)` 可靠得多：后者只要
     * 漏掉一处就复现，而且症状是一个完全看不出原因的网络异常。
     */
    private suspend fun <T> execute(
        request: Request,
        deserializer: DeserializationStrategy<T>,
    ): T = suspendCancellableCoroutine { cont ->
        val call = http.newCall(request)
        cont.invokeOnCancellation { call.cancel() }

        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isCancelled) return
                cont.resumeWithException(
                    SyncFailure.Unreachable(
                        "连不上服务器。检查 NAS 是否开机、端口映射是否生效、是否在同一个 WiFi。（${e.javaClass.simpleName}）"
                    )
                )
            }

            override fun onResponse(call: Call, response: Response) {
                if (cont.isCancelled) {
                    response.close()
                    return
                }
                response.use {
                    val text = it.body?.string().orEmpty()
                    val failure: SyncFailure? = when {
                        it.code == 401 -> SyncFailure.Unauthorized("令牌无效，请在设置里重新配对")
                        it.code == 403 -> SyncFailure.Pairing("配对码无效或已过期，在 NAS 日志里重新获取")
                        !it.isSuccessful -> SyncFailure.Protocol("服务端返回 ${it.code}: ${text.take(200)}")
                        else -> null
                    }
                    if (failure != null) {
                        cont.resumeWithException(failure)
                        return
                    }
                    try {
                        cont.resume(json.decodeFromString(deserializer, text))
                    } catch (e: Exception) {
                        cont.resumeWithException(SyncFailure.Protocol("响应解析失败: ${e.message}"))
                    }
                }
            }
        })
    }

    companion object {
        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            // 连接超时给得比读取短：连不上要立刻失败并提示，而不是让用户对着转圈等。
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}
