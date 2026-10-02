package kapi.ledger.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * 聚合行：`categoryId` + 出现次数。
 *
 * 顶层而不是嵌在 DAO 接口里——Room 的注解处理器对接口内嵌的 POJO 处理得
 * 不太稳，而这个类型是 [TxnDao.categoryHistogramFor] 的返回类型，
 * 自学习全靠它。
 */
data class CategoryCount(val categoryId: String, val n: Int)

@Dao
interface TxnDao {

    @Query("SELECT * FROM txn WHERE deleted = 0 AND occurredAt >= :from AND occurredAt < :to ORDER BY occurredAt DESC")
    fun observeRange(from: Long, to: Long): Flow<List<TxnEntity>>

    @Query("SELECT * FROM txn WHERE id = :id")
    suspend fun byId(id: String): TxnEntity?

    /**
     * 「这个商户的账都被归到哪几档，各多少次」——自学习的数据来源。
     *
     * 直接从 `txn` 表聚合，不另建影子表：`merchant` + `categoryId` 本来就在行里，
     * 抄一份出去就要多一处同步（账目被改、被删都得跟着撤），而两处一漂移，
     * 表现就是"我明明改过了，它还按老的分类"。
     *
     * `merchant = :merchant` 是**精确匹配**：见 [kapi.core.auto.MerchantLearning.normalize]
     * 对归一化的取舍。宁可学得少。
     */
    @Query(
        "SELECT categoryId, COUNT(*) AS n FROM txn " +
            "WHERE deleted = 0 AND categoryId IS NOT NULL AND merchant = :merchant " +
            "GROUP BY categoryId ORDER BY n DESC"
    )
    suspend fun categoryHistogramFor(merchant: String): List<CategoryCount>

    /**
     * 同步取一段区间，用于"账本里有没有一笔很像的"这种一次性判断。
     *
     * 刻意和 [observeRange] 分开：那个是给界面订阅的 Flow，而自动记账管线要的是
     * 一次性的同步答案。在协程里 `first()` 一个 Flow 能凑合，但会在捕获路径上多挂
     * 一个收集器，而捕获路径每次自动记账都要跑一遍。
     */
    @Query("SELECT * FROM txn WHERE deleted = 0 AND occurredAt >= :from AND occurredAt < :to")
    suspend fun inRange(from: Long, to: Long): List<TxnEntity>

    /**
     * 全部未删除的账目，按时间正序。
     *
     * 导出和导入去重都要"一次拿到全量"：[LedgerImportExport] 导出时遍历它，
     * 导入时先把它的每一行算成指纹建成集合，再决定哪一行跳过。
     * 软删的行不在里面——用户删掉的账不该在导出文件里复活。
     */
    @Query("SELECT * FROM txn WHERE deleted = 0 ORDER BY occurredAt ASC")
    suspend fun allNotDeleted(): List<TxnEntity>

    /** 同额同向且时间落在区间内。自动记账用来标"这笔可能已经记过了"。 */
    @Query(
        "SELECT * FROM txn WHERE deleted = 0 AND amountMinor = :amount AND direction = :dir " +
            "AND occurredAt >= :from AND occurredAt < :to ORDER BY occurredAt DESC LIMIT 4"
    )
    suspend fun sameAmountNear(amount: Long, dir: String, from: Long, to: Long): List<TxnEntity>

    /**
     * 待推送的行。
     *
     * `hasPending` 有独立索引，所以"哪些行要推"这个查询在同步时被扫几十遍
     * 也不会拖慢主流程。早期版本打算解析 pendingJson 来判断，等于每次同步
     * 都把候选行全拉进内存解 JSON。
     */
    @Query("SELECT * FROM txn WHERE hasPending = 1 ORDER BY lamport ASC LIMIT :limit")
    suspend fun pendingBatch(limit: Int = 200): List<TxnEntity>

    @Query("SELECT COUNT(*) FROM txn WHERE hasPending = 1")
    fun observePendingCount(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rows: List<TxnEntity>)

    @Update
    suspend fun update(row: TxnEntity)

    /** 细类合并时改指向：同一 (父类, 名字) 的重复行收敛到保留行（2026-10-03）。 */
    @Query("UPDATE txn SET categoryId = :to WHERE categoryId = :from")
    suspend fun repointCategory(from: String, to: String)

    @Transaction
    suspend fun applyBatch(rows: List<TxnEntity>) {
        rows.forEach { upsertAll(listOf(it)) }
    }
}

@Dao
interface CategoryDao {
    @Query("SELECT * FROM category WHERE ledgerId = :ledgerId AND deleted = 0 ORDER BY sortOrder ASC")
    fun observeForLedger(ledgerId: String): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM category WHERE id = :id")
    suspend fun byId(id: String): CategoryEntity?

    /**
     * 按名字精确查分类。自动记账用它把商户名对到分类上。
     *
     * `COLLATE NOCASE` 是为英文商户准备的，中文分类名不受影响。
     */
    @Query(
        "SELECT * FROM category WHERE ledgerId = :ledgerId AND kind = :kind " +
            "AND name = :name COLLATE NOCASE LIMIT 1"
    )
    suspend fun byName(ledgerId: String, kind: String, name: String): CategoryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rows: List<CategoryEntity>)

    /** 合并远端快照后写回。不能用 upsertAll：那是 REPLACE，会连带清掉未推送的 pending。 */
    @Update
    suspend fun update(row: CategoryEntity)

    @Query("SELECT * FROM category WHERE hasPending = 1 LIMIT :limit")
    suspend fun pendingBatch(limit: Int = 200): List<CategoryEntity>

    /** 供「首次铺数据后标记待推送」用：需要看到 serverFvJson 才能判断推过没有。 */
    @Query("SELECT * FROM category")
    suspend fun allForPush(): List<CategoryEntity>
}

@Dao
interface AccountDao {
    @Query("SELECT * FROM account WHERE ledgerId = :ledgerId AND deleted = 0 ORDER BY sortOrder ASC")
    fun observeForLedger(ledgerId: String): Flow<List<AccountEntity>>

    @Query("SELECT * FROM account WHERE id = :id")
    suspend fun byId(id: String): AccountEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rows: List<AccountEntity>)

    /** 同 CategoryDao.update：REPLACE 会清掉 pending，只有 @Update 是"改这一行"。 */
    @Update
    suspend fun update(row: AccountEntity)

    @Query("SELECT * FROM account WHERE hasPending = 1 LIMIT :limit")
    suspend fun pendingBatch(limit: Int = 200): List<AccountEntity>

    /** 同 CategoryDao.allForPush。 */
    @Query("SELECT * FROM account")
    suspend fun allForPush(): List<AccountEntity>
}

@Dao
interface LedgerDao {
    @Query("SELECT * FROM ledger WHERE deleted = 0")
    fun observeAll(): Flow<List<LedgerEntity>>

    @Query("SELECT * FROM ledger WHERE deleted = 0 LIMIT 1")
    suspend fun firstActive(): LedgerEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rows: List<LedgerEntity>)

    @Query("SELECT * FROM ledger WHERE hasPending = 1 LIMIT :limit")
    suspend fun pendingBatch(limit: Int = 200): List<LedgerEntity>
}

@Dao
interface SyncStateDao {
    @Query("SELECT * FROM sync_state WHERE id = 1")
    fun observe(): Flow<SyncStateEntity?>

    @Query("SELECT * FROM sync_state WHERE id = 1")
    suspend fun get(): SyncStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(state: SyncStateEntity)
}

@Dao
interface AutoDraftDao {
    @Query("SELECT * FROM auto_draft WHERE id = :id LIMIT 1")
    suspend fun byId(id: String): AutoDraftEntity?

    @Query("SELECT * FROM auto_draft ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<AutoDraftEntity>>

    @Query("SELECT COUNT(*) FROM auto_draft")
    fun observeCount(): Flow<Int>

    @Query("SELECT * FROM auto_draft WHERE fingerprint = :fp ORDER BY createdAt DESC LIMIT 1")
    suspend fun newestByFingerprint(fp: String): AutoDraftEntity?

    /**
     * 跨来源合并的候选查询：同额、同向、时间落在给定区间内。
     *
     * 区间由调用方按 [kapi.core.auto.AutoBook.CROSS_SOURCE_WINDOW_MS] 给出，
     * 这里只负责把候选捞出来——**真正的判定在 core**，那边有单测钉死。
     * 这里若再加一层"看起来像"的判断，就有两份判据了，改一处忘一处。
     */
    @Query(
        "SELECT * FROM auto_draft WHERE amountMinor = :amount AND direction = :dir " +
            "AND occurredAt BETWEEN :from AND :to ORDER BY createdAt DESC LIMIT 8"
    )
    suspend fun nearMisses(amount: Long, dir: String, from: Long, to: Long): List<AutoDraftEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(draft: AutoDraftEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(drafts: List<AutoDraftEntity>)

    @Query("DELETE FROM auto_draft WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM auto_draft WHERE id IN (:ids)")
    suspend fun deleteAll(ids: List<String>)

    /**
     * 清理过期草稿。
     *
     * 指纹去重只在时间窗内有效（见 [AutoBookStore]），所以超过窗口的草稿
     * 和指纹都必须清掉——否则用户上午买杯咖啡、下午又买一杯同样价钱的，
     * 下午那杯会被当成重复而丢掉，**这就是真金白银的丢账**。
     */
    @Query("DELETE FROM auto_draft WHERE createdAt < :before")
    suspend fun purgeOlderThan(before: Long)

    /** 草稿表只增不减的话会一直涨，删完的槽位也需要回收。 */
    @Query("SELECT COUNT(*) FROM auto_draft")
    suspend fun count(): Int
}

@Database(
    entities = [
        TxnEntity::class,
        CategoryEntity::class,
        AccountEntity::class,
        LedgerEntity::class,
        SyncStateEntity::class,
        AutoDraftEntity::class,
    ],
    // **故意保留 1**。旧 schema 文件要留着：上线后想写迁移测试就来不及了，
    // 那时候每条用户手机上都已经有数据，删掉旧版本等于把迁移的参照物一起删了。
    version = 2,
    exportSchema = true,
)
abstract class KapiDatabase : RoomDatabase() {
    abstract fun txnDao(): TxnDao
    abstract fun categoryDao(): CategoryDao
    abstract fun accountDao(): AccountDao
    abstract fun ledgerDao(): LedgerDao
    abstract fun syncStateDao(): SyncStateDao
    abstract fun autoDraftDao(): AutoDraftDao

    companion object {
        const val NAME = "kapi.db"
    }
}
