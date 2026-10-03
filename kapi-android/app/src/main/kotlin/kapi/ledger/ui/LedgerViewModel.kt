package kapi.ledger.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kapi.ledger.data.AccountEntity
import kapi.ledger.data.CategoryEntity
import kapi.ledger.data.LedgerRepository
import kapi.ledger.data.MonthSummary
import kapi.ledger.data.TxnEntity
import kapi.ledger.sync.SyncCoordinator
import kapi.ledger.sync.SyncEngine
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/** 顶部汇总条要显示的全部内容，一次算完。 */
data class LedgerUiState(
    val month: YearMonth = YearMonth.now(),
    val rows: List<TxnEntity> = emptyList(),
    val summary: MonthSummary = MonthSummary(),
    val pendingCount: Int = 0,
    val isPaired: Boolean = false,
    val lastSyncAt: Long = 0L,
    val lastError: String? = null,
    val loaded: Boolean = false,
)

@OptIn(ExperimentalCoroutinesApi::class)
class LedgerViewModel(
    private val repo: LedgerRepository,
    private val sync: SyncCoordinator,
) : ViewModel() {

    private val month = MutableStateFlow(YearMonth.now())

    /**
     * 配对/断开/同步之后要手动戳一下 UI。
     *
     * `isPaired` 存在 SharedPreferences 里，不是 Flow，所以它自己不会通知界面。
     * 少了这个计数器，用户配完 NAS 看到的还是"未连接"，会以为没成功而反复配。
     */
    private val refreshTick = MutableStateFlow(0L)

    private val monthFlow = month.flatMapLatest { m ->
        combine(repo.observeMonth(m), repo.observeSummary(m)) { rows, sum ->
            LedgerUiState(month = m, rows = rows, summary = sum, loaded = true)
        }
    }

    val state: StateFlow<LedgerUiState> = combine(
        monthFlow,
        repo.observePendingCount(),
        repo.observeSyncState(),
        refreshTick,
    ) { base, pending, syncState, _ ->
        base.copy(
            pendingCount = pending,
            isPaired = sync.isPaired,
            lastSyncAt = syncState?.lastSyncAt ?: 0L,
            lastError = syncState?.lastError,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LedgerUiState())

    val categories: StateFlow<List<CategoryEntity>> = repo
        .observeCategories(LedgerRepository.DEFAULT_LEDGER_ID)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val accounts: StateFlow<List<AccountEntity>> = repo
        .observeAccounts(LedgerRepository.DEFAULT_LEDGER_ID)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // ---- 操作 ----

    fun shiftMonth(delta: Long) {
        month.value = month.value.plusMonths(delta)
    }

    fun goToCurrentMonth() {
        month.value = YearMonth.now()
    }

    /**
     * 记一笔。
     *
     * 返回 true 表示已经落库。**落库先于同步**：用户按完保存就该看见这行，
     * 同步是后台的事，断网也不该让用户觉得自己没记上。
     */
    suspend fun saveTransaction(
        amountMinor: Long,
        isExpense: Boolean,
        accountId: String,
        categoryId: String?,
        note: String?,
        merchant: String?,
        occurredAt: Long,
        tags: List<String> = emptyList(),
    ): String? {
        val ledgerId = repo.ensureSeeded()
        val txn = repo.editor.createTransaction(
            amountMinor = amountMinor,
            direction = if (isExpense) "EXPENSE" else "INCOME",
            accountId = accountId.ifBlank { DEFAULT_ACCOUNT_ID },
            occurredAt = occurredAt,
            ledgerId = ledgerId,
            deviceId = sync.deviceId,
            note = note,
            merchant = merchant,
            categoryId = categoryId,
        )
        if (tags.isNotEmpty()) repo.setTxnTags(txn.id, tags)
        return txn.id
    }

    suspend fun deleteTransaction(id: String) {
        repo.editor.softDelete(id, sync.deviceId)
    }

    /**
     * 改一笔账的分类。
     *
     * **这是自学习唯一的学习信号。** 自动分类判错时用户在这里改一次，
     * 同一个商户改够三次（[kapi.core.auto.MerchantLearning.MIN_OCCURRENCES]），
     * 之后这个商户的账就会自动按他选的那一档归。
     *
     * 走 [LocalEditWriter.editField] 而不是直接 UPDATE：分类是要同步到 NAS 的
     * 业务字段，直接改本地行的话，推送时服务端那一列还是旧的，
     * 换台手机拉下来就又变回去了。
     *
     * 只支持**设置**，不支持清空。`category_id` 为空时这个键在同步行里整个不存在，
     * 而合并层会用原值兜底——想清空得动同步层的编码约定，
     * 那个代价不值得为一个罕见操作付。
     */
    suspend fun setCategory(txnId: String, categoryId: String): Boolean =
        repo.editor.editField(txnId, "category_id", "\"$categoryId\"", sync.deviceId)

    /**
     * 新建一个用户自定义分类，返回它的 id；失败返回 null。
     *
     * 落在 [LedgerRepository] 上而不是这里直接 `repo.db`——db 是它的私有字段，
     * 而"建分类"本来就是账本初始化的一部分。
     */
    suspend fun createCategory(
        name: String,
        kind: String,
        parentId: String?,
        icon: String? = null,
    ): String? = repo.createCategory(name, kind, parentId, icon)

    /** 用户分类规则（设置 → 分类规则 编辑用）。 */
    fun observeCategoryRules() = repo.observeCategoryRules(LedgerRepository.DEFAULT_LEDGER_ID)

    suspend fun addCategoryRule(kind: String, pattern: String, categoryId: String): String? =
        repo.upsertCategoryRule(kind, pattern, categoryId)

    suspend fun deleteCategoryRule(id: String) = repo.deleteCategoryRule(id)

    suspend fun updateCategoryIcon(categoryId: String, icon: String?) =
        repo.updateCategoryIcon(categoryId, icon)

    suspend fun allTags(): List<String> = repo.allTags()

    suspend fun setTxnTags(txnId: String, tags: List<String>) = repo.setTxnTags(txnId, tags)

    fun observeTagMap() = repo.observeTxnTags()

    suspend fun pair(baseUrl: String, code: String): SyncCoordinator.PairOutcome {
        val outcome = sync.pair(baseUrl, code)
        // 配完立刻推一次。用户刚配好就点开 App 看到空列表，是最伤的体验。
        if (outcome is SyncCoordinator.PairOutcome.Paired) {
            runCatching { sync.syncNow() }
        }
        refreshTick.value++
        return outcome
    }

    suspend fun syncNow(): SyncEngine.SyncResult {
        val r = sync.syncNow()
        refreshTick.value++
        return r
    }

    fun disconnect() {
        sync.disconnect()
        refreshTick.value++
    }

    fun todayStartMillis(): Long =
        LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    companion object {
        const val DEFAULT_ACCOUNT_ID = "A1"
    }
}

/**
 * 手工 ViewModel 工厂。这个 App 的依赖图就三五个节点，Hilt 换不来什么。
 */
class LedgerViewModelFactory(
    private val repo: LedgerRepository,
    private val sync: SyncCoordinator,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(LedgerViewModel::class.java)) {
            "不认识这个 ViewModel: ${modelClass.name}"
        }
        return LedgerViewModel(repo, sync) as T
    }
}
