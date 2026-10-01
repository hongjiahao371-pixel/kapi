package kapi.ledger.sync

import androidx.room.withTransaction
import kapi.core.sync.DELETED_FIELD
import kapi.core.sync.LamportClock
import kapi.core.sync.LocalMerge
import kapi.ledger.data.KapiDatabase
import kapi.ledger.data.SyncStateEntity
import kapi.ledger.data.TxnEntity
import java.util.UUID

/**
 * 本地编辑写入器——**唯一**允许往 Room 里写 pending 的地方。
 *
 * 编码格式必须和 [SyncEngine] 读取时用的完全一致，所以这里走的是同一套
 * [kapi.core.sync.SyncedRow] / [LocalMerge] 桥接，而不是自己拼 JSON 字符串。
 * 手拼的格式一旦对不上（比如少了"剥一层引号"这个约定），pending 会静默丢字段，
 * 用户记的账表现为"同步一次就消失"，而且没有任何报错。这不是假设——同一条路径上
 * 已经因为值多套一层引号把**所有金额**读成 0 过。
 *
 * 另一条硬规矩：**先落库、再推服务端**。反过来就是断网即丢账。
 */
class LocalEditWriter(private val db: KapiDatabase) {

    /**
     * 记一笔新账。
     *
     * 返回写入后的行。调用方不需要再关心 pending——戳已经打好了。
     *
     * [amountMinor] 是分单位的**非负数**。收支方向在 [direction] 里，不靠符号表达：
     * 一个"把 -23.50 当支出"和一个"当成收入"的模型在数据上无法区分，界面上就会把
     * 支出显示成收入。require 挡掉负数是最后一道防线。
     */
    suspend fun createTransaction(
        amountMinor: Long,
        direction: String,
        accountId: String,
        occurredAt: Long,
        ledgerId: String,
        deviceId: String,
        note: String? = null,
        merchant: String? = null,
        categoryId: String? = null,
        toAccountId: String? = null,
        source: String = SOURCE_MANUAL,
        confidence: Double = 1.0,
    ): TxnEntity = db.withTransaction {
        require(amountMinor >= 0) { "amountMinor 是分单位的非负整数，收到 $amountMinor" }
        require(direction != "UNKNOWN") { "新账必须明确方向，UNKNOWN 只能来自识别失败的草稿" }

        val base = TxnEntity(
            id = UUID.randomUUID().toString(),
            ledgerId = ledgerId,
            amountMinor = amountMinor,
            direction = direction,
            accountId = accountId,
            toAccountId = toAccountId,
            categoryId = categoryId,
            occurredAt = occurredAt,
            note = note?.trim()?.takeIf { it.isNotEmpty() },
            merchant = merchant?.trim()?.takeIf { it.isNotEmpty() },
            source = source,
            confidence = confidence,
        )

        var row = base.toSyncedRow()
        val clock = LamportClock(state().lamportCounter)
        val changeId = "$deviceId:txn/${base.id}:0"
        // 每个业务字段单独打戳。少一个戳，那个字段在服务端就永远是旧值。
        for ((field, value) in base.businessValues()) {
            row = LocalMerge.applyLocalEdit(row, field, value, clock, deviceId, changeId)
        }

        val stored = row.toEntity(base)
        db.txnDao().upsertAll(listOf(stored))
        saveLamport(clock.current)
        stored
    }

    /** 改一个字段。返回 false 表示这一行已经不在了。 */
    suspend fun editField(
        entityId: String,
        field: String,
        value: String,
        deviceId: String,
    ): Boolean = db.withTransaction {
        val existing = db.txnDao().byId(entityId) ?: return@withTransaction false
        val clock = LamportClock(state().lamportCounter)
        val row = LocalMerge.applyLocalEdit(
            existing.toSyncedRow(), field, value, clock, deviceId,
            "$deviceId:txn/$entityId:0",
        )
        db.txnDao().update(row.toEntity(existing))
        saveLamport(clock.current)
        true
    }

    /**
     * 软删除。
     *
     * 删除也是一个 pending 字段：界面上立刻消失，但**在推送成功之前服务端仍然认为
     * 这行活着**。真删本地行的话，推送失败时就永远没有机会把删除告诉服务端，
     * 回家一同步这行又冒出来。
     */
    suspend fun softDelete(entityId: String, deviceId: String): Boolean = db.withTransaction {
        val existing = db.txnDao().byId(entityId) ?: return@withTransaction false
        val clock = LamportClock(state().lamportCounter)
        val row = LocalMerge.applyLocalEdit(
            existing.toSyncedRow(), DELETED_FIELD, "true", clock, deviceId,
            "$deviceId:txn/$entityId:0",
        )
        db.txnDao().update(row.toEntity(existing))
        saveLamport(clock.current)
        true
    }

    private suspend fun state(): SyncStateEntity = db.syncStateDao().get() ?: SyncStateEntity()

    private suspend fun saveLamport(lamport: Long) {
        val s = state()
        db.syncStateDao().put(s.copy(lamportCounter = maxOf(s.lamportCounter, lamport)))
    }

    companion object {
        const val SOURCE_MANUAL = "MANUAL"
        const val SOURCE_LLM = "LLM"
        const val SOURCE_LOCAL_FALLBACK = "LOCAL_FALLBACK"
    }
}
