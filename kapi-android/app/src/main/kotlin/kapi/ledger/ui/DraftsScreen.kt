package kapi.ledger.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kapi.core.llm.Money
import kapi.ledger.auto.AutoBookStore
import kapi.ledger.data.AutoDraftEntity
import kapi.ledger.data.KapiDatabase
import kapi.ledger.data.LedgerRepository
import kapi.ledger.sync.LocalEditWriter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 待确认队列。
 *
 * 这一页是整个自动记账的**信用入口**：识别出来的东西不直接进账本，用户在这里
 * 扫一眼、点几个"记下"。所以它的设计目标不是"展示所有信息"，而是
 * **让用户在两秒内判断这一笔记得对不对**——多一个要读的字，就多一分放弃。
 */
class DraftsViewModel(
    private val db: KapiDatabase,
    private val repository: LedgerRepository,
    private val editor: LocalEditWriter,
    private val deviceId: () -> String,
) : ViewModel() {

    val state: StateFlow<List<AutoDraftEntity>> = db.autoDraftDao().observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    /**
     * 确认一笔，进账本。
     *
     * 必须走 [LocalEditWriter]：pending 的编码格式全 App 只有一个正确版本，
     * 在这里手拼 JSON 迟早对不上，而对不上的表现是"同步一次金额就变成 0"，
     * 不报错、只在跨端那一跳才现形。
     */
    fun confirm(draft: AutoDraftEntity) {
        viewModelScope.launch {
            _busy.value = true
            runCatching {
                editor.createTransaction(
                    amountMinor = draft.amountMinor,
                    direction = draft.direction,
                    // 兜底是 **A1（现金）**，不是 ledgerId。这两个常量都是
                    // 字符串、看���去一样，填错不会报错，只会让每一笔记到
                    // "我的账本"这个不存在的账户上，而账户表里根本没有它。
                    accountId = draft.accountId ?: FALLBACK_ACCOUNT_ID,
                    occurredAt = draft.occurredAt,
                    ledgerId = draft.ledgerId,
                    deviceId = deviceId(),
                    note = draft.note,
                    merchant = draft.merchant,
                    source = LocalEditWriter.SOURCE_LOCAL_FALLBACK,
                    confidence = draft.confidence,
                )
                db.autoDraftDao().delete(draft.id)
            }
            _busy.value = false
        }
    }

    fun discard(draft: AutoDraftEntity) {
        viewModelScope.launch { db.autoDraftDao().delete(draft.id) }
    }

    fun confirmAll() {
        viewModelScope.launch {
            _busy.value = true
            // 逐笔而不是批量：每一笔都是一次独立的数据库事务，中途失败时
            // 前面几笔已经落库、后面几笔还在队列里，用户重进页面继续点就行。
            // 批量提交看着快，但失败就是全灭。
            for (d in state.value) confirmNow(d)
            _busy.value = false
        }
    }

    fun discardAll() {
        viewModelScope.launch {
            val ids = state.value.map { it.id }
            if (ids.isNotEmpty()) db.autoDraftDao().deleteAll(ids)
        }
    }

    private suspend fun confirmNow(draft: AutoDraftEntity) {
        runCatching {
            editor.createTransaction(
                amountMinor = draft.amountMinor,
                direction = draft.direction,
                accountId = draft.accountId ?: FALLBACK_ACCOUNT_ID,
                occurredAt = draft.occurredAt,
                ledgerId = draft.ledgerId,
                deviceId = deviceId(),
                note = draft.note,
                merchant = draft.merchant,
                source = LocalEditWriter.SOURCE_LOCAL_FALLBACK,
                confidence = draft.confidence,
            )
            db.autoDraftDao().delete(draft.id)
        }
    }

    companion object {
        /** 猜不出账户时的兜底。必须和 `LedgerRepository.ensureSeeded()` 铺的默认值一致。 */
        const val FALLBACK_ACCOUNT_ID = "A1"
    }
}

class DraftsViewModelFactory(
    private val db: KapiDatabase,
    private val repository: LedgerRepository,
    private val editor: LocalEditWriter,
    private val deviceId: () -> String,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        DraftsViewModel(db, repository, editor, deviceId) as T
}

// ---------------------------------------------------------------------------

private val timeFmt = DateTimeFormatter.ofPattern("M月d日 HH:mm")

private fun formatTime(ms: Long): String = runCatching {
    Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(timeFmt)
}.getOrDefault("")

@Composable
fun DraftsScreen(
    container: kapi.ledger.AppContainer,
    listState: LazyListState = rememberLazyListState(),
    contentPadding: PaddingValues,
    onOpenAutoSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val factory = remember(container) {
        DraftsViewModelFactory(
            container.db, container.repository, container.editor,
        ) { container.tokenStore.deviceId }
    }
    val vm: DraftsViewModel = viewModel(factory = factory)
    val drafts by vm.state.collectAsState()

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            top = contentPadding.calculateTopPadding() + 8.dp,
            bottom = contentPadding.calculateBottomPadding() + 16.dp,
            start = 16.dp,
            end = 16.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(
                Modifier.fillMaxWidth().padding(bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "待确认",
                    style = androidx.compose.material3.MaterialTheme.typography.displaySmall,
                    color = Ink,
                )
                Spacer(Modifier.width(8.dp))
                if (drafts.isNotEmpty()) {
                    Text(
                        "${drafts.size}",
                        style = MoneyMedium,
                        color = Brand,
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(BrandSoft)
                            .padding(horizontal = 9.dp, vertical = 2.dp),
                    )
                }
                Spacer(Modifier.weight(1f))
                if (drafts.isNotEmpty()) {
                    MiniAction("全部记下", Brand, enabled = true) { vm.confirmAll() }
                    Spacer(Modifier.width(8.dp))
                    MiniAction("忽略", InkSoft, enabled = true) { vm.discardAll() }
                }
            }
        }

        if (drafts.isEmpty()) {
            item { DraftsEmptyState(onOpenAutoSettings) }
        } else {
            items(drafts, key = { it.id }) { d ->
                DraftCard(
                    draft = d,
                    onConfirm = { vm.confirm(d) },
                    onDiscard = { vm.discard(d) },
                )
            }
            item {
                Text(
                    "这些是自动读到的，还没有进账本。确认过的会立刻记入，之后同步到 NAS。",
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                    color = InkFaint,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun DraftsEmptyState(onOpenAutoSettings: () -> Unit) {
    SurfaceCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(22.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.NotificationsActive, null,
                    tint = Brand, modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "还没有要确认的",
                    style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                    color = Ink,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "开了自动记账之后，微信、支付宝、银行 App 发了支付通知，这里会列出" +
                    "读到的金额和商户。它不会直接记账——每笔都要你点一下。",
                style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                color = InkSoft,
            )
            Spacer(Modifier.height(14.dp))
            MiniAction("去开自动记账", Brand, enabled = true, onOpenAutoSettings)
        }
    }
}

@Composable
private fun DraftCard(
    draft: AutoDraftEntity,
    onConfirm: () -> Unit,
    onDiscard: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val income = draft.direction == "INCOME"
    val amountColor = if (income) IncomeGreen else ExpenseRed
    val sources = draft.sources.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    val multi = sources.size > 1 || draft.evidenceCount > 1

    SurfaceCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(
                        (if (income) "+" else "-") + Money.toYuanDisplay(draft.amountMinor),
                        style = MoneyLarge,
                        color = amountColor,
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        draft.merchant ?: "（没读到商户）",
                        style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                        color = if (draft.merchant != null) Ink else InkFaint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        formatTime(draft.occurredAt) + " · " +
                            AutoBookStore.PACKAGE_LABELS[draft.originPackage] ?: "自动识别",
                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                        color = InkFaint,
                    )
                }
            }

            // 两条来源都提到了这一笔。必须写出来：用户知道"这是同一笔被读了两次"
            // 才不会奇怪地数成两杯咖啡。
            if (multi) {
                Spacer(Modifier.height(10.dp))
                Notice(
                    "通知和读屏都提到了这笔，已按同一笔合并" +
                        if (draft.evidenceCount > 1) "（捕获 ${draft.evidenceCount} 次）" else ""
                )
            }

            // 账本里已经有一笔很像的。**标出来但不自动丢**——大模型先入账、
            // 另一条通路随后收成草稿的情况是真实存在的，照点"记下"就会重复。
            if (draft.likelyDuplicateOf != null) {
                Spacer(Modifier.height(8.dp))
                Notice("账本里已经有一笔金额、时间都接近的。确认前先翻一下账本。", warn = true)
            }

            if (!draft.rawText.isNullOrBlank()) {
                Spacer(Modifier.height(6.dp))
                Row(
                    Modifier
                        .clip(RoundedCornerShape(10.dp))
                        // 展开/收起是整块文字可点，所以不收缩padding——
                        // 涟漪要铺满这一行的可点区域，用户才知道能点哪儿
                        .glassClickable { expanded = !expanded }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Outlined.Visibility, null,
                        tint = InkFaint, modifier = Modifier.size(15.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (expanded) "收起原文" else "看看读到了什么",
                        style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                        color = InkSoft,
                    )
                    Icon(
                        Icons.Filled.ExpandMore, null,
                        tint = InkFaint,
                        modifier = Modifier.size(15.dp),
                    )
                }
                AnimatedVisibility(visible = expanded) {
                    Text(
                        draft.rawText.orEmpty(),
                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                        color = InkSoft,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.Black.copy(alpha = 0.04f))
                            .padding(12.dp),
                    )
                }
            }

            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MiniAction("记下", BrandDeep, enabled = true, onClick = onConfirm)
                MiniAction("忽略", InkSoft, enabled = true, onClick = onDiscard)
            }
        }
    }
}

@Composable
private fun Notice(text: String, warn: Boolean = false) {
    Text(
        text = text,
        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
        color = if (warn) ExpenseRed else InkSoft,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background((if (warn) ExpenseRed else Brand).copy(alpha = 0.08f))
            .padding(horizontal = 10.dp, vertical = 7.dp),
    )
}

@Composable
private fun MiniAction(
    label: String,
    color: Color,
    enabled: Boolean,
    onClick: () -> Unit = {},
) {
    Text(
        text = label,
        style = androidx.compose.material3.MaterialTheme.typography.labelLarge,
        color = if (enabled) color else InkFaint,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(color.copy(alpha = if (enabled) 0.10f else 0.04f))
            // 上一版这里是 indication = null，理由是"自定义按钮要干净"，
            // 实际效果是整个 App 点下去毫无反馈。glassClickable 补回涟漪，
            // 并且额外加一层 0.955 的按压缩放——涟漪只说明"这里能点"，
            // 缩放才说明"它被按下去了"。
            .glassClickable(enabled = enabled) { onClick() }
            .padding(horizontal = 16.dp, vertical = 9.dp),
    )
}

