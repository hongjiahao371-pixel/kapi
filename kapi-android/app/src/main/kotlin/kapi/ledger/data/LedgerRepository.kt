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

    /**
     * 内容判重的读侧透传：同商户 + 同金额 + 同方向 + 同一发生日。
     *
     * 读屏管线用同一张 DAO 查询拦自动重复；这里给**手动路径**用——截图
     * 识别回填表单后，保存前查一下"账本里是不是已经有这笔"，命中就弹
     * 确认而不是静默记两笔（2026-10-03 用户实测报的第二条）。商户空或
     * 时间缺失（识别不出）时不判——没有锚点的判重全是误伤。
     */
    suspend fun findDuplicateTxn(
        merchant: String,
        amountMinor: Long,
        direction: String,
        occurredAt: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): TxnEntity? {
        if (merchant.isBlank() || occurredAt <= 0) return null
        val day = java.time.Instant.ofEpochMilli(occurredAt).atZone(zone).toLocalDate()
        val from = day.atStartOfDay(zone).toInstant().toEpochMilli()
        val to = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return db.txnDao().sameMerchantAmountDay(merchant.trim(), amountMinor, direction, from, to)
    }

    fun observeSummary(
        month: YearMonth,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Flow<MonthSummary> = observeMonth(month, zone).map { rows ->
        var expense = 0L
        var income = 0L
        for (r in rows) {
            // 自转账（toAccountId 非空）不是收支：钱只是在自己口袋之间挪。
            // postSelfTransfer 的 KDoc 从第一天就承诺"收支统计会把
            // toAccountId 非空的行排除"——实现一直漏了这行过滤，余额宝
            // 申购 12100 之类会把月支出顶成天文数字（2026-10-03 真机实见）。
            if (r.toAccountId != null) continue
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
    suspend fun createCategory(
        name: String,
        kind: String,
        parentId: String?,
        icon: String? = null,
    ): String? {
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
                    icon = icon,
                    kind = kind,
                    // 排在用户自建的后面：内置分类是骨架，自定义的是补充。
                    sortOrder = 99,
                )
            )
        )
        return id
    }

    // ------------------------------------------------------------ 用户分类规则

    /** 全部用户规则（按创建序）。 */
    fun observeCategoryRules(ledgerId: String): Flow<List<CategoryRuleEntity>> =
        db.categoryRuleDao().observeForLedger(ledgerId)

    suspend fun categoryRules(ledgerId: String): List<CategoryRuleEntity> =
        db.categoryRuleDao().allForLedger(ledgerId)

    /** 新建/更新一条规则。pattern 剥空白，空串拒绝。 */
    suspend fun upsertCategoryRule(kind: String, pattern: String, categoryId: String): String? {
        val p = pattern.trim()
        if (p.isEmpty()) return null
        val id = "rule-" + java.util.UUID.randomUUID().toString().take(8)
        db.categoryRuleDao().upsert(
            CategoryRuleEntity(
                id = id,
                ledgerId = DEFAULT_LEDGER_ID,
                kind = kind,
                pattern = p,
                categoryId = categoryId,
                createdAt = System.currentTimeMillis(),
            ),
        )
        return id
    }

    suspend fun deleteCategoryRule(id: String) = db.categoryRuleDao().delete(id)

    /**
     * 改分类图标（含给老自建分类补图标）。
     *
     * icon 在 category 的同步白名单里（[businessValues] 有它），所以这里
     * **必须走 LocalMerge** 打 pending 戳——直接 update(row.copy(icon=…))
     * 会让这台设备和 NAS 的 icon 永远各改各的。
     */
    suspend fun updateCategoryIcon(categoryId: String, icon: String?) {
        val row = db.categoryDao().byId(categoryId) ?: return
        val clock = LamportClock(db.syncStateDao().get()?.lamportCounter ?: 0L)
        var r = row.toSyncedRow()
        r = LocalMerge.applyLocalEdit(
            r, "icon", icon ?: "",
            clock, SEED_ORIGIN, "local-edit:category/$categoryId/icon",
        )
        db.categoryDao().update(r.toCategory(row))
        val st = db.syncStateDao().get() ?: SyncStateEntity()
        db.syncStateDao().put(st.copy(lamportCounter = maxOf(st.lamportCounter, clock.current)))
    }

    // ------------------------------------------------------------ 标签（设备本地）

    /** 重写一笔的标签。tag 里的逗号剥掉（展示用 join(", ")）。 */
    suspend fun setTxnTags(txnId: String, tags: List<String>) {
        db.txnTagDao().setTags(
            txnId,
            tags.map { it.trim().replace(",", "").replace("，", "") }
                .filter { it.isNotEmpty() }
                .distinct(),
        )
    }

    /** 全部标签（去重排序），录入时的建议源。 */
    suspend fun allTags(): List<String> = db.txnTagDao().allTags()

    /** 全部标签行（账本页拼 txnId→tags 用）。 */
    fun observeTxnTags() = db.txnTagDao().observeAll()

    /**
     * 清空全部账单（2026-10-04 新增）：软删所有存活账单（带同步标记，
     * 删除会照常同步到 NAS/其他设备），并清空自动记账草稿。
     * 返回删掉的笔数。账户/分类/预算/设置不动。
     */
    /** 存活账单 id 列表（清空全部用）。 */
    suspend fun aliveTxnIds(): List<String> = db.txnDao().aliveIds()

    /** 清空自动记账草稿（清空账单时一并清）。 */
    suspend fun clearAutoDrafts() = db.autoDraftDao().deleteOlder(0)

    suspend fun clearAllBills(deviceId: String): Int {
        val ids = db.txnDao().aliveIds()
        for (id in ids) editor.softDelete(id, deviceId)
        db.autoDraftDao().deleteOlder(0)  // 清空时草稿一并清（0=全部）
        return ids.size
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

        // ---- 细分类（2026-10-01；2026-10-03 重修 id 方案）----
        //
        // 顶级分类只有 11 个，`餐饮` 底下什么都不分——可包子铺和火锅在用户的生活里
        // 根本不是一回事，`购物` 更极端：一件 T 恤和一台显示器放在同一栏，
        // 那栏数字没有任何意义。
        //
        // 顶级分类的 id 和名字**一个字都不改**：已有的库里已经有 `cat-0`…`cat-10`，
        // 改名会让用户的历史账目指向不存在的分类。
        //
        // **细类 id 为什么是 `cat-m-<顶级序号>-<细类名>`**：原来是位置序号
        // `cat-<i>-<j>`，规则表里一插队（烘焙、零食都是后来插进餐饮中间的），
        // j 位置整体后移，存量库里 j 位被旧名字占住——烘焙静默漏铺、「正餐」
        // 被重复建了三行（2026-10-03 OPPO 真机实证）。名字派生的 id 不随顺序漂移。
        //
        // 细分类清单不写在这里，而是从 [CategoryRules] 取——**规则表是唯一的权威**。
        // 两处各写一份的话，加了规则忘了铺库、或铺了库没有规则，两边都会对不上。
        val expenseRows = db.categoryDao().allForPush().filter { it.kind == "EXPENSE" && !it.deleted }
        val existingTopNames = expenseRows.filter { it.parentId == null }.map { it.name }.toSet()
        val existingMinorNames = expenseRows.filter { it.parentId != null }.map { it.name }.toSet()

        val missingCategories = (DEFAULT_EXPENSE_CATEGORIES.mapIndexed { i, n ->
            CategoryEntity("cat-$i", ledgerId, null, n, kind = "EXPENSE", sortOrder = i)
        } + DEFAULT_INCOME_CATEGORIES.mapIndexed { i, n ->
            CategoryEntity("inc-$i", ledgerId, null, n, kind = "INCOME", sortOrder = i)
        }).filter { db.categoryDao().byId(it.id) == null && it.name !in existingTopNames }
        if (missingCategories.isNotEmpty()) db.categoryDao().upsertAll(missingCategories)

        val subCategories = DEFAULT_EXPENSE_CATEGORIES.flatMapIndexed { i, major ->
            CategoryRules.minors(major).mapIndexed { j, minor ->
                CategoryEntity(
                    id = "cat-m-$i-$minor",
                    ledgerId = ledgerId,
                    parentId = "cat-$i",
                    name = minor,
                    kind = "EXPENSE",
                    sortOrder = j,
                )
            }
        }.filter { db.categoryDao().byId(it.id) == null && it.name !in existingMinorNames }
        if (subCategories.isNotEmpty()) db.categoryDao().upsertAll(subCategories)

        // ---- 历史重复行合并（2026-10-03，一次性，幂等）----
        // 位置 id 时代的 bug 在设备上留下了同名细类多行（「正餐」×3）。收敛到
        // id 最小的一行：账目改指向，重复行**改名**再软删——byName 不滤 deleted
        // （AutoCategory 在用，不能顺手改语义），不改名软删行会被 LIMIT 1 查回来。
        // 跑完再跑无重复组，天然幂等。
        for ((_, dups) in expenseRows.filter { it.parentId != null }.groupBy { it.parentId to it.name }) {
            if (dups.size < 2) continue
            val keep = dups.minByOrNull { it.id }!!
            for (dup in dups.filter { it.id != keep.id }) {
                db.txnDao().repointCategory(dup.id, keep.id)
                db.categoryDao().update(dup.copy(name = dup.name + "·重复", deleted = true))
            }
        }

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
            // ---- 2026-10-02 竞品对齐追加（钱迹一级分类清单）----
            // **只增不改**：cat-0…cat-10 的 id 和名字一字不动（改了会打断已有库的
            // 历史账目），新的追加在 cat-11 起。ensureSeeded 幂等，存量设备下次
            // 启动自动补行，新分类随参考数据推给 NAS。旧「娱乐/旅行」「购物/宠物」
            // 的行保留——历史账目还指着它们，只是规则不再往那里判新账。
            "育儿", "旅行", "宠物",
        )

        private val DEFAULT_INCOME_CATEGORIES = listOf(
            "工资", "奖金", "兼职", "理财", "报销", "其他",
        )
    }
}
