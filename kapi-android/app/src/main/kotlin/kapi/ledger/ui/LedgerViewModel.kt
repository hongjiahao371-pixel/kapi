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
    ): Boolean {
        val ledgerId = repo.ensureSeeded()
        repo.editor.createTransaction(
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
        return true
    }

    suspend fun deleteTransaction(id: String) {
        repo.editor.softDelete(id, sync.deviceId)
    }

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
