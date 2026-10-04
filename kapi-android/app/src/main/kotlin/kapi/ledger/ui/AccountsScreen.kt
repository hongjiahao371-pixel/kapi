package kapi.ledger.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kapi.core.llm.Money
import kapi.ledger.AppContainer
import kapi.ledger.data.AccountEntity
import kapi.ledger.data.KapiDatabase
import kapi.ledger.data.LedgerRepository
import kapi.ledger.sync.LocalEditWriter
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 账户管理：账户行 + 手动划转。
 *
 * ## 为什么它出现（2026-10-02）
 *
 * 自动记账的子钱包入账（[kapi.core.auto.SubWallet]）上线后，账户表第一次
 * 有了用户需要关心的"非种子"行——余额宝、花呗这些既是自动划转的两端，
 * 也是花呗消费的落点。有了行就得有管理：**归档**一个不用的子钱包，
 * 自动记账会尊重（[kapi.ledger.auto.AutoCapturePipeline.resolveWalletAccount]
 * 对 deleted 行返回 null，回退记日志），**新增**银行卡/子钱包则让
 * 「还款到 招商银行(9590)」这类两端将来能对上。
 *
 * ## 同步戳的诚实声明
 *
 * 新增/改名/归档走的是 DAO 直写（与种子行同一套默认戳），**没有**
 * 走 LocalMerge 的逐字段 pending 戳——单机使用完全正确；多设备同步时
 * 这些账户行的字段级冲突合并会退化为整行覆盖。要严谨化时把这三个方法
 * 挪进 LocalEditWriter 并补戳，界面不用动。
 */
class AccountsViewModel(
    private val db: KapiDatabase,
    private val editor: LocalEditWriter,
    private val deviceId: () -> String,
) : ViewModel() {

    val accounts: StateFlow<List<AccountEntity>> =
        db.accountDao().observeForLedger(LedgerRepository.DEFAULT_LEDGER_ID)
            .map { rows -> rows.filter { !it.deleted }.sortedBy { it.sortOrder } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 新增账户。id 让库自增语义由名字派生——重名直接拒绝，宁可报错不静默合并。 */
    fun add(name: String, kind: String, onDone: (String?) -> Unit) {
        val n = name.trim()
        if (n.isEmpty()) return onDone("名字不能为空")
        viewModelScope.launch {
            if (accounts.value.any { it.name == n }) return@launch onDone("已有同名账户")
            val id = "U-${n.hashCode().toUInt().toString(16)}"
            db.accountDao().upsertAll(
                listOf(
                    AccountEntity(
                        id = id,
                        ledgerId = LedgerRepository.DEFAULT_LEDGER_ID,
                        name = n,
                        kind = kind,
                        // 排在种子的后面：种子是骨架，自定义的是补充。
                        sortOrder = 200 + accounts.value.size,
                    ),
                ),
            )
            onDone(null)
        }
    }

    fun rename(id: String, name: String, onDone: (String?) -> Unit) {
        val n = name.trim()
        if (n.isEmpty()) return onDone("名字不能为空")
        viewModelScope.launch {
            val row = db.accountDao().byId(id) ?: return@launch onDone("账户不存在")
            if (accounts.value.any { it.name == n && it.id != id }) {
                return@launch onDone("已有同名账户")
            }
            db.accountDao().update(row.copy(name = n))
            onDone(null)
        }
    }

    /**
     * 归档（软删）。
     *
     * 核心四账户（现金/银行卡/支付宝/微信）**不允许归档**：它们是自动记账
     * 的骨架——管线按来源 App 把消费落到这四行上，归档任何一行都会让一类
     * 消费落空。
     *
     * 子钱包（余额宝/零钱通/零钱/存钱罐/账户余额/花呗，A5…A10）**明确支持
     * 归档**：管线按 [kapi.core.auto.SubWallet] 的读屏词找到账户行后会尊重
     * deleted（见类 KDoc）——归档「零钱通」只意味着微信里零钱通↔零钱的
     * 转入转出不再自动记，其它一切照旧。这正是 2026-10-04 用户反馈
     * "账户太多还不能删"的解法：用不到的子钱包随时删，需要时 NAS/App
     * 重新安装或手动重建同名账户即可恢复。
     */
    fun archive(id: String, onDone: (String?) -> Unit) {
        viewModelScope.launch {
            if (id in CORE_ACCOUNT_IDS) {
                return@launch onDone("现金/银行卡/支付宝/微信是自动记账的骨架，不能删除")
            }
            val row = db.accountDao().byId(id) ?: return@launch onDone("账户不存在")
            db.accountDao().update(row.copy(deleted = true))
            onDone(null)
        }
    }

    /**
     * 手动划转：钱从 [fromId] 搬到 [toId]。
     *
     * direction 记 EXPENSE 与自动划转同款——收支统计会排除 toAccountId
     * 非空的行，余额由 accountId → toAccountId 表达。
     */
    fun transfer(fromId: String, toId: String, amountText: String, note: String?, onDone: (String?) -> Unit) {
        val minor = Money.parse(amountText)?.minor ?: return onDone("金额看不懂")
        if (minor <= 0) return onDone("金额要大于 0")
        if (fromId == toId) return onDone("两端不能是同一个账户")
        viewModelScope.launch {
            editor.createTransaction(
                amountMinor = minor,
                direction = "EXPENSE",
                accountId = fromId,
                toAccountId = toId,
                occurredAt = System.currentTimeMillis(),
                ledgerId = LedgerRepository.DEFAULT_LEDGER_ID,
                deviceId = deviceId(),
                note = note?.trim()?.takeIf { it.isNotEmpty() },
            )
            onDone(null)
        }
    }

    private companion object {
        /**
         * 不可删除的核心账户：自动记账按来源 App 固定落到这四行
         * （微信消费→微信、扫码/转账→银行卡…），删任何一行都会让一类
         * 支付落空。子钱包 A5…A10 不在此列——归档是明确支持的。
         */
        val CORE_ACCOUNT_IDS = setOf("A1", "A2", "A3", "A4")
    }
}

class AccountsViewModelFactory(
    private val db: KapiDatabase,
    private val editor: LocalEditWriter,
    private val deviceId: () -> String,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        AccountsViewModel(db, editor, deviceId) as T
}

/** 账户类型的展示名。 */
private val KIND_LABELS = linkedMapOf(
    "DEBIT" to "借记",
    "CREDIT" to "信用",
    "INVESTMENT" to "理财",
    "CASH" to "现金",
    "LOAN" to "贷款",
)

@Composable
fun AccountsScreen(
    container: AppContainer,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val factory = remember(container) {
        AccountsViewModelFactory(container.db, container.editor) { container.tokenStore.deviceId }
    }
    val vm: AccountsViewModel = viewModel(factory = factory)
    val accounts by vm.accounts.collectAsState()

    var showAdd by remember { mutableStateOf(false) }
    var showTransfer by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<AccountEntity?>(null) }
    var archiving by remember { mutableStateOf<AccountEntity?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    val contentPadding = PaddingValues(top = 48.dp, bottom = 32.dp)
    Column(modifier = modifier.fillMaxSize().padding(contentPadding).padding(horizontal = 20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onDone) { Text("完成") }
            Text(
                "账户管理",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center,
            )
            // 占位平衡标题居中
            Spacer(Modifier.width(64.dp))
        }
        Spacer(Modifier.height(8.dp))

        LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    ActionChip(Modifier.weight(1f), "记一笔划转", Icons.Filled.SwapHoriz) { showTransfer = true }
                    ActionChip(Modifier.weight(1f), "新增账户", Icons.Filled.Add) { showAdd = true }
                }
            }
            items(accounts.size) { i ->
                val a = accounts[i]
                AccountRow(
                    name = a.name,
                    kindLabel = KIND_LABELS[a.kind] ?: a.kind,
                    balance = Money.toYuanDisplay(a.balanceMinor),
                    deletable = a.id !in listOf("A1", "A2", "A3", "A4"),
                    onRename = { renaming = a },
                    onArchive = { archiving = a },
                )
            }
            item {
                Text(
                    "子钱包（余额宝/花呗/零钱…）由自动记账使用：归档后，对应的两端划转会回到「只记日志」。" +
                        "银行卡类账户手动新增后，将来还款类自动记账可以精确对账。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }

    if (showAdd) {
        AccountEditDialog(
            title = "新增账户",
            initialName = "",
            onDismiss = { showAdd = false },
            onSave = { name, kind ->
                vm.add(name, kind) { err ->
                    message = err
                    if (err == null) showAdd = false
                }
            },
            showKindPicker = true,
        )
    }
    renaming?.let { account ->
        AccountEditDialog(
            title = "重命名账户",
            initialName = account.name,
            onDismiss = { renaming = null },
            onSave = { name, _ ->
                vm.rename(account.id, name) { err ->
                    message = err
                    if (err == null) renaming = null
                }
            },
            showKindPicker = false,
        )
    }
    archiving?.let { account ->
        AlertDialog(
            onDismissRequest = { archiving = null },
            title = { Text("删除「${account.name}」？") },
            text = {
                Text(
                    if (account.id in listOf("A5", "A6", "A7", "A8", "A9", "A10"))
                        "历史账目保留，账户从选择列表消失。注意：无障碍自动记账里「${account.name}」相关的转入转出也会停止自动记录。"
                    else
                        "历史账目保留，只是这个账户不再出现在选择列表里。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.archive(account.id) { err ->
                        message = err
                        archiving = null
                    }
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { archiving = null }) { Text("取消") } },
        )
    }
    if (showTransfer) {
        TransferDialog(
            accounts = accounts,
            onDismiss = { showTransfer = false },
            onSave = { from, to, amount, note ->
                vm.transfer(from, to, amount, note) { err ->
                    message = err
                    if (err == null) showTransfer = false
                }
            },
        )
    }
    message?.let { msg ->
        AlertDialog(
            onDismissRequest = { message = null },
            title = { Text("提示") },
            text = { Text(msg) },
            confirmButton = { TextButton(onClick = { message = null }) { Text("好") } },
        )
    }
}

@Composable
private fun ActionChip(modifier: Modifier, label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = modifier
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun AccountRow(
    name: String,
    kindLabel: String,
    balance: String,
    deletable: Boolean,
    onRename: () -> Unit,
    onArchive: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Box(
            Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.AccountBalanceWallet,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.titleSmall)
            Text("$kindLabel · 余额 $balance", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
        }
        TextButton(onClick = onRename) { Text("改名") }
        // 核心四账户（现金/银行卡/支付宝/微信）是自动记账的骨架，不给删除按钮
        // ——逻辑层虽然也会拒绝，但按钮点了才报错比不出现更让人烦。
        if (deletable) {
            TextButton(onClick = onArchive) { Text("删除", color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
private fun AccountEditDialog(
    title: String,
    initialName: String,
    showKindPicker: Boolean,
    onDismiss: () -> Unit,
    onSave: (name: String, kind: String) -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    var kind by remember { mutableStateOf("DEBIT") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("账户名") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (showKindPicker) {
                    Spacer(Modifier.height(12.dp))
                    Text("类型", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(6.dp))
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        KIND_LABELS.entries.forEachIndexed { i, (k, label) ->
                            SegmentedButton(
                                selected = kind == k,
                                onClick = { kind = k },
                                shape = SegmentedButtonDefaults.itemShape(index = i, count = KIND_LABELS.size),
                            ) { Text(label, maxLines = 1) }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(name, kind) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun TransferDialog(
    accounts: List<AccountEntity>,
    onDismiss: () -> Unit,
    onSave: (fromId: String, toId: String, amount: String, note: String?) -> Unit,
) {
    var from by remember { mutableStateOf(accounts.firstOrNull()?.id.orEmpty()) }
    var to by remember {
        mutableStateOf(accounts.getOrNull(1)?.id.orEmpty())
    }
    var amount by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("记一笔划转") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                LabeledAccountPicker("从", accounts, from) { from = it }
                LabeledAccountPicker("到", accounts, to) { to = it }
                OutlinedTextField(
                    value = amount,
                    onValueChange = { amount = it },
                    label = { Text("金额（元）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("备注（可留空）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(from, to, amount, note) }) { Text("记入") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LabeledAccountPicker(
    label: String,
    accounts: List<AccountEntity>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    Column {
        Text(label, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(4.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            accounts.forEach { a ->
                FilterChip(
                    selected = selected == a.id,
                    onClick = { onSelect(a.id) },
                    label = { Text(a.name, maxLines = 1) },
                )
            }
        }
    }
}
