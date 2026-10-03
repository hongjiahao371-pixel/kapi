package kapi.ledger.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.ReceiptLong
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kapi.core.llm.Money
import kapi.ledger.data.CategoryEntity
import kapi.ledger.data.TxnEntity
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val DAY_FMT = DateTimeFormatter.ofPattern("M月d日")
private val WEEKDAY = arrayOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

/**
 * 账单列表。
 *
 * 分组、排序、汇总全部在内存里现算，没有预聚合表——一个月几百行的量级下
 * 「查一次 + 分组」是几十微秒，而预聚合表要处理"跨月边界的一笔改动要同时
 * 改两张表"这类问题，收益和复杂度完全不成比例。
 */
@Composable
fun LedgerScreen(
    vm: LedgerViewModel,
    listState: LazyListState = rememberLazyListState(),
    onDelete: suspend (String) -> Unit,
    onOpenSettings: () -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val categories by vm.categories.collectAsStateWithLifecycle()
    val accounts by vm.accounts.collectAsStateWithLifecycle()
    val categoryNames = remember(categories) { categories.associate { it.id to it.name } }
    val accountNames = remember(accounts) { accounts.associate { it.id to it.name } }
    val scope = rememberCoroutineScope()
    // 点开的是**编辑**面板，删除是面板里的一个按钮。
    // 原来点一下直接弹"确定删除吗"——那是个**不可逆**操作放在最顺手的位置上，
    // 而用户点一行时想做的事九成是"改一下分类"。自动分类判错时尤其如此：
    // 没有这里就没有改的入口，也就没有自学习的数据。
    var editing by remember { mutableStateOf<TxnEntity?>(null) }
    var askDelete by remember { mutableStateOf<TxnEntity?>(null) }
    val grouped = remember(state.rows) { groupByDay(state.rows) }

    Scaffold(
        modifier = modifier,
        // 透明：KapiRoot 已经铺了液态背景，这里再铺一层不透明底色，
        // 玻璃和光斑就全被盖死了——页面会退回成一块平色。
        containerColor = Color.Transparent,
        // 底部导航条由外层 KapiRoot 画，所以这里的 inset 必须归零，
        // 否则 Scaffold 会自己再垫一份，列表底部凭空多出一大块空白。
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { inner ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = contentPadding.calculateTopPadding() + inner.calculateTopPadding() + 4.dp,
                bottom = contentPadding.calculateBottomPadding() + 24.dp,
            ),
        ) {
            item {
                MonthBar(
                    state = state,
                    onPrev = { vm.shiftMonth(-1) },
                    onNext = { vm.shiftMonth(1) },
                    onToday = { vm.goToCurrentMonth() },
                    // 齿轮保留着：底部导航说明"这是三个平级页面"，而设置
                    // 从任何页面一键可到更符合"顺手"这个直觉。配 NAS 是
                    // 低频操作，但它是这个产品最核心的一步，藏在二级页里
                    // 等于把它变成一个"没人会找到的功能"。
                    onSettings = onOpenSettings,
                )
            }

            item { SummaryCard(state, Modifier.padding(top = 4.dp)) }

            if (!state.loaded) {
                item { EmptyState("正在载入…", showHint = false) }
            } else if (state.rows.isEmpty()) {
                item {
                    EmptyState(
                        if (isCurrentMonth(state)) "这个月还没有记账" else "这个月没有记录",
                        showHint = true,
                    )
                }
            } else {
                for (group in grouped) {
                    item(key = "h-${group.day}") { DayHeader(group) }
                    items(group.rows, key = { it.id }) { row ->
                        TxnRow(
                            row = row,
                            category = row.categoryId?.let { categoryNames[it] },
                            account = row.accountId?.let { accountNames[it] },
                            onClick = { editing = row },
                        )
                    }
                }
            }
        }
    }

    // ---- 编辑面板：改分类 / 新建分类 / 删除 ----
    editing?.let { target ->
        TxnEditDialog(
            txn = target,
            categories = categories,
            currentCategoryName = target.categoryId?.let { categoryNames[it] },
            onDismiss = { editing = null },
            onPick = { catId ->
                scope.launch {
                    vm.setCategory(target.id, catId)
                    editing = null
                }
            },
            onCreate = { name, parentId ->
                scope.launch {
                    val id = vm.createCategory(name, target.direction, parentId)
                    if (id != null) vm.setCategory(target.id, id)
                    editing = null
                }
            },
            onAskDelete = { askDelete = target; editing = null },
        )
    }

    askDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { askDelete = null },
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(20.dp),
            title = { Text("删除这笔？") },
            text = {
                Text(
                    "${target.merchant ?: target.note ?: "未命名"}  " +
                        "¥${Money.toYuanDisplay(target.amountMinor)}"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    askDelete = null
                    // onDelete 是 suspend：Room 写盘不能挂主线程。
                    scope.launch { onDelete(target.id) }
                }) { Text("删除", color = ExpenseRed) }
            },
            dismissButton = {
                TextButton(onClick = { askDelete = null }) { Text("取消") }
            },
        )
    }
}

// ---------------------------------------------------------------------------

@Composable
private fun MonthBar(
    state: LedgerUiState,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onToday: () -> Unit,
    onSettings: () -> Unit,
) {
    val isCurrent = isCurrentMonth(state)
    Box(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Row(
            modifier = Modifier.align(Alignment.Center),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onPrev) {
                Icon(Icons.Filled.ChevronLeft, contentDescription = "上一月", tint = InkSoft)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "${state.month.monthValue} 月",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    text = if (isCurrent) "${state.month.year} 年 · 本月" else "${state.month.year} 年",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isCurrent) Brand else InkFaint,
                )
            }
            IconButton(onClick = onNext) {
                Icon(Icons.Filled.ChevronRight, contentDescription = "下一月", tint = InkSoft)
            }
        }

        // 回到本月：只在离开了本月时出现，给一个常驻的"本月"按钮不如
        // 在标题里标出"你现在不在本月"来得省地方。
        if (!isCurrent) {
            Text(
                text = "回到本月",
                style = MaterialTheme.typography.labelMedium,
                color = Brand,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .clip(RoundedCornerShape(8.dp))
                    .glassClickable { onToday() }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            )
        }

        IconButton(
            onClick = onSettings,
            modifier = Modifier.align(Alignment.CenterEnd),
        ) {
            Icon(Icons.Filled.Settings, contentDescription = "设置", tint = InkSoft)
        }
    }
}

@Composable
private fun SummaryCard(state: LedgerUiState, modifier: Modifier = Modifier) {
    val negative = state.summary.balanceMinor < 0
    // 标签色**不靠透明度分主次**。白字压在饱和渐变上时，alpha 0.75 只有
    // 4.06:1、0.7 更低，都够不到 AA 的 4.5:1——而"本月结余/支出/收入"是
    // 要被读的字，不是装饰。层级改由**字号和字重**承担（12sp / 34sp / 11sp），
    // 这是比调透明度更稳的做法：透明度做的层级在任何一个色相的卡面上都会塌。
    val onCard = Color.White
    val onCardSoft = Color.White.copy(alpha = 0.94f)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            // 结余为负时**整张卡换色**。原来不管正负都是一块品牌绿，等于在说
            // "亏了 12345" 是一件好事——绿色在财务语境里是"进账/安全"，
            // 亏钱挂在绿底上会让用户第一眼读成收入。数字再大也救不回来，
            // 因为颜色是比数字更快的一层判断。
            .background(if (negative) SummaryGradientDown else SummaryGradient)
            .padding(horizontal = 20.dp, vertical = 18.dp)
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "本月结余",
                    style = MaterialTheme.typography.labelMedium,
                    color = onCardSoft,
                )
                if (state.pendingCount > 0) {
                    Spacer(Modifier.width(8.dp))
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(Color.White.copy(alpha = 0.22f))
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text(
                            "${state.pendingCount} 笔待同步",
                            style = MaterialTheme.typography.labelSmall,
                            color = onCard,
                        )
                    }
                }
            }

            Spacer(Modifier.height(2.dp))
            Text(
                text = "¥ ${Money.toYuanDisplay(state.summary.balanceMinor)}",
                style = MoneyLarge,
                color = onCard,
                maxLines = 1,
            )

            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth()) {
                // 两列都**居中**。原来两列各占一半、里面的字却左对齐，于是
                // "支出 ¥12345.00" 挤在左边、"收入 ¥0.00" 挤在偏右，
                // 中间空出一大块、右侧还剩三分之一没人站——整张卡看着是歪的。
                // 居中之后两列各自站在自己那一半的中间，视觉上是一块完整的底。
                SummaryColumn("支出", state.summary.expenseMinor, Modifier.weight(1f), onCard, onCardSoft)
                Box(
                    Modifier
                        .width(1.dp)
                        .height(28.dp)
                        .background(Color.White.copy(alpha = 0.22f))
                )
                SummaryColumn("收入", state.summary.incomeMinor, Modifier.weight(1f), onCard, onCardSoft)
            }
        }
    }
}

@Composable
private fun SummaryColumn(
    label: String,
    minor: Long,
    modifier: Modifier,
    onValue: androidx.compose.ui.graphics.Color,
    onLabel: androidx.compose.ui.graphics.Color,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = onLabel,
        )
        Text(
            "¥ ${Money.toYuanDisplay(minor)}",
            style = MoneyMedium,
            color = onValue,
            maxLines = 1,
        )
    }
}

@Composable
private fun DayHeader(group: DayGroup) {
    val isToday = group.day == LocalDate.now()
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (isToday) "今天" else DAY_FMT.format(group.day),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            WEEKDAY[group.day.dayOfWeek.value - 1],
            style = MaterialTheme.typography.bodySmall,
            color = InkFaint,
        )
        Spacer(Modifier.weight(1f))
        Text(
            "支出 ¥${Money.toYuanDisplay(group.expenseMinor)}",
            style = MaterialTheme.typography.bodySmall,
            color = InkSoft,
        )
    }
}

/**
 * 改一笔账的分类。
 *
 * ## 这个面板为什么必须存在
 *
 * 它是**自学习唯一的学习信号**。自动分类判错时用户在这里改一次，同一个商户改够三次，
 * 之后这个商户的账就自动按他选的那一档归（见 `core` 的 `MerchantLearning`）。
 * 没有它，自动分类就是个单向的猜测——错了用户改不回来，于是错的那一档会一直错下去。
 *
 * ## 为什么点一行是"编辑"而不是"删除"
 *
 * 删除不可逆，而它原来在列表里最顺手的位置上。用户点一行时想做的事九成是改分类，
 * 尤其在自动分类刚推上去的这段时间。
 *
 * ## 新建分类挂在哪个父节点下
 *
 * 跟着**当前选中的那一档**：他正在给某笔餐饮的账点开"猫主子"这种新分类时，
 * 大概率是想加在餐饮下面，而不是挂到根上。默认挂当前选中的，没有选中就挂根。
 */
@Composable
private fun TxnEditDialog(
    txn: TxnEntity,
    categories: List<CategoryEntity>,
    currentCategoryName: String?,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
    onCreate: (name: String, parentId: String?) -> Unit,
    onAskDelete: () -> Unit,
) {
    var picked by remember(txn.id) { mutableStateOf<String?>(txn.categoryId) }
    var expanded by remember(txn.id) { mutableStateOf<String?>(null) }
    var newName by remember(txn.id) { mutableStateOf("") }

    val tops = remember(categories, txn.direction) {
        categories.filter { it.parentId == null && it.kind == txn.direction }
            .sortedBy { it.sortOrder }
    }
    val subsOf = { parentId: String ->
        categories.filter { it.parentId == parentId }.sortedBy { it.sortOrder }
    }
    val pickedTop = tops.firstOrNull { it.id == picked }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(20.dp),
        title = {
            Column {
                Text("¥${Money.toYuanDisplay(txn.amountMinor)}", color = Ink)
                Text(
                    txn.merchant ?: txn.note ?: "未命名",
                    style = MaterialTheme.typography.bodySmall,
                    color = InkSoft,
                )
                Text(
                    currentCategoryName ?: "未分类",
                    style = MaterialTheme.typography.labelMedium,
                    color = Brand,
                )
            }
        },
        text = {
            Column(Modifier.fillMaxWidth()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(tops, key = { it.id }) { c ->
                        CategoryPill(
                            text = c.name,
                            selected = c.id == picked,
                            onClick = {
                                // 再点一次已选中的 = 收起它的细分；
                                // 点新的 = 先选它，再展开它的细分。
                                if (c.id == picked) {
                                    picked = c.id
                                    expanded = if (expanded == c.id) null else c.id
                                } else {
                                    picked = c.id
                                    expanded = c.id
                                }
                            },
                        )
                    }
                }
                if (expanded != null) {
                    val subs = subsOf(expanded!!)
                    if (subs.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(subs, key = { it.id }) { c ->
                                CategoryPill(
                                    text = c.name,
                                    selected = c.id == picked,
                                    onClick = { picked = c.id },
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                Text("新建分类", style = MaterialTheme.typography.labelMedium, color = InkSoft)
                Spacer(Modifier.height(6.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it.take(20) },
                        placeholder = { Text("比如 猫主子", color = InkFaint) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = {
                            if (newName.isNotBlank()) onCreate(newName.trim(), pickedTop?.id)
                        },
                        enabled = newName.isNotBlank(),
                    ) { Text("建并归入", color = Brand) }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { picked?.let(onPick) },
                enabled = picked != null,
            ) { Text("保存", color = Brand) }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onAskDelete) { Text("删除", color = ExpenseRed) }
                TextButton(onClick = onDismiss) { Text("取消", color = InkSoft) }
            }
        },
    )
}

/** 分类选择器里的一颗。选中靠"底色更实 + 描边"两重，不只靠颜色。 */
@Composable
private fun CategoryPill(text: String, selected: Boolean, onClick: () -> Unit) {
    val container = if (selected) Brand.copy(alpha = 0.20f) else Brand.copy(alpha = 0.07f)
    val border = if (selected) 1.5.dp else 1.dp
    val borderColor = if (selected) Brand else Brand.copy(alpha = 0.18f)
    val content = if (selected) Brand else InkSoft
    Row(
        modifier = Modifier
            .height(32.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(container)
            .border(border, borderColor, RoundedCornerShape(16.dp))
            .glassClickable { onClick() }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = content,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
        )
    }
}

@Composable
private fun TxnRow(row: TxnEntity, category: String?, account: String?, onClick: () -> Unit) {
    val isExpense = row.direction == "EXPENSE"
    // 取色按**分类名**而不是 categoryId：id 在不同设备、不同同步顺序下会变，
    // 而"橙色 = 购物"是用户要记住的东西。统计页用的是同一张表，两处颜色一致。
    val tone = categoryTone(category)
    val title = row.merchant ?: row.note ?: category ?: "未命名"
    val sign = if (isExpense) "-" else "+"
    val color = when (row.direction) {
        "INCOME" -> IncomeGreen
        "EXPENSE" -> ExpenseRed
        else -> InkSoft
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .glassClickable { onClick() }
            .padding(vertical = 10.dp, horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(CircleShape)
                .background(tone.container),
            contentAlignment = Alignment.Center,
        ) {
            // 图标取**分类名**，不是标题（title）。标题是商户优先的——一笔"午餐"
            // 商户和分类都是餐饮，碰巧一样；但商户叫"星巴克"时，标题是"星巴克"，
            // 用标题查表会查不到，整行退化成三个点。分类才是圆圈该表达的东西。
            Icon(
                imageVector = CategoryIcons.of(category),
                contentDescription = null,
                tint = tone.onContainer,
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // 副标题不能把标题再抄一遍。标题已经用了分类的时候（没填商户和备注），
            // 副标题就只留账户——否则每行都会显示两次"餐饮"。
            val subtitle = buildList {
                if (title != category) category?.let { add(it) }
                add(row.note ?: account ?: "")
            }.filter { it.isNotBlank() }.joinToString(" · ")
            if (subtitle.isNotEmpty()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = InkSoft,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = sign + Money.toYuanDisplay(row.amountMinor),
            style = MoneyMedium,
            color = color,
            maxLines = 1,
        )
    }
}

@Composable
private fun EmptyState(text: String, showHint: Boolean) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 64.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.size(64.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Outlined.ReceiptLong,
                    contentDescription = null,
                    tint = InkFaint,
                    modifier = Modifier.size(28.dp),
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (showHint) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = "点右下角的 + 记第一笔",
                style = MaterialTheme.typography.bodyMedium,
                color = InkSoft,
                textAlign = TextAlign.Center,
            )
        }
    }
}

private fun isCurrentMonth(state: LedgerUiState) = state.month == YearMonth.now()

// ---------------------------------------------------------------------------
// 纯函数：分组与汇总。没有 Compose 依赖，可以直接跑 JVM 单测。
// ---------------------------------------------------------------------------

data class DayGroup(val day: LocalDate, val rows: List<TxnEntity>, val expenseMinor: Long)

fun groupByDay(rows: List<TxnEntity>, zone: ZoneId = ZoneId.systemDefault()): List<DayGroup> =
    rows
        .groupBy { Instant.ofEpochMilli(it.occurredAt).atZone(zone).toLocalDate() }
        .toSortedMap(compareByDescending { it })
        .map { (day, list) ->
            DayGroup(
                day = day,
                rows = list.sortedByDescending { it.occurredAt },
                // 自转账（toAccountId 非空）不算支出：与 observeSummary 同一口径。
                // 漏掉它的日子，日头上的"支出 ¥12110"和顶部卡片的 ¥10.20 对不上。
                expenseMinor = list.filter {
                    it.direction == "EXPENSE" && it.toAccountId == null
                }.sumOf { it.amountMinor },
            )
        }

/** 按分类汇总本月支出。返回金额倒序——用户看统计页第一眼想看的是花得最多的。 */
fun expenseByCategory(
    rows: List<TxnEntity>,
): List<Pair<String?, Long>> =
    rows.asSequence()
        .filter { it.direction == "EXPENSE" && it.toAccountId == null }
        .groupBy { it.categoryId }
        .map { (id, list) -> id to list.sumOf { it.amountMinor } }
        .sortedByDescending { it.second }
        .toList()
