package kapi.core.llm

import java.net.URI
import java.net.URLEncoder
import kotlinx.serialization.json.*

/** Protocol follows the endpoint, never the model name (brokers often expose other vendors' models). */
object VisionApi {
    enum class Protocol { CHAT, RESPONSES, CLAUDE, GEMINI }
    data class Endpoint(val url: String, val protocol: Protocol, val azure: Boolean = false)
    data class WireRequest(val endpoint: Endpoint, val headers: Map<String, String>, val body: JsonObject)

    fun endpoint(config: LlmConfig): Endpoint {
        val uri = URI(config.baseUrl.trim())
        require(uri.scheme?.lowercase() in setOf("https", "http") && !uri.host.isNullOrBlank() && uri.rawUserInfo == null && uri.rawFragment == null) {
            "接口地址应为完整的 http 或 https 地址，不要包含用户名或 # 片段"
        }
        val host = uri.host.lowercase()
        val path = uri.rawPath.orEmpty().trimEnd('/')
        val protocol = when {
            path.endsWith("/chat/completions") -> Protocol.CHAT
            path.endsWith("/responses") -> Protocol.RESPONSES
            path.endsWith("/messages") -> Protocol.CLAUDE
            path.endsWith(":generateContent") -> Protocol.GEMINI
            host == "api.anthropic.com" -> Protocol.CLAUDE
            host == "generativelanguage.googleapis.com" && !path.contains("/openai") -> Protocol.GEMINI
            else -> Protocol.CHAT
        }
        val resolved = when (protocol) {
            Protocol.CHAT -> if (path.endsWith("/chat/completions")) path else "${path.ifBlank { "/v1" }}/chat/completions"
            Protocol.RESPONSES -> path
            Protocol.CLAUDE -> if (path.endsWith("/messages")) path else "${path.ifBlank { "/v1" }}/messages"
            Protocol.GEMINI -> {
                val model = config.model.trim().removePrefix("models/")
                require(model.isNotBlank()) { "请填写模型名" }
                val base = if (path.endsWith(":generateContent")) {
                    require(path.contains("/models/")) { "Gemini 地址应包含 /models/模型名:generateContent" }
                    path.substringBeforeLast("/models/")
                } else path.ifBlank { "/v1beta" }
                "$base/models/${URLEncoder.encode(model, "UTF-8").replace("+", "%20")}:generateContent"
            }
        }
        // Preserve query exactly, e.g. Azure api-version. URI(component constructor) double-escapes raw paths.
        val url = "${uri.scheme}://${uri.rawAuthority}$resolved" + (uri.rawQuery?.let { "?$it" } ?: "")
        val azure = host.endsWith(".openai.azure.com") || host.endsWith(".services.ai.azure.com")
        return Endpoint(url, protocol, azure)
    }

    fun request(config: LlmConfig, system: String, prompt: String, imageBase64: String? = null,
                mimeType: String = "image/jpeg", maxTokens: Int = 4000, completionTokens: Boolean = false): WireRequest {
        val endpoint = endpoint(config)
        val key = config.apiKey.trim()
        val headers = buildMap {
            when (endpoint.protocol) {
                Protocol.CLAUDE -> { put("anthropic-version", "2023-06-01"); if (key.isNotBlank()) put("x-api-key", key) }
                Protocol.GEMINI -> if (key.isNotBlank()) put("x-goog-api-key", key)
                else -> if (key.isNotBlank()) put(if (endpoint.azure) "api-key" else "Authorization", if (endpoint.azure) key else "Bearer $key")
            }
        }
        val body = buildJsonObject {
            when (endpoint.protocol) {
                Protocol.CHAT -> {
                    put("model", config.model.trim())
                    put(if (completionTokens) "max_completion_tokens" else "max_tokens", maxTokens)
                    putJsonArray("messages") {
                        if (system.isNotBlank()) add(buildJsonObject { put("role", "system"); put("content", system) })
                        add(buildJsonObject { put("role", "user"); putJsonArray("content") {
                            add(buildJsonObject { put("type", "text"); put("text", prompt) })
                            if (imageBase64 != null) add(buildJsonObject { put("type", "image_url"); putJsonObject("image_url") { put("url", "data:$mimeType;base64,$imageBase64") } })
                        } })
                    }
                }
                Protocol.RESPONSES -> {
                    put("model", config.model.trim()); put("max_output_tokens", maxTokens); put("store", false)
                    if (system.isNotBlank()) put("instructions", system)
                    putJsonArray("input") { add(buildJsonObject { put("role", "user"); putJsonArray("content") {
                        add(buildJsonObject { put("type", "input_text"); put("text", prompt) })
                        if (imageBase64 != null) add(buildJsonObject { put("type", "input_image"); put("image_url", "data:$mimeType;base64,$imageBase64") })
                    } }) }
                }
                Protocol.CLAUDE -> {
                    put("model", config.model.trim()); put("max_tokens", maxTokens)
                    if (system.isNotBlank()) put("system", system)
                    putJsonArray("messages") { add(buildJsonObject { put("role", "user"); putJsonArray("content") {
                        if (imageBase64 != null) add(buildJsonObject { put("type", "image"); putJsonObject("source") {
                            put("type", "base64"); put("media_type", mimeType); put("data", imageBase64)
                        } })
                        add(buildJsonObject { put("type", "text"); put("text", prompt) })
                    } }) }
                }
                Protocol.GEMINI -> {
                    if (system.isNotBlank()) putJsonObject("systemInstruction") { putJsonArray("parts") { add(buildJsonObject { put("text", system) }) } }
                    putJsonObject("generationConfig") { put("maxOutputTokens", maxTokens) }
                    putJsonArray("contents") { add(buildJsonObject { put("role", "user"); putJsonArray("parts") {
                        add(buildJsonObject { put("text", prompt) })
                        if (imageBase64 != null) add(buildJsonObject { putJsonObject("inline_data") { put("mime_type", mimeType); put("data", imageBase64) } })
                    } }) }
                }
            }
        }
        return WireRequest(endpoint, headers, body)
    }

    /** Only read final text; thinking, refusal and tool blocks are not bookkeeping output. */
    fun readContent(raw: String, protocol: Protocol): String? = runCatching {
        val root = Json.parseToJsonElement(raw) as? JsonObject ?: return null
        fun textParts(parts: JsonArray?, type: String? = null): String = parts.orEmpty().mapNotNull {
            val block = it as? JsonObject ?: return@mapNotNull null
            if (block["thought"] == JsonPrimitive(true) || (type != null && block["type"]?.jsonPrimitive?.contentOrNull != type)) null
            else (block["text"] as? JsonPrimitive)?.contentOrNull
        }.joinToString("\n")
        val content = when (protocol) {
            Protocol.CHAT -> {
                val message = ((root["choices"] as? JsonArray)?.firstOrNull() as? JsonObject)?.get("message") as? JsonObject
                when (val c = message?.get("content")) {
                    is JsonPrimitive -> if (c.isString) c.content else null
                    is JsonArray -> textParts(c, "text")
                    else -> null
                }
            }
            Protocol.CLAUDE -> textParts(root["content"] as? JsonArray, "text")
            Protocol.RESPONSES -> (root["output"] as? JsonArray).orEmpty().mapNotNull {
                val block = it as? JsonObject
                if (block?.get("type") == JsonPrimitive("message")) textParts(block["content"] as? JsonArray, "output_text") else null
            }.joinToString("\n")
            Protocol.GEMINI -> {
                val candidate = (root["candidates"] as? JsonArray)?.firstOrNull() as? JsonObject
                textParts((candidate?.get("content") as? JsonObject)?.get("parts") as? JsonArray)
            }
        }
        content?.takeIf { it.isNotBlank() }
    }.getOrNull()

    /** One bounded retry, only after an explicit rejection of max_tokens; never drop the image. */
    fun needsCompletionTokens(status: Int, body: String, protocol: Protocol): Boolean {
        if (status != 400 || protocol != Protocol.CHAT) return false
        val error = runCatching { (Json.parseToJsonElement(body) as? JsonObject)?.get("error") as? JsonObject }.getOrNull() ?: return false
        val message = (error["message"] as? JsonPrimitive)?.contentOrNull.orEmpty().lowercase()
        val param = (error["param"] as? JsonPrimitive)?.contentOrNull
        return (param == "max_tokens" || message.contains("max_tokens")) &&
            (message.contains("not supported") || message.contains("unsupported")) && message.contains("max_completion_tokens")
    }
}
