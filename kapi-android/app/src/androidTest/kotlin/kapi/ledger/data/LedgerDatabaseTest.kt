package kapi.ledger.data

import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 存储层不变量测试。
 *
 * 必须在**还没有真实用户数据的时候**就把这套搭起来。等 App 被人用几个月、
 * 每一版 schema 都躺在别人手机上的时候再补迁移测试，就晚了——那时候任何一次
 * 迁移写错都会直接吃掉一批用户的账本数据，而且不可恢复。
 */
@RunWith(AndroidJUnit4::class)
class LedgerDatabaseTest {

    private lateinit var db: KapiDatabase
    private val dbFile: File
        get() = ApplicationProvider.getApplicationContext<android.content.Context>()
            .getDatabasePath(KapiDatabase.NAME)

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            KapiDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    // -----------------------------------------------------------------------

    /**
     * 金额列必须是 INTEGER，而且存进去再读出来还是整数。
     *
     * 这一条是整个存储层最硬的约束：`REAL` 存金额意味着 SQLite 层面就开始有
     * 浮点误差，而 Room 不会拦你——类型系统在这里是失效的，只能靠这个测试守着。
     * 0.1 + 0.2 这类事实在账本里表现为"这个软件算出来的数不能信"。
     */
    @Test
    fun amountColumnIsIntegerNotReal() = runBlocking {
        db.txnDao().upsertAll(listOf(sampleTxn(amountMinor = 2350L)))

        val raw: SupportSQLiteDatabase = db.openHelper.readableDatabase
        raw.query("PRAGMA table_info(txn)").use { c ->
            val typeIdx = c.getColumnIndexOrThrow("type")
            val nameIdx = c.getColumnIndexOrThrow("name")
            var found = false
            while (c.moveToNext()) {
                if (c.getString(nameIdx) == "amountMinor") {
                    found = true
                    val type = c.getString(typeIdx).uppercase()
                    assertTrue(
                        "amount_minor 的列类型是 $type，必须是 INTEGER —— 账本的金额不允许浮点",
                        type.contains("INT"),
                    )
                }
            }
            assertTrue("没找到 amountMinor 列", found)
        }

        // 再从库里直接查一次，确认存进去的就是整数 2350 而不是 2350.0
        raw.query("SELECT amountMinor FROM txn WHERE id = 't1'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(2350L, c.getLong(0))
        }
    }

    @Test
    fun roundTripsThroughDiskWithMoneyIntact() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val file = File(ctx.cacheDir, "roundtrip.db")
        if (file.exists()) file.delete()

        val disk = Room.databaseBuilder(ctx, KapiDatabase::class.java, file.absolutePath)
            .allowMainThreadQueries().build()

        // 选一些"难搞"的值：超大金额、奇数分、零
        val rows = listOf(
            sampleTxn(id = "a", amountMinor = 9_999_999_999L),
            sampleTxn(id = "b", amountMinor = 1L),
            sampleTxn(id = "c", amountMinor = 0L),
        )
        disk.txnDao().upsertAll(rows)
        disk.close()

        val reopened = Room.databaseBuilder(ctx, KapiDatabase::class.java, file.absolutePath)
            .allowMainThreadQueries().build()
        try {
            for (expected in rows) {
                val got = reopened.txnDao().byId(expected.id)
                assertNotNull("${expected.id} 重开后消失了", got)
                assertEquals("金额在重开后必须逐分不差", expected.amountMinor, got!!.amountMinor)
            }
        } finally {
            reopened.close()
            file.delete()
        }
    }

    @Test
    fun pendingRowsAreRetrievableAndCountable() = runBlocking {
        db.txnDao().upsertAll(
            listOf(
                sampleTxn(id = "p1", amountMinor = 100L, hasPending = true),
                sampleTxn(id = "p2", amountMinor = 200L, hasPending = true),
                sampleTxn(id = "p3", amountMinor = 300L, hasPending = false),
            )
        )

        val pending = db.txnDao().pendingBatch()
        assertEquals("只有 2 行待推送", 2, pending.size)
        assertTrue(pending.none { it.id == "p3" })
    }

    @Test
    fun deletedRowsAreExcludedFromRangeQueries() = runBlocking {
        val now = 1_757_000_000_000L
        db.txnDao().upsertAll(
            listOf(
                sampleTxn(id = "live", amountMinor = 100L, occurredAt = now),
                sampleTxn(id = "gone", amountMinor = 100L, occurredAt = now, deleted = true),
            )
        )
        val visible = db.txnDao().observeRangeRaw(now - 1000, now + 1000)
        assertEquals(1, visible.size)
        assertEquals("live", visible[0].id)
    }

    // -----------------------------------------------------------------------

    private fun sampleTxn(
        id: String = "t1",
        amountMinor: Long = 2350L,
        occurredAt: Long = 1_757_000_000_000L,
        hasPending: Boolean = false,
        deleted: Boolean = false,
    ) = TxnEntity(
        id = id,
        ledgerId = "L1",
        amountMinor = amountMinor,
        direction = "EXPENSE",
        accountId = "A1",
        occurredAt = occurredAt,
        hasPending = hasPending,
        deleted = deleted,
    )
}

/**
 * 测试辅助：把 Flow 的第一个值取出来（不引入 turbine 依赖）。
 *
 * `first` 是 `Flow<T>` 的**扩展**函数，不是接收 Flow 的顶层函数。
 * 写成 `first(flow)` 编译不过；而且 Kotlin 不会把 `first(flow)` 报成
 * "unknown function"，会报一段和真实原因无关的 receiver mismatch。
 */
private suspend fun TxnDao.observeRangeRaw(from: Long, to: Long): List<TxnEntity> =
    observeRange(from, to).first()
