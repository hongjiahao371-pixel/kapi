package kapi.ledger.sync

import androidx.room.withTransaction
import kapi.core.sync.FieldVersion
import kapi.core.sync.FieldVersions
import kapi.core.sync.LamportClock
import kapi.core.sync.LocalMerge
import kapi.core.sync.OutboxChange
import kapi.core.sync.PendingField
import kapi.core.sync.Snapshot
import kapi.core.sync.SyncedRow
import kapi.ledger.data.KapiDatabase
import kapi.ledger.data.AccountEntity
import kapi.ledger.data.CategoryEntity
import kapi.ledger.data.SyncStateEntity
import kapi.ledger.data.TxnEntity
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

/**
 * 同步编排。
 *
 * 顺序是**先拉后推**，不能反：
 *  1. 拉远端变更，合并进本地
 *  2. 吸收远端 lamport，抬高本地计数
 *  3. 才推自己的 outbox
 *
 * 反过来的话，本地 lamport 可能小于已知远端变更，合并结果不可预测。
 *
 * 纯业务逻辑放 `kapi.core`，这里只做「HTTP + Room」的胶水。
 */
class SyncEngine(
    private val db: KapiDatabase,
    private val client: SyncClient,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {

    sealed interface SyncResult {
        data object NotPaired : SyncResult
        data class Success(val pushed: Int, val pulled: Int) : SyncResult
        data class Rejected(val count: Int) : SyncResult
        data class Failed(val failure: SyncFailure) : SyncResult
    }

    suspend fun sync(baseUrl: String, token: String, deviceId: String): SyncResult {
        return try {
            // ---- 1. 拉 ----
            val state = db.syncStateDao().get() ?: SyncStateEntity()
            var cursor = state.cursor
            var pulled = 0
            val clock = LamportClock(state.lamportCounter)

            while (true) {
                val page = client.changes(baseUrl, token, cursor)
                if (page.changes.isEmpty()) {
                    cursor = page.cursor
                    break
                }
                applyRemote(page.changes.mapNotNull { it.snapshot })
                page.changes.forEach { clock.observe(it.snapshot?.lamport ?: 0) }
                pulled += page.changes.size
                val advanced = page.cursor > cursor
                cursor = page.cursor
                if (!page.hasMore) break
                // 服务端说有下一页却没推进游标，继续转下去就是死循环。
                // 这是 WorkManager 里跑的同步，死循环等于后台一直烧电，用户看不出来。
                if (!advanced) break
            }

            // ---- 2. 抬 lamport，再推 ----
            // LamportClock.observe 已经在上面做过，这里只需保证不落后。
            var pushed = 0
            var rejected = 0

            val txnBatch = db.txnDao().pendingBatch()
            if (txnBatch.isNotEmpty()) {
                // 行和它对应的 change 必须**成对**往下传。
                // 早前是 rows.mapNotNull { buildOutboxChange(it) } 得到 changes，
                // 提交时再用 `sent.withIndex()` 去 `changes[i]` 里取——
                // 只要 mapNotNull 丢掉过任何一个（buildOutboxChange 在没有 pending 时返回 null），
                // 下标就整体错位，**另一行的 pending 会被当成已推送清掉**，那笔账就此静默丢失。
                val pairs = txnBatch.mapNotNull { entity ->
                    LocalMerge.buildOutboxChange(entity.toSyncedRow(), deviceId)
                        ?.let { change -> entity to change }
                }
                if (pairs.isNotEmpty()) {
                    val res = client.push(baseUrl, token, deviceId, pairs.map { it.second })
                    pushed = res.applied.size
                    rejected = res.rejected.size
                    commitPush(pairs, res)
                }
            }

            // 参考数据（分类/账户）走同一条 outbox 通道。必须一起推：
            // txn 里的 category_id 是指向它们的指针，只推账单不推分类，
            // 换一台手机连上来就全是「未分类」。
            val refBatch = db.categoryDao().pendingBatch(500).map { it.toSyncedRow() } +
                db.accountDao().pendingBatch(500).map { it.toSyncedRow() }
            if (refBatch.isNotEmpty()) {
                val refPairs = refBatch.mapNotNull { row ->
                    LocalMerge.buildOutboxChange(row, deviceId)?.let { change -> row to change }
                }
                if (refPairs.isNotEmpty()) {
                    val res = client.push(baseUrl, token, deviceId, refPairs.map { it.second })
                    rejected += res.rejected.size
                    commitRefPush(refPairs, res)
                }
            }

            db.syncStateDao().put(
                state.copy(
                    baseUrl = baseUrl,
                    deviceId = deviceId,
                    cursor = cursor,
                    lamportCounter = clock.current,
                    lastSyncAt = System.currentTimeMillis(),
                    lastError = null,
                )
            )

            if (rejected > 0) SyncResult.Rejected(rejected) else SyncResult.Success(pushed, pulled)
        } catch (f: SyncFailure) {
            db.syncStateDao().get()?.let {
                db.syncStateDao().put(it.copy(lastError = f.message))
            }
            SyncResult.Failed(f)
        }
    }

    /**
     * 把远端快照写回本地。
     *
     * 逐行处理而不是批量 REPLACE，因为 pending 的字段不能被覆盖——
     * 这正是 `LocalMerge.applyRemote` 的职责。批量 REPLACE 会静默丢掉用户
     * 还没推上去的编辑，而且没有任何报错。
     *
     * 本地没有的行要**插进来**。早前这里是 `?: continue`，后果是：新手机装上 App、
     * 连上 NAS 之后永远拉不到任何历史账单，只能看见自己在本机记的那些——
     * 而服务端明明有数据、客户端也明明收到了快照，整个过程零报错。
     * 靠"每台设备都会先自己记一笔"来回避是行不通的：用户换机的第一诉求就是把
     * 旧账搬过来。
     */
    private suspend fun applyRemote(snapshots: List<Snapshot>) {
        if (snapshots.isEmpty()) return
        db.withTransaction {
            val ledgerId = db.ledgerDao().firstActive()?.id ?: DEFAULT_LEDGER_ID

            val cats = ArrayList<CategoryEntity>()
            val accts = ArrayList<AccountEntity>()

            val txnDao = db.txnDao()
            val catDao = db.categoryDao()
            val acctDao = db.accountDao()

            for (snap in snapshots) {
                when (snap.entity) {
                    ENTITY_TXN -> {
                        val existing = txnDao.byId(snap.entityId)
                        if (existing == null) {
                            txnDao.upsertAll(listOf(snap.toNewEntity(ledgerId)))
                        } else {
                            txnDao.update(LocalMerge.applyRemote(existing.toSyncedRow(), snap).toEntity(existing))
                        }
                    }

                    // 分类/账户走"有则合并、无则插入"，和 txn 完全一样。
                    // 字段级 LWW 意味着两台手机各自改同一个分类名也不会互相抹掉。
                    ENTITY_CATEGORY -> {
                        val existing = catDao.byId(snap.entityId)
                        if (existing == null) {
                            cats += snap.toCategoryEntity(ledgerId)
                        } else {
                            val merged = LocalMerge.applyRemote(existing.toSyncedRow(), snap)
                            catDao.update(merged.toCategory(existing))
                        }
                    }

                    ENTITY_ACCOUNT -> {
                        val existing = acctDao.byId(snap.entityId)
                        if (existing == null) {
                            accts += snap.toAccountEntity(ledgerId)
                        } else {
                            val merged = LocalMerge.applyRemote(existing.toSyncedRow(), snap)
                            acctDao.update(merged.toAccount(existing))
                        }
                    }
                    // 未知实体直接丢。服务端将来加了新表时旧版客户端不该崩，
                    // 少同步一类参考数据远好过整个同步流程失败。
                }
            }
            if (cats.isNotEmpty()) catDao.upsertAll(cats)
            if (accts.isNotEmpty()) acctDao.upsertAll(accts)
        }
    }

    private suspend fun commitPush(
        pairs: List<Pair<TxnEntity, OutboxChange>>,
        res: PushDto,
    ) {
        val rejectedByChange = res.rejected.associateBy { it.changeId }

        db.withTransaction {
            val dao = db.txnDao()
            for ((entity, change) in pairs) {
                val row = entity.toSyncedRow()
                val rejected = rejectedByChange[change.changeId]
                val updated = if (rejected != null) {
                    LocalMerge.applyPushRejection(row, change, rejected.fields)
                } else {
                    LocalMerge.applyPushSuccess(row, change)
                }
                dao.update(updated.toEntity(entity))
            }
        }
    }

    /**
     * 分类/账户的推送回执。和 [commitPush] 是同一套逻辑，只是落库的对象不同。
     *
     * 单独写一份而不是泛型化，是因为 Room 的 `update` 是两个不同实体的重载，
     * 泛型到 `T` 之后拿不到 dao 本身——为了省十几行去绕反射不划算。
     */
    private suspend fun commitRefPush(
        pairs: List<Pair<SyncedRow, OutboxChange>>,
        res: PushDto,
    ) {
        val rejectedByChange = res.rejected.associateBy { it.changeId }
        val catDao = db.categoryDao()
        val acctDao = db.accountDao()

        db.withTransaction {
            for ((row, change) in pairs) {
                val rejected = rejectedByChange[change.changeId]
                val updated = if (rejected != null) {
                    LocalMerge.applyPushRejection(row, change, rejected.fields)
                } else {
                    LocalMerge.applyPushSuccess(row, change)
                }
                when (row.entity) {
                    ENTITY_CATEGORY -> {
                        val orig = catDao.byId(row.entityId) ?: continue
                        catDao.update(updated.toCategory(orig))
                    }
                    ENTITY_ACCOUNT -> {
                        val orig = acctDao.byId(row.entityId) ?: continue
                        acctDao.update(updated.toAccount(orig))
                    }
                    else -> Unit
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 分类 / 账户的同步桥
//
// 这两张表以前**根本不参与同步**，而 txn 里的 category_id / account_id 恰恰是
// 指向它们的指针。后果是：任何一台新手机连上 NAS 之后，拉回来的每一笔账都
// 显示「未分类」——服务端那边数据是全的，面板也显示得出来，但手机本地查不到
// 名字，因为本地表里只有自己那批 cat-0..cat-10，和 NAS 侧的 id 对不上。
//
// 之前一直没暴露，是因为账本看起来仍然"有内容"：金额、商户、备注都在，
// 只有分类那一列是空的。零报错、零日志。
//
// 实体的 schema 本来就带 serverFvJson / pendingJson / lamport / deleted，
// 也就是说这套同步是**一开始就为它们设计好的**，只是引擎没接上。
// ---------------------------------------------------------------------------

internal fun CategoryEntity.toSyncedRow(): SyncedRow = SyncedRow(
    entity = ENTITY_CATEGORY,
    entityId = id,
    values = businessValues(),
    serverFv = serverFvJson.decodeFv(),
    pending = pendingJson.decodePending(),
    delFv = delFvJson.decodeDelFv(),
    deleted = deleted,
    lamport = lamport,
)

internal fun CategoryEntity.businessValues(): Map<String, String> = buildMap {
    put("name", "\"$name\"")
    put("kind", "\"$kind\"")
    put("sort_order", sortOrder.toString())
    parentId?.let { put("parent_id", "\"$it\"") }
    icon?.let { put("icon", "\"$it\"") }
}

private fun Snapshot.toCategoryEntity(ledgerId: String): CategoryEntity = CategoryEntity(
    id = entityId,
    ledgerId = ledgerId,
    parentId = values["parent_id"]?.asPlainString(),
    name = values["name"]?.asPlainString() ?: entityId,
    icon = values["icon"]?.asPlainString(),
    kind = values["kind"]?.asPlainString() ?: "EXPENSE",
    sortOrder = values["sort_order"].asLongOrNull()?.toInt() ?: 0,
    serverFvJson = fv.encodeFv(),
    pendingJson = "{}",
    hasPending = false,
    delFvJson = delFv.encodeDelFv(),
    deleted = isDeleted(),
    lamport = lamport,
)

internal fun AccountEntity.toSyncedRow(): SyncedRow = SyncedRow(
    entity = ENTITY_ACCOUNT,
    entityId = id,
    values = businessValues(),
    serverFv = serverFvJson.decodeFv(),
    pending = pendingJson.decodePending(),
    delFv = delFvJson.decodeDelFv(),
    deleted = deleted,
    lamport = lamport,
)

internal fun AccountEntity.businessValues(): Map<String, String> = buildMap {
    put("name", "\"$name\"")
    put("kind", "\"$kind\"")
    put("balance_minor", balanceMinor.toString())
    put("currency", "\"$currency\"")
    put("sort_order", sortOrder.toString())
    icon?.let { put("icon", "\"$it\"") }
}

private fun Snapshot.toAccountEntity(ledgerId: String): AccountEntity = AccountEntity(
    id = entityId,
    ledgerId = ledgerId,
    name = values["name"]?.asPlainString() ?: entityId,
    kind = values["kind"]?.asPlainString() ?: "CASH",
    balanceMinor = values["balance_minor"].asLongOrNull() ?: 0L,
    currency = values["currency"]?.asPlainString() ?: "CNY",
    icon = values["icon"]?.asPlainString(),
    sortOrder = values["sort_order"].asLongOrNull()?.toInt() ?: 0,
    serverFvJson = fv.encodeFv(),
    pendingJson = "{}",
    hasPending = false,
    delFvJson = delFv.encodeDelFv(),
    deleted = isDeleted(),
    lamport = lamport,
)

// ---------------------------------------------------------------------------
// Room 实体 <-> core 纯模型的桥
//
// 字段值在两侧都是**原始 JSON 字符串**，不是解析后的对象。
// 金额在 Kotlin 里是 Long，但传输层和「服务端不是我的类型」这件事无关，
// 保持字节透传可以避免任何一次编码/解码往返把它变成别的类型。
// ---------------------------------------------------------------------------

/**
 * 合并后的 [SyncedRow] 写回分类行。
 *
 * 保留本地的 ledgerId：账本归属是本机概念，不在同步字段里。
 * 远端没带的字段一律回落到本地旧值，而不是变成 null——`?: original.x`
 * 和直接赋值在"远端只推了部分字段"时结果完全不同。
 *
 * `deleted` 直接取合并结果的字段：[LocalMerge.applyRemote] 已经把「本地还挂着
 * 未推送的删除」算进去了；在这里重新推导一遍只会得到一个既不是本地状态、
 * 也不是远端状态的第三种值。
 */
internal fun SyncedRow.toCategory(original: CategoryEntity): CategoryEntity = original.copy(
    name = values["name"]?.asPlainString() ?: original.name,
    kind = values["kind"]?.asPlainString() ?: original.kind,
    sortOrder = values["sort_order"].asLongOrNull()?.toInt() ?: original.sortOrder,
    parentId = values["parent_id"]?.asPlainString() ?: original.parentId,
    icon = values["icon"]?.asPlainString() ?: original.icon,
    serverFvJson = serverFv.encodeFv(),
    pendingJson = pending.encodePending(),
    hasPending = pending.isNotEmpty(),
    delFvJson = delFv.encodeDelFv(),
    deleted = deleted,
    lamport = lamport,
)

internal fun SyncedRow.toAccount(original: AccountEntity): AccountEntity = original.copy(
    name = values["name"]?.asPlainString() ?: original.name,
    kind = values["kind"]?.asPlainString() ?: original.kind,
    balanceMinor = values["balance_minor"].asLongOrNull() ?: original.balanceMinor,
    currency = values["currency"]?.asPlainString() ?: original.currency,
    sortOrder = values["sort_order"].asLongOrNull()?.toInt() ?: original.sortOrder,
    icon = values["icon"]?.asPlainString() ?: original.icon,
    serverFvJson = serverFv.encodeFv(),
    pendingJson = pending.encodePending(),
    hasPending = pending.isNotEmpty(),
    delFvJson = delFv.encodeDelFv(),
    deleted = deleted,
    lamport = lamport,
)

/** pendingJson 的行格式：`{"note":{"value":"午餐","l":12,"d":"phone"}}` */
@kotlinx.serialization.Serializable
internal data class PendingEntry(
    val value: String,
    val l: Long,
    val d: String,
)

/** serverFvJson / delFvJson 的行格式：`{"amount_minor":{"l":10,"d":"phone"}}` */
@kotlinx.serialization.Serializable
internal data class FvEntry(val l: Long, val d: String)

internal fun TxnEntity.toSyncedRow(): SyncedRow = SyncedRow(
    entity = "txn",
    entityId = id,
    values = businessValues(),
    serverFv = serverFvJson.decodeFv(),
    pending = pendingJson.decodePending(),
    delFv = delFvJson.decodeDelFv(),
    deleted = deleted,
    lamport = lamport,
)

/**
 * 一行要参与同步的业务字段。
 *
 * 值一律是**原始 JSON 文本**，不是解析后的对象——这是传输层的约定，服务端按
 * 字节透传。见 [String.asLongOrNull] 关于这套约定造成的真实故障。
 *
 * internal 而不是 private：[LocalEditWriter] 写本地编辑时必须用**完全相同**的
 * 字段集和编码方式，两边各写一份迟早会对不上，而对不上的表现是静默丢字段。
 */
internal fun TxnEntity.businessValues(): Map<String, String> = buildMap {
    put("amount_minor", amountMinor.toString())
    put("direction", "\"$direction\"")
    put("account_id", "\"$accountId\"")
    categoryId?.let { put("category_id", "\"$it\"") }
    toAccountId?.let { put("to_account_id", "\"$it\"") }
    put("occurred_at", occurredAt.toString())
    note?.let { put("note", it.asJsonString()) }
    merchant?.let { put("merchant", it.asJsonString()) }
}

/**
 * 远端快照 → 一条全新的本地行。
 *
 * 服务端只存客户端推上去的字段，账本归属（ledgerId）不在其中，所以由调用方
 * 指定当前账本。识别来源记成 SYNC：这一笔是别的设备写的，本机没见过它被识别。
 */
private fun Snapshot.toNewEntity(ledgerId: String): TxnEntity = TxnEntity(
    id = entityId,
    ledgerId = ledgerId,
    amountMinor = values["amount_minor"].asLongOrNull() ?: 0L,
    direction = values["direction"]?.asPlainString() ?: "UNKNOWN",
    accountId = values["account_id"]?.asPlainString() ?: DEFAULT_ACCOUNT_ID,
    toAccountId = values["to_account_id"]?.asPlainString(),
    categoryId = values["category_id"]?.asPlainString(),
    occurredAt = values["occurred_at"].asLongOrNull() ?: 0L,
    note = values["note"]?.asPlainString(),
    merchant = values["merchant"]?.asPlainString(),
    serverFvJson = fv.encodeFv(),
    pendingJson = "{}",
    hasPending = false,
    delFvJson = delFv.encodeDelFv(),
    deleted = isDeleted(),
    lamport = lamport,
    source = SOURCE_SYNC,
    confidence = 0.0,
)

internal fun SyncedRow.toEntity(original: TxnEntity): TxnEntity = original.copy(
    // 走 asLongOrNull 而不是 toLongOrNull：经过服务端的值会带一层 JSON 引号，
    // 直接 toLongOrNull 会得到 null，金额就静默变成 0 或退回本地旧值。详见 asLongOrNull。
    amountMinor = values["amount_minor"].asLongOrNull() ?: original.amountMinor,
    // 方向和归属也是会同步的业务字段。漏掉它们的后果是：A 在手机上把一笔支出
    // 改成收入，B 那边拉下来仍然显示支出——而 direction 正是这个产品拿来替代
    // 「正负号表示收支」的那根支柱，错了就是把收入显示成支出。
    direction = values["direction"]?.asPlainString() ?: original.direction,
    accountId = values["account_id"]?.asPlainString() ?: original.accountId,
    categoryId = values["category_id"]?.asPlainString() ?: original.categoryId,
    toAccountId = values["to_account_id"]?.asPlainString() ?: original.toAccountId,
    occurredAt = values["occurred_at"].asLongOrNull() ?: original.occurredAt,
    note = values["note"]?.asPlainString() ?: original.note,
    merchant = values["merchant"]?.asPlainString() ?: original.merchant,
    serverFvJson = serverFv.encodeFv(),
    // 注意接收者：encodePending 是 Map 的扩展函数，不是 SyncedRow 的。
    pendingJson = pending.encodePending(),
    hasPending = pending.isNotEmpty(),
    delFvJson = delFv.encodeDelFv(),
    deleted = deleted,
    lamport = maxOf(original.lamport, lamport),
)

/** 远端来的行归到哪个账本。v1 是单账本，所以只在极端情况下才用得上这个兜底值。 */
private const val DEFAULT_LEDGER_ID = "L1"

/** 远端快照没带 account_id 时的兜底。服务端不校验它，随便给一个稳定值即可。 */
private const val DEFAULT_ACCOUNT_ID = "A1"

internal const val SOURCE_SYNC = "SYNC"

/**
 * 参与同步的实体名。
 *
 * 三张表都参与：账目、分类、账户。少同步任何一张，引用它的那张表就会出现
 * "指针悬空"——账目还在，指向的名字没了，界面上是一整列「未分类」。
 */
internal const val ENTITY_TXN = "txn"
internal const val ENTITY_CATEGORY = "category"
internal const val ENTITY_ACCOUNT = "account"

// --- JSON 小工具 ---------------------------------------------------------
//
// 早期这几段是空实现（decodePending 直接返回 emptyMap），后果是**所有未推送
// 的本地编辑在读出来时凭空消失**——用户记的账在同步前就"忘了"，而且不报错。
// 空实现比崩溃危险得多，尤其是在一个没人测到的边上。

// encodeToString / decodeFromString 的 reified 扩展**必须显式 import**。
// kotlinx.serialization 只在成员位置提供「显式传 serializer」的重载，少了这行
// import，编译器会安静地选中那个重载，然后报一堆和真因毫无关系的类型推断错误。

private val bridgeJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}

private fun String.asJsonString(): String =
    bridgeJson.encodeToString(JsonPrimitive(this))

/**
 * 把「JSON 文本形态的整数」读成 Long。
 *
 * 这层是**必需**的，不是洁癖。传输层的约定是"值一律是原始 JSON 文本"，
 * 而 `Map<String, String>` 序列化时每个值都会再套一层引号，于是服务端原样存、
 * 原样回的 `amount_minor` 长这样：
 *
 * ```json
 * "amount_minor":"8888"
 * ```
 *
 * Kotlin 侧拿到的字符串是 `"8888"` 六个字符，直接 `toLongOrNull()` 得到 **null**。
 * 早前 `toEntity` 用的就是 `toLongOrNull()`，后果是**凡是经服务端回来的金额和时间戳
 * 全部读成 0**（新设备）或悄悄退回本地旧值（老设备）——而备注走 `asPlainString()`
 * 剥了引号所以看起来一切正常，没有任何报错。
 *
 * 这就是两端各写一遍协议的代价：Go 侧 29 条 E2E 和 Kotlin 侧 55 个单测都测不到它，
 * 因为它只存在于"真连一次"的那条路径上。
 *
 * 接收者声明成可空，是因为它直接作用在 `map["key"]` 上——键不存在时不该崩，
 * 该由调用方决定回退成什么（`?: 0L` 还是 `?: original.amountMinor` 语义完全不同）。
 */
private fun String?.asLongOrNull(): Long? {
    var s = this ?: return null
    // 正常只需要剥一层（Map<String,String> 序列化套上的那层）。
    // 循环上限 3 是为了容忍"已经编码过的文本又被编码一次"的上游，
    // 同时保证遇到病态输入不会在这里转圈。
    var rounds = 0
    while (rounds < 3) {
        val unwrapped = s.asPlainString()
        if (unwrapped == s) break
        s = unwrapped
        rounds++
    }
    s.toLongOrNull()?.let { return it }
    // 万一某天上游发了 2350.0，也不该把金额读成 0。
    // longValueExact 带小数会抛异常，正好被 runCatching 收成 null。
    return runCatching { s.toBigDecimal().longValueExact() }.getOrNull()
}

private fun String.asPlainString(): String {
    if (!startsWith("\"")) return this
    return runCatching {
        bridgeJson.parseToJsonElement(this).jsonPrimitive.content
    }.getOrDefault(this)
}

private fun String.decodeFv(): FieldVersions =
    runCatching {
        bridgeJson.decodeFromString<Map<String, FvEntry>>(this)
            .mapValues { (_, e) -> FieldVersion(e.l, e.d) }
    }.getOrDefault(emptyMap())

private fun FieldVersions.encodeFv(): String = runCatching {
    bridgeJson.encodeToString(mapValues { (_, v) -> FvEntry(v.lamport, v.device) })
}.getOrDefault("{}")

private fun String.decodePending(): Map<String, PendingField> =
    runCatching {
        bridgeJson.decodeFromString<Map<String, PendingEntry>>(this)
            .mapValues { (_, e) -> PendingField(e.value, FieldVersion(e.l, e.d)) }
    }.getOrDefault(emptyMap())

private fun Map<String, PendingField>.encodePending(): String = runCatching {
    bridgeJson.encodeToString(
        mapValues { (_, v) -> PendingEntry(v.value, v.stamp.lamport, v.stamp.device) }
    )
}.getOrDefault("{}")

private fun String.decodeDelFv(): FieldVersion =
    runCatching { bridgeJson.decodeFromString<FvEntry>(this) }
        .getOrNull()
        ?.let { FieldVersion(it.l, it.d) }
        ?: FieldVersion()

private fun FieldVersion.encodeDelFv(): String =
    runCatching { bridgeJson.encodeToString(FvEntry(lamport, device)) }
        .getOrDefault("""{"l":0,"d":""}""")
