package kapi.ledger.data

import kapi.core.auto.CategoryRules
import kapi.core.sync.LamportClock
import kapi.core.sync.LocalMerge
import kapi.ledger.sync.LocalEditWriter
import kapi.ledger.sync.businessValues
import kapi.ledger.sync.toAccount
import kapi.ledger.sync.toCategory
import kapi.ledger.sync.toSyncedRow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.ZoneId
import java.time.YearMonth

/** 一个月的收支汇总。金额一律是分。 */
data class MonthSummary(
    val expenseMinor: Long = 0,
    val incomeMinor: Long = 0,
) {
    val balanceMinor: Long get() = incomeMinor - expenseMinor
}

/**
 * 账本读模型。
 *
 * 只做"查"和"算"，写一律走 [LocalEditWriter]——因为 pending 的编码格式只有一个
 * 正确版本，散开写迟早会有一处对不上，而对不上的表现是静默丢字段。
 */
class LedgerRepository(
    private val db: KapiDatabase,
    val editor: LocalEditWriter,
) {

    fun observeMonth(
        month: YearMonth,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Flow<List<TxnEntity>> {
        val from = month.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val to = month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return db.txnDao().observeRange(from, to)
    }

    fun observeSummary(
        month: YearMonth,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Flow<MonthSummary> = observeMonth(month, zone).map { rows ->
        var expense = 0L
        var income = 0L
        for (r in rows) {
            when (r.direction) {
                "INCOME" -> income += r.amountMinor
                "EXPENSE" -> expense += r.amountMinor
                // UNKNOWN 不计入任何一侧。硬塞进"支出"会让汇总凭空多一笔，
                // 塞进"收入"更糟——用户会以为自己赚了。
                else -> Unit
            }
        }
        MonthSummary(expense, income)
    }

    fun observeCategories(ledgerId: String): Flow<List<CategoryEntity>> =
        db.categoryDao().observeForLedger(ledgerId)

    fun observeAccounts(ledgerId: String): Flow<List<AccountEntity>> =
        db.accountDao().observeForLedger(ledgerId)

    fun observeSyncState(): Flow<SyncStateEntity?> = db.syncStateDao().observe()

    fun observePendingCount(): Flow<Int> = db.txnDao().observePendingCount()

    suspend fun defaultLedgerId(): String {
        val existing = db.ledgerDao().firstActive()
        if (existing != null) return existing.id
        val seeded = ensureSeeded()
        return seeded
    }

    /**
     * 首次启动时铺一套最小可用的账本、账户和分类。
     *
     * 没有它，"记一笔"页是空的——用户看到零个分类、零个账户，第一反应是 App 坏了，
     * 而不是"我得先去设置里建一个"。可用的默认值比"正确的空状态"重要。
     *
     * 幂等：已存在就不动。v1 只有单账本，共享账本留到后面。
     */
    /**
     * 新建一个用户自定义分类，返回它的 id；失败或重名时返回已存在那行的 id / null。
     *
     * 分类是**用户的数据**，不是代码里的常量：他会想把「猫主子」建在「其他」下面。
     * 规则表（[CategoryRules]）只管自动猜，这里管他说了算。
     *
     * id 用 `cat-u-` + 随机后缀而不是序号——分类会同步到 NAS，而两台手机各自建一个
     * 「猫主子」应该变成两行，不该互相覆盖。
     *
     * 同名同级不重复建：用户连点两次"保存"不该得到两个一样的分类，
     * 而那会让分类选择器里出现两颗一模一样的 chip。
     */
    suspend fun createCategory(name: String, kind: String, parentId: String?): String? {
        val clean = name.trim().take(20)
        if (clean.isEmpty()) return null
        val ledgerId = ensureSeeded()
        db.categoryDao().byName(ledgerId, kind, clean)?.let { return it.id }
        val id = "cat-u-" + java.util.UUID.randomUUID().toString().take(8)
        db.categoryDao().upsertAll(
            listOf(
                CategoryEntity(
                    id = id,
                    ledgerId = ledgerId,
                    parentId = parentId,
                    name = clean,
                    kind = kind,
                    // 排在用户自建的后面：内置分类是骨架，自定义的是补充。
                    sortOrder = 99,
                )
            )
        )
        return id
    }

    suspend fun ensureSeeded(): String {
        val ledgerId = DEFAULT_LEDGER_ID

        if (db.ledgerDao().firstActive() == null) {
            db.ledgerDao().upsertAll(listOf(LedgerEntity(id = ledgerId, name = "我的账本")))
        }

        val missingAccounts = DEFAULT_ACCOUNTS
            .map { (id, name, kind) -> AccountEntity(id = id, ledgerId = ledgerId, name = name, kind = kind) }
            .filter { db.accountDao().byId(it.id) == null }
        if (missingAccounts.isNotEmpty()) db.accountDao().upsertAll(missingAccounts)

        val missingCategories = (DEFAULT_EXPENSE_CATEGORIES.mapIndexed { i, n ->
            CategoryEntity("cat-$i", ledgerId, null, n, kind = "EXPENSE", sortOrder = i)
        } + DEFAULT_INCOME_CATEGORIES.mapIndexed { i, n ->
            CategoryEntity("inc-$i", ledgerId, null, n, kind = "INCOME", sortOrder = i)
        }).filter { db.categoryDao().byId(it.id) == null }
        if (missingCategories.isNotEmpty()) db.categoryDao().upsertAll(missingCategories)

        // ---- 细分类（2026-10-01）----
        //
        // 顶级分类只有 11 个，`餐饮` 底下什么都不分——可包子铺和火锅在用户的生活里
        // 根本不是一回事，`购物` 更极端：一件 T 恤和一台显示器放在同一栏，
        // 那栏数字没有任何意义。
        //
        // 顶级分类的 id 和名字**一个字都不改**：已有的库里已经有 `cat-0`…`cat-10`，
        // 改名会让用户的历史账目指向不存在的分类。细分类是新行，用
        // `cat-<顶级序号>-<细类序号>`，靠 `parentId` 串起来。
        //
        // 细分类清单不写在这里，而是从 [CategoryRules] 取——**规则表是唯一的权威**。
        // 两处各写一份的话，加了规则忘了铺库、或铺了库没有规则，两边都会对不上。
        val subCategories = DEFAULT_EXPENSE_CATEGORIES.flatMapIndexed { i, major ->
            CategoryRules.minors(major).mapIndexed { j, minor ->
                CategoryEntity(
                    id = "cat-$i-$j",
                    ledgerId = ledgerId,
                    parentId = "cat-$i",
                    name = minor,
                    kind = "EXPENSE",
                    sortOrder = j,
                )
            }
        }.filter { db.categoryDao().byId(it.id) == null }
        if (subCategories.isNotEmpty()) db.categoryDao().upsertAll(subCategories)

        // 上面铺的这套分类/账户要**推上去**，否则 NAS 上永远没有参考数据：
        // 换一台手机连过来，拉到的账目里的 category_id 指向一个不存在的行，
        // 整列显示「未分类」。而服务器上什么都没有，是查不出原因的。
        markReferenceDataPending()

        return ledgerId
    }

    /**
     * 把"从没推给过服务端"的分类/账户标成待推送。
     *
     * 判据是 `serverFvJson == "{}"`——**推送过一次**的行戳就不再是空的，
     * 所以不会每��同步都重推一遍。这是幂等的：跑十次和跑一次结果一样。
     *
     * 必须走 [LocalMerge.applyLocalEdit] 而不是自己拼 pendingJson：pending 的编码
     * 格式只有一处权威实现，这里手拼的字段少一个戳，那个字段在服务端就永远停在
     * 旧值——和金额曾经静默变 0 是同一类事故。
     */
    private suspend fun markReferenceDataPending() {
        val clock = LamportClock(db.syncStateDao().get()?.lamportCounter ?: 0L)
        var dirty = false

        for (row in db.categoryDao().allForPush().filter { it.serverFvJson == "{}" && !it.deleted }) {
            var r = row.toSyncedRow()
            for ((field, value) in row.businessValues()) {
                r = LocalMerge.applyLocalEdit(r, field, value, clock, SEED_ORIGIN, "")
            }
            db.categoryDao().update(r.toCategory(row))
            dirty = true
        }
        for (row in db.accountDao().allForPush().filter { it.serverFvJson == "{}" && !it.deleted }) {
            var r = row.toSyncedRow()
            for ((field, value) in row.businessValues()) {
                r = LocalMerge.applyLocalEdit(r, field, value, clock, SEED_ORIGIN, "")
            }
            db.accountDao().update(r.toAccount(row))
            dirty = true
        }
        if (dirty) {
            val s = db.syncStateDao().get() ?: SyncStateEntity()
            db.syncStateDao().put(s.copy(lamportCounter = maxOf(s.lamportCounter, clock.current)))
        }
    }

    companion object {
        const val DEFAULT_LEDGER_ID = "L1"

        /**
         * 首次铺数据时用的 origin 标记。
         *
         * 这时候往往还没配对，拿不到真正的 deviceId。用一个固定的本地标记：
         * 服务端只用 origin 做字段版本比较，而这套默认值在每台设备上完全相同，
         * 谁先到谁生效，结果都是同一份数据——不构成冲突。
         */
        private const val SEED_ORIGIN = "local-seed"

        /** (id, 显示名, 账户类型) */
        private val DEFAULT_ACCOUNTS = listOf(
            Triple("A1", "现金", "CASH"),
            Triple("A2", "银行卡", "DEBIT"),
            Triple("A3", "支付宝", "DEBIT"),
            Triple("A4", "微信", "DEBIT"),
            // ---- 平台子钱包（2026-10-02）----
            // 自转账要真实入账，两端必须对应到 account 表里的**不同行**——
            // 只有 A3/A4 时「账户余额→余额宝」两端都落到支付宝，只能记日志。
            // id 与 core 的 [kapi.core.auto.SubWallet] 目录逐字一致（那边有
            // 对照测试钉着）；ensureSeeded 幂等，存量设备下次启动自动补行。
            Triple("A5", "余额宝", "INVESTMENT"),
            Triple("A6", "零钱通", "INVESTMENT"),
            Triple("A7", "零钱", "DEBIT"),
            Triple("A8", "存钱罐", "INVESTMENT"),
            Triple("A9", "账户余额", "DEBIT"),
            Triple("A10", "花呗", "CREDIT"),
        )

        private val DEFAULT_EXPENSE_CATEGORIES = listOf(
            "餐饮", "交通", "购物", "居住", "娱乐", "医疗", "教育", "通讯", "日用", "人情", "其他",
        )

        private val DEFAULT_INCOME_CATEGORIES = listOf(
            "工资", "奖金", "兼职", "理财", "报销", "其他",
        )
    }
}
