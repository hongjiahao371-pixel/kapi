package kapi.ledger.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kapi.ledger.data.KapiDatabase
import kapi.ledger.data.SyncStateEntity
import kapi.ledger.data.TxnEntity
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 真机 / 模拟器上的**真端到端**测试：App 打真 HTTP，真 kapi-sync 在另一头。
 *
 * 单元测试证不了这件事。协议是两端各写一遍的——Go 用 `json.RawMessage` 按字节透传，
 * Kotlin 用 `Map<String, String>` 存原始 JSON 文本。两边各自 100% 通过单测，
 * 照样可能因为一个字段名拼错而永远对不上话。只有真连一次才知道。
 *
 * 前置条件（在跑测试的机器上准备好）：
 * ```
 * kapi-sync.exe -addr 127.0.0.1:8787 -data <dir>
 * adb reverse tcp:8787 tcp:8787
 * gradle connectedDebugAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.baseUrl=http://127.0.0.1:8787 \
 *   -Pandroid.testInstrumentationRunnerArguments.pairCode=<服务端日志里的配对码>
 * ```
 *
 * 没传参数就**跳过**而不是失败：否则别人 clone 下来跑一遍测试看到一片红，
 * 分不清是自己环境没配好还是代码坏了。
 */
@RunWith(AndroidJUnit4::class)
class SyncE2ETest {

    @Serializable
    private data class PendingEntry(val value: String, val l: Long, val d: String)

    private val bridgeJson = Json { encodeDefaults = true; explicitNulls = false }

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    private val args get() = InstrumentationRegistry.getArguments()
    private val baseUrl: String? get() = args.getString("baseUrl")
    private val pairCode: String? get() = args.getString("pairCode")

    private val client = SyncClient()
    private var db: KapiDatabase? = null

    @Before
    fun setUp() {
        assumeTrue(
            "没传 baseUrl / pairCode，跳过真机联调（服务端没起或没做 adb reverse）",
            !baseUrl.isNullOrBlank() && !pairCode.isNullOrBlank(),
        )
        db = newDatabase("e2e-write.db")
    }

    @After
    fun tearDown() {
        db?.close()
        db = null
        ctx.deleteDatabase("e2e-write.db")
        ctx.deleteDatabase("e2e-read.db")
    }

    private fun db(): KapiDatabase = checkNotNull(db) { "setUp 没跑成" }
    private fun engine() = SyncEngine(db(), client)

    // -----------------------------------------------------------------------

    @Test
    fun healthReportsServerIsAlive() = runBlocking {
        val h = client.health(baseUrl!!)
        assertTrue("health 没返回 ok", h.ok)
    }

    /**
     * 主线：配对 → 本地记账 → 推上去 → **换一台空设备拉回来**。
     *
     * 最后那一步是全部意义所在。「账在 NAS 上、换台手机就能看到」是这个产品
     * 唯一的差异化；只在同一台设备上推了再拉，任何 bug 都测不出来。
     */
    @Test
    fun freshDevicePullsEverythingBack() = runBlocking {
        val paired = client.pair(baseUrl!!, pairCode!!, "e2e-phone-${System.nanoTime()}")
        assertTrue("配对没拿到 token", paired.token.isNotBlank())
        assertTrue("配对没拿到 deviceId", paired.deviceId.isNotBlank())

        val stamp = System.nanoTime()
        val written = listOf(
            localTxn("e2e-$stamp-a", 23_50L, "午餐", "盒马鲜生"),
            localTxn("e2e-$stamp-b", 1_000_00L, "话费", "中国移动"),
            // 大金额 + 奇数分：金额一旦在哪一步变成浮点，这笔最先露馅
            localTxn("e2e-$stamp-c", 9_999_999_99L + 1L, "个税", "国家税务总局"),
        )
        db().txnDao().upsertAll(written)
        markAllPending(written)

        val first = engine().sync(baseUrl!!, paired.token, paired.deviceId)
        assertTrue("第一次同步应该成功，实际=$first", first is SyncEngine.SyncResult.Success)
        first as SyncEngine.SyncResult.Success
        assertEquals("3 笔都该被服务端接受", 3, first.pushed)

        assertEquals(
            "推送成功后不该还有待推送的行 —— 否则同一笔会被无限重推",
            0,
            db().txnDao().pendingBatch().size,
        )

        val health = client.health(baseUrl!!)
        assertTrue("服务端应该有行，实际=${health.rows}", health.rows >= 3)

        // ---- 换一台「全新设备」：空库、自己配对、从 cursor 0 拉 ----
        //
        // 第二台设备必须**自己配对**拿自己的 token：服务端会用令牌认设备，
        // 请求体里的 device_id 和令牌对不上会被 403 直接拒掉。
        val freshPair = client.pair(baseUrl!!, pairCode!!, "e2e-second-phone-$stamp")
        val fresh = newDatabase("e2e-read.db")
        try {
            fresh.syncStateDao().put(
                SyncStateEntity(baseUrl = baseUrl!!, deviceId = freshPair.deviceId)
            )
            val freshEngine = SyncEngine(fresh, client)
            val pull = freshEngine.sync(baseUrl!!, freshPair.token, freshPair.deviceId)
            assertTrue("新设备拉取应该成功，实际=$pull", pull is SyncEngine.SyncResult.Success)

            for (expected in written) {
                val got = fresh.txnDao().byId(expected.id)
                assertNotNull(
                    "新设备没拉到 ${expected.id} —— 服务端有数据但客户端没落库",
                    got,
                )
                got!!
                assertEquals(
                    "${expected.id} 的金额在新设备上必须逐分不差",
                    expected.amountMinor,
                    got.amountMinor,
                )
                assertEquals("${expected.id} 的备注没对上", expected.note, got.note)
                assertEquals("${expected.id} 的商户没对上", expected.merchant, got.merchant)
                assertEquals("${expected.id} 的方向没对上", expected.direction, got.direction)
            }
        } finally {
            fresh.close()
        }
    }

    /**
     * 用户在本地改了还没推上去的东西，**不能**被下一次拉取盖回远端的旧值。
     *
     * 这是"离线记账"这条产品设定最容易死的地方：回家一连 WiFi 同步，
     * 路上改的备注凭空变回服务器上的老值，用户会以为 App 吃掉了他的输入。
     */
    @Test
    fun uncommittedLocalEditSurvivesAPull() = runBlocking {
        val stamp = System.nanoTime()
        val paired = client.pair(baseUrl!!, pairCode!!, "e2e-edit-$stamp")
        val id = "e2e-edit-$stamp"

        val original = localTxn(id, 88_88L, "原始备注", "某商户")
        db().txnDao().upsertAll(listOf(original))
        markAllPending(listOf(original))
        engine().sync(baseUrl!!, paired.token, paired.deviceId)

        // 用户又改了备注，还没推
        val current = db().txnDao().byId(id)!!
        val edited = "本地改过的备注"
        val pending = bridgeJson.encodeToString(
            mapOf("note" to PendingEntry(jsonStr(edited), 9_999_999L, paired.deviceId))
        )
        db().txnDao().update(current.copy(note = edited, pendingJson = pending, hasPending = true))

        val res = engine().sync(baseUrl!!, paired.token, paired.deviceId)
        assertTrue("同步应该成功，实际=$res", res is SyncEngine.SyncResult.Success)
        assertEquals(
            "未推送的本地编辑被远端快照覆盖了 —— 路上记的东西会凭空消失",
            edited,
            db().txnDao().byId(id)!!.note,
        )
    }

    /**
     * 方向和归属在 A 改、B 拉之后必须跟着变。
     *
     * 这两个字段早前在 `SyncedRow.toEntity` 里被整个漏掉了：只回写金额/时间/备注/商户。
     * 后果是 A 把一笔支出改成收入，B 拉下来还是支出——而"方向独立于正负号"正是这个
     * 存储层最硬的一条设计，映射层漏了它，界面上就会把收入显示成支出。
     */
    @Test
    fun directionChangeOnOneDeviceReachesTheOther() = runBlocking {
        val stamp = System.nanoTime()
        val paired = client.pair(baseUrl!!, pairCode!!, "e2e-dir-$stamp")
        val id = "e2e-dir-$stamp"

        // 设备 A 记一笔支出并推上去
        val a = localTxn(id, 66_00L, "先记成支出", "商户")
        db().txnDao().upsertAll(listOf(a))
        markAllPending(listOf(a))
        engine().sync(baseUrl!!, paired.token, paired.deviceId)

        // 设备 B 空库拉一次，拿到 EXPENSE。
        // B 必须自己配对：服务端按令牌认设备，body 里的 device_id 对不上直接 403。
        val pairB = client.pair(baseUrl!!, pairCode!!, "e2e-dir-b-$stamp")
        val b = newDatabase("e2e-read.db")
        try {
            b.syncStateDao().put(SyncStateEntity(baseUrl = baseUrl!!, deviceId = pairB.deviceId))
            val engineB = SyncEngine(b, client)
            engineB.sync(baseUrl!!, pairB.token, pairB.deviceId)
            assertEquals("B 第一次拉到的方向不对", "EXPENSE", b.txnDao().byId(id)?.direction)

            // B 把它改成收入并推回去
            val rowB = b.txnDao().byId(id)!!
            val pending = bridgeJson.encodeToString(
                mapOf("direction" to PendingEntry(jsonStr("INCOME"), 5_000_000L, pairB.deviceId))
            )
            b.txnDao().update(rowB.copy(direction = "INCOME", pendingJson = pending, hasPending = true))
            val pushB = engineB.sync(baseUrl!!, pairB.token, pairB.deviceId)
            assertTrue("B 推送应该成功，实际=$pushB", pushB is SyncEngine.SyncResult.Success)

            // A 拉一次，方向应该变成 INCOME
            engine().sync(baseUrl!!, paired.token, paired.deviceId)
            assertEquals(
                "A 没收到 B 改的方向 —— 支出会一直显示成支出",
                "INCOME",
                db().txnDao().byId(id)?.direction,
            )
        } finally {
            b.close()
        }
    }

    // -----------------------------------------------------------------------

    private fun newDatabase(name: String): KapiDatabase =
        Room.databaseBuilder(ctx, KapiDatabase::class.java, name)
            .allowMainThreadQueries()
            .build()

    private fun jsonStr(s: String) = bridgeJson.encodeToString(JsonPrimitive(s))

    /**
     * 把一行标成「所有业务字段都待推送」。
     *
     * 走的是和 [SyncEngine] 推送前**完全相同**的 JSON 格式（`{"note":{"value":…,"l":…,"d":…}}`），
     * 而不是随手搓一个字符串。手搓的格式一旦和读取端对不上，测试会绿、线上会丢账，
     * 而且这种错在单测里永远发现不了。
     */
    private fun markAllPending(rows: List<TxnEntity>) = runBlocking {
        val device = "e2e-local"
        var lamport = 100L
        for (r in rows) {
            val m = LinkedHashMap<String, PendingEntry>()
            fun put(field: String, value: String) {
                m[field] = PendingEntry(value, lamport++, device)
            }
            put("amount_minor", r.amountMinor.toString())
            put("direction", jsonStr(r.direction))
            put("account_id", jsonStr(r.accountId))
            put("occurred_at", r.occurredAt.toString())
            r.note?.let { put("note", jsonStr(it)) }
            r.merchant?.let { put("merchant", jsonStr(it)) }
            r.categoryId?.let { put("category_id", jsonStr(it)) }
            r.toAccountId?.let { put("to_account_id", jsonStr(it)) }

            db().txnDao().update(
                r.copy(
                    pendingJson = bridgeJson.encodeToString(m),
                    hasPending = true,
                    lamport = lamport,
                )
            )
        }
    }

    private fun localTxn(id: String, amountMinor: Long, note: String, merchant: String) = TxnEntity(
        id = id,
        ledgerId = "L1",
        amountMinor = amountMinor,
        direction = "EXPENSE",
        accountId = "A1",
        occurredAt = 1_757_000_000_000L,
        note = note,
        merchant = merchant,
    )
}
