package kapi.ledger.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import kapi.core.llm.Money
import kapi.ledger.AppContainer
import kapi.ledger.data.BudgetEntity
import kapi.ledger.data.CategoryEntity
import kapi.ledger.data.LedgerRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/**
 * 预算管理（2026-10-03，咔皮对齐 P1）。
 *
 * 月总预算 + 各分类（顶级）预算 + 进度。对标商汤咔皮记账的「预算管理」页：
 * 当月、总预算剩余、分类额度逐行进度条。咔皮的"AI 智能预算方案"是它的 Pro
 * 付费功能，kapi 不做（本地、免费、不联网是本产品的底线）。
 *
 * ## 口径
 *
 * 「已用」与统计页同一口径：`direction=EXPENSE`、转账行（`toAccountId` 非空）
 * 排除、按分类的**顶级**归并（预算设在顶级）。月份用业务时间 [TxnEntity.occurredAt]。
 *
 * 数据全部现场查（不进 ViewModel）：这页只有一个入口（设置 → 预算管理），
 * 状态就是"当月的一组行"，进 ViewModel 只是搬运。
 */
@Composable
fun BudgetScreen(
    container: AppContainer,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val ledgerId = LedgerRepository.DEFAULT_LEDGER_ID
    val month = remember { YearMonth.now() }

    var budgets by remember { mutableStateOf<List<BudgetEntity>>(emptyList()) }
    var categories by remember { mutableStateOf<List<CategoryEntity>>(emptyList()) }
    var spentByCategory by remember { mutableStateOf<Map<String, Long>>(emptyMap()) }
    var spentTotal by remember { mutableStateOf(0L) }
    var editing by remember { mutableStateOf<CategoryEntity?>(null) }
    var editingTotal by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }

    // 当月区间 → 拉账目行聚合 + 订阅预算行。预算行变化（设置/清除）时 Flow 自动重推。
    LaunchedEffect(Unit) {
        val zone = ZoneId.systemDefault()
        val from = month.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val to = month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
        categories = container.db.categoryDao().observeForLedger(ledgerId).first()
            .filter { !it.deleted && it.kind == "EXPENSE" && it.parentId == null }
        container.db.budgetDao().observeForMonth(ledgerId, month.toString()).collect { rows ->
            budgets = rows
            val txns = container.db.txnDao().inRange(from, to)
                .filter { !it.deleted && it.direction == "EXPENSE" && it.toAccountId == null }
            val byTop = HashMap<String, Long>()
            var total = 0L
            val parentOf = categories.associate { it.id to it.id } +
                categories.associate { it.id to it.id } // 顶级自映射
            val childToParent = container.db.categoryDao().observeForLedger(ledgerId).first()
                .filter { !it.deleted && it.kind == "EXPENSE" && it.parentId != null }
                .associate { it.id to it.parentId!! }
            for (t in txns) {
                val catId = t.categoryId ?: continue
                val top = childToParent[catId] ?: if (parentOf.containsKey(catId)) catId else continue
                byTop[top] = (byTop[top] ?: 0L) + t.amountMinor
                total += t.amountMinor
            }
            spentByCategory = byTop
            spentTotal = total
            loaded = true
        }
    }

    val totalBudget = budgets.firstOrNull { it.categoryId == null }?.amountMinor
    val contentPadding = PaddingValues(top = 48.dp, bottom = 32.dp)
    Column(modifier = modifier.fillMaxSize().padding(contentPadding).padding(horizontal = 20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onDone) { Text("完成") }
            Text(
                "预算管理",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.width(64.dp))
        }
        Spacer(Modifier.height(8.dp))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                BudgetCard {
                    Text("${month.year} 年 ${month.monthValue} 月", style = MaterialTheme.typography.titleMedium, color = Ink)
                    Spacer(Modifier.height(10.dp))
                    // 总预算剩余大字。没设预算时引导去设置。
                    if (totalBudget == null) {
                        Text(
                            "还没设本月总预算",
                            style = MaterialTheme.typography.titleLarge,
                            color = InkSoft,
                        )
                    } else {
                        val remain = totalBudget - spentTotal
                        Text(
                            "剩余 " + Money.toYuanDisplay(remain),
                            style = MaterialTheme.typography.titleLarge,
                            color = if (remain >= 0) Ink else ExpenseRed,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    if (totalBudget != null) {
                        val usedPct = if (totalBudget > 0) {
                            (spentTotal * 100 / totalBudget).toInt().coerceIn(0, 100)
                        } else 0
                        Spacer(Modifier.height(8.dp))
                        ProgressBar(usedPct / 100f, over = spentTotal > totalBudget)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "已用 ${Money.toYuanDisplay(spentTotal)} / 预算 ${Money.toYuanDisplay(totalBudget)}" +
                                "（$usedPct%）",
                            style = MaterialTheme.typography.bodySmall,
                            color = InkSoft,
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "设置月总预算",
                        style = MaterialTheme.typography.labelLarge,
                        color = Brand,
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { editingTotal = true }
                            .padding(horizontal = 4.dp, vertical = 4.dp),
                    )
                }
            }

            item {
                Text(
                    "分类预算（点一行设置额度）",
                    style = MaterialTheme.typography.labelLarge,
                    color = InkSoft,
                    modifier = Modifier.padding(start = 4.dp, top = 6.dp),
                )
            }

            // 有预算的分类排前面，其余按本月花费降序；两者并集（没花也没预算的顶级不出现）
            val rows = categories
                .map { cat -> cat to (spentByCategory[cat.id] ?: 0L) }
                .filter { (cat, spent) -> spent > 0 || budgets.any { b -> b.categoryId == cat.id } }
                .sortedWith(
                    compareByDescending<Pair<CategoryEntity, Long>> { (c, spent) ->
                        (budgets.firstOrNull { b -> b.categoryId == c.id }?.amountMinor ?: 0L) * 1_000_000L + spent
                    },
                )
            items(rows.size) { i ->
                val (cat, spent) = rows[i]
                val budget = budgets.firstOrNull { b -> b.categoryId == cat.id }?.amountMinor
                BudgetCategoryRow(cat.name, spent, budget) { editing = cat }
            }

            item {
                Text(
                    "预算只存在这台设备上，不参与 NAS 同步。转账与自转账不计入已用。",
                    style = MaterialTheme.typography.bodySmall,
                    color = InkFaint,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }

    // ---- 编辑弹窗：总预算 / 分类预算 共用一套输入
    if (editingTotal) {
        BudgetEditDialog(
            title = "设置 ${month.monthValue} 月总预算",
            initial = totalBudget?.let { Money.toYuanDisplay(it) } ?: "",
            onDismiss = { editingTotal = false },
            onSave = { yuanText ->
                scope.launch {
                    upsertBudget(container, month, null, yuanText)
                    editingTotal = false
                }
            },
            onClear = if (totalBudget != null) {
                {
                    scope.launch {
                        container.db.budgetDao().delete("b-$month-total")
                        editingTotal = false
                    }
                }
            } else null,
        )
    }
    editing?.let { cat ->
        val current = budgets.firstOrNull { b -> b.categoryId == cat.id }?.amountMinor
        BudgetEditDialog(
            title = "「${cat.name}」月预算",
            initial = current?.let { Money.toYuanDisplay(it) } ?: "",
            onDismiss = { editing = null },
            onSave = { yuanText ->
                scope.launch {
                    upsertBudget(container, month, cat.id, yuanText)
                    editing = null
                }
            },
            onClear = if (current != null) {
                {
                    scope.launch {
                        container.db.budgetDao().delete("b-$month-${cat.id}")
                        editing = null
                    }
                }
            } else null,
        )
    }
}

private suspend fun upsertBudget(
    container: AppContainer,
    month: YearMonth,
    categoryId: String?,
    yuanText: String,
) {
    val minor = Money.parse(yuanText)?.minor ?: return
    if (minor <= 0L) return
    container.db.budgetDao().upsert(
        BudgetEntity(
            id = if (categoryId == null) "b-$month-total" else "b-$month-$categoryId",
            ledgerId = LedgerRepository.DEFAULT_LEDGER_ID,
            month = month.toString(),
            categoryId = categoryId,
            amountMinor = minor,
        ),
    )
}

@Composable
private fun BudgetCard(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.72f))
            .padding(16.dp),
    ) {
        content()
    }
}

@Composable
private fun BudgetCategoryRow(name: String, spentMinor: Long, budgetMinor: Long?, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.6f))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(name, style = MaterialTheme.typography.labelLarge, color = Ink, modifier = Modifier.weight(1f))
            val color = if (budgetMinor != null && spentMinor > budgetMinor) ExpenseRed else InkSoft
            Text(
                if (budgetMinor != null) {
                    "${Money.toYuanDisplay(spentMinor)} / ${Money.toYuanDisplay(budgetMinor)}"
                } else {
                    Money.toYuanDisplay(spentMinor)
                },
                style = MaterialTheme.typography.labelLarge,
                color = color,
            )
        }
        if (budgetMinor != null) {
            Spacer(Modifier.height(6.dp))
            val pct = if (budgetMinor > 0) (spentMinor.toFloat() / budgetMinor) else 1f
            ProgressBar(pct.coerceIn(0f, 1f), over = spentMinor > budgetMinor)
        }
    }
}

@Composable
private fun ProgressBar(fraction: Float, over: Boolean) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(InkFaint.copy(alpha = 0.25f)),
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction)
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(if (over) ExpenseRed else Brand),
        )
    }
}

@Composable
private fun BudgetEditDialog(
    title: String,
    initial: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
    onClear: (() -> Unit)?,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                label = { Text("金额（元）") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(text) }) { Text("保存") }
        },
        dismissButton = {
            Row {
                onClear?.let { clear ->
                    TextButton(onClick = clear) { Text("清除", color = ExpenseRed) }
                }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
    )
}
