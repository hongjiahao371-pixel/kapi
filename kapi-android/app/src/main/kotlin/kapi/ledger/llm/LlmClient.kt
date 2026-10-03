package kapi.ledger.llm

import android.util.Log
import kapi.core.llm.LlmConfig
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
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

/**
 * 调用任意 OpenAI 兼容端点。
 *
 * 和 [kapi.ledger.sync.SyncClient] 同一套纪律：
 *  - **全部方法是 suspend**，内部 `enqueue`。调用方忘记切线程也不会炸。
 *    踩过 `NetworkOnMainThreadException` —— `rememberCoroutineScope()` 绑的是主线程，
 *    而 `scope.launch { client.xxx() }` 一调一个准。
 *  - **每层错误都同时给 [LlmFailure.detail] 和 [Exception]`**。只放 data class 字段的话
 *    `Throwable.message` 是 null，界面上就只剩"识别失败"四个字。
 *  - **绝不把 API Key 写进日志或异常消息。**
 */
sealed class LlmFailure : Exception() {
    abstract val detail: String

    /** 地址不对 / 网络不通。 */
    data class Unreachable(override val detail: String) : LlmFailure()

    /** Key 错、余额不足、被限流。区别对待，因为用户该做的事完全不同。 */
    data class Auth(override val detail: String) : LlmFailure()

    /** 连上了但模型不认识这个请求——多半是 baseUrl 少了 /v1，或者模型不支持图片。 */
    data class Unsupported(override val detail: String) : LlmFailure()

    /** 端点返回了我们读不懂的东西。 */
    data class Protocol(override val detail: String) : LlmFailure()

    /** 超时。 */
    data class Timeout(override val detail: String) : LlmFailure()
}

class LlmClient(
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true },
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        // 大模型看图动辄十几秒，读取超时必须给得比同步宽松得多，否则正常识别会被
        // 自己的超时打断，而用户看到的只是"失败"。
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(90, TimeUnit.SECONDS)
        .build(),
) {
    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

    /**
     * 让模型看一眼图，吐出记账草稿。
     *
     * 返回**模型回复的原始文本**，解析交给 `TransactionExtractor`——它才是那个
     * "永不抛异常"的地方。这里不解析，解析失败就不该连累整个请求。
     */
    suspend fun recognizeScreenshot(
        config: LlmConfig,
        imageBase64: String,
        mimeType: String,
        categoryHints: List<String>,
    ): String {
        if (!config.isConfigured) {
            throw LlmFailure.Unreachable("还没填大模型配置，去设置里填 baseUrl 和模型名")
        }
        if (!config.supportsVision) {
            throw LlmFailure.Unsupported("这个模型标了不支持图片，截图识别用不了")
        }
        val payload = buildJsonObject {
            put("model", config.model)
            put("temperature", 0)
            // 推理模型（MiniMax M3 等）的思考会消耗输出预算：不给 max_tokens 时
            // 默认值太小，<think> 写满就截断，正式 JSON 一个字都没出。
            // 4000 够「思考 + JSON」；再说提取器对截断回复也只是降级不崩溃。
            put("max_tokens", 4000)
            put(
                "messages",
                buildJsonArray {
                    add(buildJsonObject {
                        put("role", "system")
                        put("content", SYSTEM_PROMPT)
                    })
                    add(buildJsonObject {
                        put("role", "user")
                        // user 的 content 是「文本 + 图片」的**数组**，不是字符串。
                        // 这一层写错的话端点会回 400，而用户只看到"识别失败"。
                        put(
                            "content",
                            buildJsonArray {
                                add(buildJsonObject {
                                    put("type", "text")
                                    put("text", userPrompt(categoryHints))
                                })
                                add(buildJsonObject {
                                    put("type", "image_url")
                                    put("image_url", buildJsonObject {
                                        put("url", "data:$mimeType;base64,$imageBase64")
                                    })
                                })
                            },
                        )
                    })
                },
            )
        }
        return post(config, payload.toString())
    }

    /**
     * 连通性探测。发一个最小的纯文本请求——不传图，这样不支持视觉的模型也能测。
     *
     * 用它区分"Key 错了"和"端点地址错了"：401/403 是 Key，其它 4xx 更可能是
     * baseUrl 少写了 /v1。这两种错误用户要做的完全不同，不能都报"连接失败"。
     */
    suspend fun ping(config: LlmConfig): String {
        if (!config.isConfigured) {
            throw LlmFailure.Unreachable("baseUrl 和模型名都要填")
        }
        val body = buildJsonObject {
            put("model", config.model)
            put("max_tokens", 8)
            put(
                "messages",
                buildJsonArray {
                    add(buildJsonObject {
                        put("role", "user")
                        put("content", "ping")
                    })
                },
            )
        }
        return post(config, body.toString())
    }

    // -----------------------------------------------------------------------

    private fun userPrompt(hints: List<String>): String = buildString {
        append("这是一张账单或支付截图。请提取出金额、收支方向、商户、分类和备注。\n")
        if (hints.isNotEmpty()) {
            append("如果判断分类，请优先从这些已有分类里选最接近的一个：")
            append(hints.joinToString("、"))
            append("。\n")
        }
        append("只输出 JSON，不要任何解释文字。")
    }

    private suspend fun post(config: LlmConfig, body: String): String {
        val request = Request.Builder()
            .url(config.chatCompletionsUrl())
            .post(body.toRequestBody(JSON_MEDIA))
            .header("Content-Type", "application/json")
            .apply {
                // 部分端点（本地 Ollama / LM Studio）不校验 Authorization，非空即可。
                if (config.apiKey.isNotBlank()) {
                    header("Authorization", "Bearer ${config.apiKey}")
                }
            }
            .build()

        return suspendCancellableCoroutine { cont ->
            val call = http.newCall(request)
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isCancelled) return
                    val failure = if (e is java.net.SocketTimeoutException) {
                        LlmFailure.Timeout("模型响应超时。可以调大超时，或换一个更快的模型")
                    } else {
                        LlmFailure.Unreachable(
                            "连不上 ${safeHost(config.baseUrl)}。检查地址、端口，" +
                                "以及手机和它是否在同一个网络。（${e.javaClass.simpleName}）"
                        )
                    }
                    // 消息里绝不带 key：异常经常被原样打进日志。
                    Log.e(TAG, "onFailure host=${safeHost(config.baseUrl)}: ${e.javaClass.name}", e)
                    cont.resumeWithException(failure)
                }

                override fun onResponse(call: Call, response: Response) {
                    if (cont.isCancelled) {
                        response.close()
                        return
                    }
                    response.use { r ->
                        val text = r.body?.string().orEmpty()
                        when {
                            r.code == 401 || r.code == 403 -> cont.resumeWithException(
                                LlmFailure.Auth(
                                    "Key 被拒了（HTTP ${r.code}）。检查 API Key 有没有复制全、" +
                                        "有没有多余空格，以及是不是欠费了"
                                )
                            )

                            r.code == 404 -> cont.resumeWithException(
                                LlmFailure.Unsupported(
                                    "端点没这个接口。baseUrl 一般要写到 /v1 这一层，" +
                                        "比如 https://api.deepseek.com/v1"
                                )
                            )

                            r.code == 429 -> cont.resumeWithException(
                                LlmFailure.Protocol("被限流了，等一会儿再试")
                            )

                            r.code == 400 -> cont.resumeWithException(
                                LlmFailure.Unsupported(
                                    "端点拒绝了这个请求（HTTP 400）。多半是模型名填错，" +
                                        "或者这个模型不支持图片。" +
                                        "服务端原话：${scrub(text, config.apiKey)}"
                                )
                            )

                            !r.isSuccessful -> cont.resumeWithException(
                                LlmFailure.Protocol("服务端返回 ${r.code}：${scrub(text, config.apiKey).take(200)}")
                            )

                            else -> {
                                val content = readContent(text)
                                if (content == null) {
                                    cont.resumeWithException(
                                        LlmFailure.Protocol("回复里没有可读内容：${scrub(text, config.apiKey).take(200)}")
                                    )
                                } else {
                                    cont.resume(content)
                                }
                            }
                        }
                    }
                }
            })
        }
    }

    /** OpenAI 兼容响应里真正要的是 `choices[0].message.content`。 */
    private fun readContent(raw: String): String? = runCatching {
        val root = json.parseToJsonElement(raw) as? JsonObject ?: return null
        val choices = root["choices"] as? JsonArray ?: return null
        val first = choices.firstOrNull() as? JsonObject ?: return null
        val message = first["message"] as? JsonObject ?: return null
        (message["content"] as? JsonPrimitive)?.contentOrNull
    }.getOrNull()

    /** 只留主机名。异常和日志里出现完整 URL 就可能带出内网结构，key 更不能出现。 */
    private fun safeHost(baseUrl: String): String = runCatching {
        java.net.URI(baseUrl).host
    }.getOrNull() ?: "该地址"

    /** 万一服务端把 key 回显在错误里，抹掉再往上抛。 */
    private fun scrub(text: String, apiKey: String): String =
        if (apiKey.isBlank()) text else text.replace(apiKey, "***")

    private companion object {
        const val TAG = "LlmClient"
    }
}

private val SYSTEM_PROMPT = """
你是一个记账助手。用户会给你一张账单、支付记录或交易流水的截图，你要把它提取成结构化数据。

严格要求：
1. 只输出一个 JSON 对象，不要 markdown 围栏，不要任何解释文字。
2. 金额字段 amount 用「元」为单位的数字，不要带货币符号，不要带千分位逗号，不要带正负号。
3. direction 只能是 EXPENSE 或 INCOME 两个值，用英文大写。
4. 拿不准的字段就省略，不要编。宁可少给字段，也不要给错的。
5. confidence 是 0 到 1 之间的小数，表示你对这一笔的把握程度。金额看不清就给 0.2 以下。

输出格式：
{"amount": 23.50, "direction": "EXPENSE", "merchant": "星巴克", "category": "餐饮", "note": "拿铁", "occurred_at": "2026-09-29T15:30:00", "confidence": 0.9}
""".trimIndent()
