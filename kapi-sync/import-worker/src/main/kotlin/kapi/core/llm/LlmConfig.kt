package kapi.core.llm

import kotlinx.serialization.Serializable
import java.net.URI

/**
 * 用户自带的大模型配置（BYOK）。
 *
 * 支持任意 OpenAI 兼容端点：官方 API、DeepSeek、硅基流动、Ollama、LM Studio、
 * 群晖本地模型…… 用户填 baseUrl + apiKey + model 三样就完事。
 *
 * [isLocalEndpoint] 是这个产品最重要的一行：它决定**用户的数据会不会离开这台
 * 设备**。填 localhost 的话，截图和账单都不出户，"数据完全归我"才是闭环的；
 * 填云端的话，账单依然只在自己 NAS 上，但截图会发出去。
 *
 * 这两种情况的隐私强度差一个量级，所以必须在界面上明确区分，不能让用户以为
 * 填了云端 key 就还是"全本地"。
 */
@Serializable
data class LlmConfig(
    val baseUrl: String = "",
    val apiKey: String = "",
    val model: String = "",
    /** 保留旧配置序列化兼容；能力由实际识图测试判断，不再用此字段禁用。 */
    val supportsVision: Boolean = true,
    val timeoutMs: Int = 20_000,
    /** 用户主动选择的账单及持仓截图跳过端侧 OCR；失败也不本地降级。 */
    val cloudOnlyScreenshots: Boolean = false,
) {
    val isConfigured: Boolean
        get() = baseUrl.isNotBlank() && model.isNotBlank()

    /**
     * 端点是否指向本机 / 局域网。
     *
     * 判定放宽到局域网是有意的：如果用户在自己 NAS 上跑推理，「不出户」依然
     * 成立，而这是这个产品最该鼓励的用法。
     */
    fun isLocalEndpoint(): Boolean = when {
        baseUrl.isBlank() -> false
        else -> {
            val host = runCatching { URI(baseUrl).host }.getOrNull()?.lowercase()
            when {
                host == null -> false
                host == "localhost" -> true
                host == "::1" || host == "[::1]" -> true
                host.endsWith(".local") || host.endsWith(".localdomain") -> true
                host.startsWith("127.") -> true
                host.startsWith("10.") -> true
                host.startsWith("192.168.") -> true
                // 172.16 – 172.31
                host.split('.').take(2).let { it.size == 2 && it[0] == "172" &&
                    (it[1].toIntOrNull() ?: 0) in 16..31 } -> true
                else -> false
            }
        }
    }

    /** 旧方法名保留兼容；按接入地址解析实际协议与完整端点。 */
    fun chatCompletionsUrl(): String {
        return VisionApi.endpoint(this).url
    }
}

/** 截图识别的结果。金额恒为正，方向单独表达，见 [Money]。 */
@Serializable
data class TransactionDraft(
    /** 以分为单位的金额。 */
    val amountMinor: Long = 0,
    val direction: Direction = Direction.UNKNOWN,
    /** 发生时间，epoch millis。模型没给就用截图时间。 */
    val occurredAt: Long = 0,
    val merchant: String? = null,
    /** 模型建议的分类名，最终要映射到本地分类表。 */
    val category: String? = null,
    val note: String? = null,
    /** 模型自评置信度 0..1。低于阈值时 UI 应要求用户确认。 */
    val confidence: Double = 0.0,
) {
    val isUsable: Boolean get() = amountMinor > 0
}

/** 识别来源。UI 要能告诉用户这一笔是模型认的还是本地规则认的。 */
enum class RecognitionSource {
    /** 大模型识别。 */
    LLM,

    /** 本地降级：不依赖任何网络，金额靠规则解析。 */
    LOCAL_FALLBACK,

    /** 完全识别不出来，需要用户手填。 */
    MANUAL,
}
