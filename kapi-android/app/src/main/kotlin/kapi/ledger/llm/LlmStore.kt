package kapi.ledger.llm

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kapi.core.llm.LlmConfig

/**
 * BYOK 配置的本地存储。
 *
 * **API Key 优先存进 EncryptedSharedPreferences。** 这不是"更安全一点"，而是
 * 这个 App 的立身之本：整个卖点是"数据只在我自己的机器上"。如果用户填了云端
 * key，而明文躺在 SharedPreferences 里，root 机 / 备份提取 / 其它 App 在
 * 拿到调试权限后都能读走，那么"数据归我"这句话在设置页就是在骗人。
 *
 * 降级路径：部分设备（部分 ROM 的 Keystore 实现有 bug、已 root 后的自定义
 * keystore）拿不到 master key，`EncryptedSharedPreferences.create()` 会直接抛。
 * 这时候退回明文，但**必须留日志**——静默降级等于悄悄把密钥降级成明文而用户
 * 一点都不知道。
 */
class LlmStore(context: Context) {

    private val app = context.applicationContext

    private val prefs: SharedPreferences by lazy { openPrefs(app) }

    @Volatile
    private var encrypted: Boolean = true

    fun load(): LlmConfig = LlmConfig(
        baseUrl = prefs.getString(KEY_BASE_URL, "") ?: "",
        apiKey = prefs.getString(KEY_API_KEY, "") ?: "",
        model = prefs.getString(KEY_MODEL, "") ?: "",
        supportsVision = prefs.getBoolean(KEY_VISION, true),
        timeoutMs = prefs.getInt(KEY_TIMEOUT, 20_000),
    )

    fun save(config: LlmConfig) {
        prefs.edit()
            .putString(KEY_BASE_URL, config.baseUrl.trim())
            .putString(KEY_API_KEY, config.apiKey.trim())
            .putString(KEY_MODEL, config.model.trim())
            .putBoolean(KEY_VISION, config.supportsVision)
            .putInt(KEY_TIMEOUT, config.timeoutMs)
            .apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    /** 密钥是否真的加密存了。界面上不用暴露，但日志和"关于"页要能说清。 */
    fun isEncryptedAtRest(): Boolean = encrypted

    private fun openPrefs(ctx: Context): SharedPreferences = try {
        val master = MasterKey.Builder(ctx)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            ctx,
            FILE_NAME,
            master,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    } catch (e: Exception) {
        encrypted = false
        Log.w(
            TAG,
            "拿不到 Keystore master key，API Key 将以明文保存。" +
                "设备 Keystore 不可用（${e.javaClass.name}）",
            e,
        )
        ctx.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
    }

    private companion object {
        const val TAG = "LlmStore"
        const val FILE_NAME = "kapi_llm"
        const val KEY_BASE_URL = "llm_base_url"
        const val KEY_API_KEY = "llm_api_key"
        const val KEY_MODEL = "llm_model"
        const val KEY_VISION = "llm_supports_vision"
        const val KEY_TIMEOUT = "llm_timeout_ms"
    }
}
