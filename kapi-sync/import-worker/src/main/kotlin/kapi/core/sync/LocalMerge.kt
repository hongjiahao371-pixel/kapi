package kapi.core.sync

/**
 * Lamport 计数器。设备内单调，不依赖墙上时钟。
 *
 * 使用规则（顺序不能反）：
 *  1. 本地每次修改 → [tick]
 *  2. 收到远端变更 → [observe]，把本地计数抬到不低于远端
 *  3. 先拉后推。必须在吸收完远端戳之后才推，否则自己的计数可能小于已知远端
 *     变更，合并结果不可预测。
 */
class LamportClock(initial: Long = 0) {
    var current: Long = initial
        private set

    fun tick(): Long {
        current += 1
        return current
    }

    fun observe(remote: Long) {
        if (remote > current) current = remote
    }

    fun reset(to: Long) {
        current = maxOf(current, to)
    }
}

/** 一次还没被服务端接受的本地编辑。 */
data class PendingField(
    val value: String,
    val stamp: FieldVersion,
)

/**
 * 客户端的一行。
 *
 * 三份状态刻意分开存，因为它们的含义完全不同：
 *  - [values] 用户现在看到的值
 *  - [serverFv] 服务端**已经知道的**戳（推送成功后才知道）
 *  - [pending]  本地已改但还没推上去的字段
 *
 * 混在一起就会出现那种经典丢数据：把本地戳写进 [serverFv]，然后远端快照一到
 * 就把用户刚改的东西盖回去。
 */
data class SyncedRow(
    val entity: String,
    val entityId: String,
    val values: Map<String, String> = emptyMap(),
    val serverFv: FieldVersions = emptyMap(),
    val pending: Map<String, PendingField> = emptyMap(),
    val delFv: FieldVersion = FieldVersion(),
    val deleted: Boolean = false,
    val lamport: Long = 0,
) {
    val hasPending: Boolean get() = pending.isNotEmpty()

    /** 账单是否对用户可见（未删除且没有未推送的删除）。 */
    fun isVisible(): Boolean = !deleted && !pending.containsKey(DELETED_FIELD)
}

/** 墓碑也当成一个「字段」来存，这样删除的推送与普通编辑走同一条路径。 */
const val DELETED_FIELD = "\$deleted"

object LocalMerge {

    /**
     * 应用服务端快照。
     *
     * 规则只有一条，但很关键：**本地还没推上去的字段，服务端快照不许覆盖。**
     *
     * 远端快照可能来自两台设备里的另一台，也可能比本地编辑更早——它反映的是
     * 服务端**在收到我们这次推送之前**的状态。如果直接覆盖，用户刚记的账会在
     * 推上去之前凭空消失，而用户什么都察觉不到。
     *
     * 同时仍然更新 [SyncedRow.serverFv] 到远端戳：推送时要和正确的基线比较。
     *
     * 被服务端拒绝的编辑不在这里处理——它们在推拉协议里由 [applyPushRejection]
     * 显式丢弃，随后下一次拉取会把权威值带回来。
     */
    fun applyRemote(
        current: SyncedRow,
        remote: Snapshot,
    ): SyncedRow {
        require(current.entity == remote.entity && current.entityId == remote.entityId) {
            "applyRemote: row identity mismatch " +
                "(${current.entity}/${current.entityId} vs ${remote.entity}/${remote.entityId})"
        }

        val newValues = current.values.toMutableMap()
        val newServerFv = current.serverFv.toMutableMap()

        for ((field, value) in remote.values) {
            val incoming = remote.fv[field] ?: FieldVersion()
            if (incoming < (newServerFv[field] ?: FieldVersion())) continue
            newServerFv[field] = incoming
            // 关键：pending 里的字段保持本地值不动。
            if (field in current.pending) continue
            newValues[field] = value
        }

        val deletion = maxOf(current.delFv, remote.delFv)
        val newestContent = (newServerFv.values + current.pending.filterKeys { it != DELETED_FIELD }.values.map { it.stamp }).maxOrNull() ?: FieldVersion()
        val serverDeleted = deletion > newestContent
        val localDeletePending = DELETED_FIELD in current.pending

        return current.copy(
            values = newValues,
            serverFv = newServerFv,
            delFv = deletion,
            // 本地还挂着未推送的删除时，不接受服务端的「行还活着」。
            deleted = if (localDeletePending) true else serverDeleted,
            lamport = maxOf(current.lamport, remote.lamport),
        )
    }

    /**
     * 记录一次本地编辑。
     *
     * 同一字段连续编辑会**复用同一个 change_id**：一次推送失败后重试必须被服务端
     * 幂等去重，如果每次编辑都换 id，重试就会把同一笔改动推两遍。
     */
    fun applyLocalEdit(
        current: SyncedRow,
        field: String,
        value: String,
        clock: LamportClock,
        deviceId: String,
        changeId: String,
    ): SyncedRow {
        val stamp = FieldVersion(clock.tick(), deviceId)
        val newValues = current.values.toMutableMap()
        newValues[field] = value

        val newPending = current.pending.toMutableMap()
        newPending[field] = PendingField(value, stamp)
        if (field != DELETED_FIELD && (newPending[DELETED_FIELD]?.stamp ?: FieldVersion()) < stamp) newPending.remove(DELETED_FIELD)
        val newestContent = (current.serverFv.values + newPending.filterKeys { it != DELETED_FIELD }.values.map { it.stamp }).maxOrNull() ?: FieldVersion()

        return current.copy(
            values = newValues,
            pending = newPending,
            // 删除也是一个「字段」，走同一条路径：立刻对用户隐藏，
            // 但在推送成功之前，服务端仍然认为这行活着。
            deleted = DELETED_FIELD in newPending || current.delFv > newestContent,
            lamport = maxOf(current.lamport, stamp.lamport),
        )
    }

    /**
     * 构造待推送的变更。没有 pending 时返回 null。
     *
     * 行的 [lamport] 取所有 pending 戳的最大值：服务端拿它和墓碑戳比，
     * 决定这次编辑是复活一行还是接受一次删除。
     */
    fun buildOutboxChange(current: SyncedRow, deviceId: String): OutboxChange? {
        if (current.pending.isEmpty()) return null

        val data = LinkedHashMap<String, String>(current.pending.size)
        val fv = LinkedHashMap<String, FieldVersion>(current.pending.size)
        for ((field, pf) in current.pending) {
            if (field == DELETED_FIELD) continue
            data[field] = pf.value
            fv[field] = pf.stamp
        }

        val isDelete = current.pending.containsKey(DELETED_FIELD)
        val topLamport = current.pending.values.maxOf { it.stamp.lamport }

        return OutboxChange(
            changeId = changeIdFor(current, deviceId, topLamport),
            entity = current.entity,
            entityId = current.entityId,
            op = if (isDelete) OP_DELETE else OP_UPSERT,
            data = data,
            fv = fv,
            lamport = topLamport,
            origin = deviceId,
            localDeleteStamp = current.pending[DELETED_FIELD]?.stamp,
        )
    }

    /**
     * 推送成功后：pending 清空，戳提升为「服务端已知」。
     *
     * 注意顺序：先推送成功，再清 pending。反过来的话断网就永久丢账——
     * 这是这类 App 最经典的丢数据路径。
     */
    fun applyPushSuccess(current: SyncedRow, sent: OutboxChange): SyncedRow {
        val newServerFv = current.serverFv.toMutableMap()
        for ((field, stamp) in sent.fv) {
            newServerFv[field] = maxOf(stamp, newServerFv[field] ?: FieldVersion())
        }

        val newPending = current.pending.toMutableMap()
        for ((field,stamp) in sent.fv) {
            if (newPending[field]?.stamp == stamp) newPending.remove(field)
        }
        // 纯删除的推送没有数据字段，需要单独把墓碑从 pending 里摘掉。
        val sentDelete = FieldVersion(sent.lamport, sent.origin)
        val deletion = if (sent.op == OP_DELETE) maxOf(current.delFv,sentDelete) else current.delFv
        if (sent.op == OP_DELETE && newPending[DELETED_FIELD]?.stamp == (sent.localDeleteStamp ?: sentDelete)) newPending.remove(DELETED_FIELD)
        val newest = (newServerFv.values + newPending.filterKeys { it != DELETED_FIELD }.values.map { it.stamp }).maxOrNull() ?: FieldVersion()
        return current.copy(serverFv = newServerFv, pending = newPending, delFv=deletion, deleted=DELETED_FIELD in newPending || deletion>newest)
    }

    /**
     * 服务端明确拒绝了某些字段：丢弃本地版本。
     *
     * 不在这里猜「服务端认为的正确值是多少」——服务端会把它算进日志，下一次
     * 拉取自然带回来。在拒绝和下一次拉取之间，本地短暂显示用户自己刚输的那个值，
     * 这比伪造一个中间状态要好：用户看到的是自己做了什么，而不是系统在偷偷改。
     */
    fun applyPushRejection(
        current: SyncedRow,
        sent: OutboxChange,
        rejectedFields: List<String>,
    ): SyncedRow {
        val newPending = current.pending.toMutableMap()
        for (field in rejectedFields) {
            if (newPending[field]?.stamp == sent.fv[field]) newPending.remove(field)
        }
        if (sent.op == OP_DELETE && newPending[DELETED_FIELD]?.stamp == (sent.localDeleteStamp ?: FieldVersion(sent.lamport,sent.origin))) newPending.remove(DELETED_FIELD)
        return current.copy(pending = newPending)
    }

    const val OP_UPSERT = "upsert"
    const val OP_DELETE = "delete"

    /**
     * change_id 必须在「同一批 pending」的重试之间保持稳定，否则幂等去重失效。
     * 组成：行 id + 本批最高 lamport + 设备。同一行的第二次独立编辑 lamport 必然
     * 不同，所以不会撞。
     */
    private fun changeIdFor(row: SyncedRow, deviceId: String, topLamport: Long): String =
        "$deviceId:${row.entity}/${row.entityId}:$topLamport"
}
