package kapi.ledger

import android.content.Context
import androidx.room.Room
import kapi.ledger.auto.AutoBookStore
import kapi.ledger.auto.AutoCapturePipeline
import kapi.ledger.data.KapiDatabase
import kapi.ledger.data.LedgerImportExport
import kapi.ledger.data.LedgerRepository
import kapi.ledger.data.Migrations
import kapi.ledger.llm.LlmClient
import kapi.ledger.llm.LlmStore
import kapi.ledger.sync.LocalEditWriter
import kapi.ledger.sync.SyncClient
import kapi.ledger.sync.SyncCoordinator
import kapi.ledger.sync.SyncEngine

/**
 * 应用级容器。手工 DI，不引 Hilt。
 *
 * 这个 App 的依赖图就五六个节点，Hilt 带来的注解处理和构建复杂度换不来任何
 * 东西。等真有多个 module 再说。
 */
class AppContainer(context: Context) {
    val db: KapiDatabase = Room
        .databaseBuilder(context.applicationContext, KapiDatabase::class.java, KapiDatabase.NAME)
        // 显式挂迁移，且**刻意不挂** fallbackToDestructiveMigration()。
        // 少了 addMigrations 的后果是启动即崩（这次就撞上了）；挂上破坏性回退的
        // 后果更糟——升级时静默清空用户的全部账本。宁可启动崩到被人看见，
        // 也不能悄悄删数据。
        //
        // 顺序不能错：**必须在 build() 之前**。`build()` 返回的是 RoomDatabase
        // 而不是 Builder，挂在后面会报 "Unresolved reference 'addMigrations'"，
        // 而那行注释挡在中间，错误信息里根本看不出是顺序问题。
        .addMigrations(*Migrations.ALL)
        // 主库不允许在主线程上查询。这条一旦漏掉，UI 卡顿会以"随机"的形式出现。
        .build()

    val syncClient = SyncClient()

    val syncEngine = SyncEngine(db, syncClient)

    val tokenStore = TokenStore(context)

    val editor = LocalEditWriter(db)

    val repository = LedgerRepository(db, editor)

    /** 导入/导出。写入一律走 [editor]，保证 pending 戳和同步路径完全一致。 */
    val transfer = LedgerImportExport(db, editor)

    val syncCoordinator = SyncCoordinator(syncEngine, db, syncClient, tokenStore)

    val llmClient = LlmClient()

    val llmStore = LlmStore(context)

    val autoBookStore = AutoBookStore(context)

    init {
        // 泄漏草稿清理（2026-10-04）：正常路径（确认/自动入账/✕ 放弃）都会
        // 删草稿，活过一天的只剩异常残留。留着它们有两个害处：待确认页堆
        // 垃圾，以及去重的存活判定被它们骗过——用户删了账，泄漏草稿还替
        // 那笔账喊"已记录"。后台线程跑，失败不影响启动。
        Thread {
            runCatching {
                val cutoff = System.currentTimeMillis() - 24 * 60 * 60_000L
                kotlinx.coroutines.runBlocking { db.autoDraftDao().deleteOlder(cutoff) }
            }
        }.start()
    }

    val autoCapture = AutoCapturePipeline(
        db = db,
        repository = repository,
        editor = editor,
        autoStore = autoBookStore,
        // 设备 id 要在**读的时候**取：TokenStore 里的 deviceId 是配对时才写上的，
        // 写成构造参数会把还没配对的空串固化下来，之后每一笔的戳都会挂在空设备上。
        deviceId = { tokenStore.deviceId },
    )
}

class TokenStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("kapi_sync", Context.MODE_PRIVATE)

    var baseUrl: String
        get() = prefs.getString(KEY_BASE_URL, "") ?: ""
        set(v) = prefs.edit().putString(KEY_BASE_URL, v).apply()

    var deviceId: String
        get() = prefs.getString(KEY_DEVICE_ID, "") ?: ""
        set(v) = prefs.edit().putString(KEY_DEVICE_ID, v).apply()

    var token: String
        get() = prefs.getString(KEY_TOKEN, "") ?: ""
        set(v) = prefs.edit().putString(KEY_TOKEN, v).apply()

    val isPaired: Boolean get() = token.isNotBlank() && baseUrl.isNotBlank()

    fun clear() {
        prefs.edit().remove(KEY_TOKEN).remove(KEY_DEVICE_ID).apply()
    }

    private companion object {
        const val KEY_BASE_URL = "base_url"
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_TOKEN = "token"
    }
}
