package kapi.ledger.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.foundation.clickable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kapi.core.llm.Money
import kapi.ledger.data.TxnEntity
import java.time.YearMonth

/**
 * 统计。
 *
 * 只回答一个问题：**这段时间的钱花在哪了**。按分类金额倒序，条形长度按占
 * 最大项的比例——不用绝对值刻度，因为用户比的是"餐饮比交通多多少"，
 * 不是"餐饮是不是 40%"。
 *
 * 周/月两个维度（2026-10-03 咔皮对齐 P2；咔皮是 周/月/年/自定义）。
 * 月走 [LedgerViewModel] 的既有状态；周**刻意不走 vm**——周会跨月
 * （9/29–10/5），vm 的整根数据管道都建立在 YearMonth 上，为周去改它要动
 * 账本页和汇总页。周模式在这页内部自己查区间（[kapi.ledger.data.TxnDao.inRange]），
 * 数据量是 7 天的行，现场查毫无压力。
 */
@Composable
fun StatsScreen(
    vm: LedgerViewModel,
    container: kapi.ledger.AppContainer,
    listState: LazyListState = rememberLazyListState(),
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val categories by vm.categories.collectAsStateWithLifecycle()
    val names = remember(categories) { categories.associate { it.id to it.name } }

    // ---- 维度状态：month（vm 管）/ week（本页自管，offset 相对本周）----
    var mode by remember { androidx.compose.runtime.mutableStateOf("month") }
    var weekOffset by remember { androidx.compose.runtime.mutableStateOf(0) }
    var weekRows by remember { androidx.compose.runtime.mutableStateOf<List<TxnEntity>>(emptyList()) }
    var weekLoaded by remember { androidx.compose.runtime.mutableStateOf(false) }

    val zone = java.time.ZoneId.systemDefault()
    val weekStart = remember(weekOffset) {
        java.time.LocalDate.now(zone).plusWeeks(weekOffset.toLong())
            .with(java.time.DayOfWeek.MONDAY)
    }
    val weekEnd = weekStart.plusDays(7)
    LaunchedEffect(weekOffset, mode) {
        if (mode != "week") return@LaunchedEffect
        val from = weekStart.atStartOfDay(zone).toInstant().toEpochMilli()
        val to = weekEnd.atStartOfDay(zone).toInstant().toEpochMilli()
        weekRows = container.db.txnDao().inRange(from, to)
        weekLoaded = true
    }

    // 两个维度各自算各自的构成；下游（合计/环形/条形）不关心来源。
    val monthByCategory = remember(state.rows) { expenseByCategory(state.rows) }
    val weekByCategory = remember(weekRows) { expenseByCategory(weekRows) }
    val byCategory = if (mode == "week") weekByCategory else monthByCategory
    val maxMinor = byCategory.maxOfOrNull { it.second } ?: 0L
    val totalExpense = byCategory.sumOf { it.second }
    val isCurrent = if (mode == "week") weekOffset == 0 else state.month == YearMonth.now()
    // 周的收入/结余：现场聚合（排除转账行，口径与月汇总一致）
    val weekSummary = remember(weekRows) {
        var income = 0L
        var expense = 0L
        for (t in weekRows) {
            if (t.deleted || t.toAccountId != null) continue
            when (t.direction) {
                "INCOME" -> income += t.amountMinor
                "EXPENSE" -> expense += t.amountMinor
            }
        }
        Triple(expense, income, income - expense)
    }
    val totalIncome = if (mode == "week") weekSummary.second else state.summary.incomeMinor
    val totalBalance = if (mode == "week") weekSummary.third else state.summary.balanceMinor
    val loaded = if (mode == "week") weekLoaded else state.loaded

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = contentPadding.calculateTopPadding() + 4.dp,
            bottom = contentPadding.calculateBottomPadding() + 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        item {
            // 维度切换：周 / 月。放在标题上方，选中态青底白字。
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                DimensionChip("周", mode == "week") { mode = "week" }
                DimensionChip("月", mode == "month") { mode = "month" }
            }
            if (mode == "week") {
                WeekHeader(
                    start = weekStart,
                    isCurrent = weekOffset == 0,
                    onPrev = { weekOffset -= 1 },
                    onNext = { weekOffset += 1 },
                )
            } else {
                StatsHeader(
                    month = state.month,
                    isCurrent = isCurrent,
                    onPrev = { vm.shiftMonth(-1) },
                    onNext = { vm.shiftMonth(1) },
                )
            }
        }

        if (!loaded) {
            item { Text("正在载入…", color = InkSoft, modifier = Modifier.padding(top = 48.dp)) }
        } else if (byCategory.isEmpty()) {
            item {
                Text(
                    text = if (isCurrent) "这个周期还没有支出" else "该周期没有支出记录",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(top = 64.dp),
                )
            }
        } else {
            item {
                TotalBlock(
                    total = totalExpense,
                    income = totalIncome,
                    balance = totalBalance,
                    modifier = Modifier.padding(top = 4.dp, bottom = 20.dp),
                )
            }
            item {
                // 环形图放在"合计"和"排行"之间。之前这页只有一个总数 + 一排
                // 横条，一屏里没有任何一处能一眼看出**结构**——横条要一行一行
                // 读才能拼出"居住占了八成"，而环形一眼就有。
                val names2 = byCategory.map { (id, minor) -> (names[id] ?: "未分类") to minor }
                DonutChart(
                    slices = names2,
                    total = totalExpense,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                        .padding(bottom = 14.dp),
                )
            }
            items(byCategory, key = { it.first ?: "_" }) { (id, minor) ->
                CategoryBar(
                    name = names[id] ?: "未分类",
                    minor = minor,
                    ratio = if (maxMinor > 0) minor.toFloat() / maxMinor.toFloat() else 0f,
                    share = if (totalExpense > 0) minor.toFloat() / totalExpense.toFloat() else 0f,
                )
            }
        }
    }
}

/**
 * 支出构成环形图。
 *
 * ## 三个踩过的点
 *
 * **颜色必须在 Canvas 外面解析。** `categoryTone` 是 `@Composable`（它要读
 * `isSystemInDarkTheme()`），而 `Canvas` 的 lambda 是 `DrawScope`——不是
 * composable 上下文，在里面调会直接编译不过。正确做法是先 `map` 成一个
 * `List<Color>` 再传进去。
 *
 * **段与段之间必须留缝。** 不留缝的话相邻两段的抗锯齿边缘会互相混色，
 * 边界糊成一条说不清的颜色带；留 2.5° 就够，缝太宽会让只有两三项的月份
 * 看起来像被咬掉几口。
 *
 * **只有一项时不要留缝。** 360° 减掉缝 = 359.5°，起点和终点错开的那 0.5°
 * 在视觉上是一个缺口，读起来像"还有一项没画出来"。所以缝只加在**段与段之间**，
 * 不加在首尾。
 */
@Composable
private fun DonutChart(
    slices: List<Pair<String, Long>>,
    total: Long,
    modifier: Modifier = Modifier,
) {
    // 颜色在 composable 作用域里解析，不能留到 DrawScope 里。
    val colors = slices.map { (name, _) -> categoryTone(name).base }
    val mins = slices.map { it.second }

    val progress by animateFloatAsState(
        targetValue = if (total > 0) 1f else 0f,
        animationSpec = tween(durationMillis = 620, easing = FastOutSlowInEasing),
        label = "donut",
    )

    Box(modifier, contentAlignment = Alignment.Center) {
        // 【必须锁成正方形】donut 是弧，半径只有一个值。外面如果给的是
        // fillMaxWidth + 固定高度（912 × 499 这种），弧会跟着一起被拉扁，
        // 画出来是一个**椭圆环**——统计页第一眼最扎眼的错就是这个。
        // 弧的尺寸只能从 size 里取，而 size 就是 Canvas 自己的尺寸，
        // 所以要正方形就得先把 Canvas 本身做成正方形，不能靠 drawArc 里补偿。
        Canvas(Modifier.size(178.dp)) {
            if (total <= 0) return@Canvas
            val stroke = size.minDimension * 0.135f
            val half = stroke / 2f
            val arcSize = androidx.compose.ui.geometry.Size(
                size.width - stroke,
                size.height - stroke,
            )
            val topLeft = androidx.compose.ui.geometry.Offset(half, half)
            val gap = if (mins.size > 1) 2.5f else 0f

            var start = -90f
            for (i in mins.indices) {
                val frac = mins[i].toFloat() / total.toFloat()
                val sweep = frac * 360f * progress
                if (sweep <= 0.01f) continue
                drawArc(
                    color = colors[i],
                    startAngle = start + gap / 2f,
                    sweepAngle = (sweep - gap).coerceAtLeast(0.6f),
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = stroke),
                )
                start += sweep
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("支出合计", style = MaterialTheme.typography.labelSmall, color = InkSoft)
            Text(
                "¥ ${Money.toYuanDisplay(total)}",
                style = MoneyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun StatsHeader(
    month: YearMonth,
    isCurrent: Boolean,
    onPrev: () -> Unit,
    onNext: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onPrev) {
            Icon(Icons.Filled.ChevronLeft, contentDescription = "上一月", tint = InkSoft)
        }
        Column(
            modifier = Modifier.weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "${month.monthValue} 月支出构成",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                if (isCurrent) "${month.year} 年" else "${month.year} 年",
                style = MaterialTheme.typography.labelSmall,
                color = InkFaint,
            )
        }
        IconButton(onClick = onNext) {
            Icon(Icons.Filled.ChevronRight, contentDescription = "下一月", tint = InkSoft)
        }
    }
}

/** 周维度的标题：日期区间 + 前后翻周。 */
@Composable
private fun WeekHeader(
    start: java.time.LocalDate,
    isCurrent: Boolean,
    onPrev: () -> Unit,
    onNext: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onPrev) {
            Icon(Icons.Filled.ChevronLeft, contentDescription = "上一周", tint = InkSoft)
        }
        Column(
            modifier = Modifier.weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "本周支出构成".takeIf { isCurrent } ?: "周支出构成",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                "%d.%d – %d.%d".format(
                    start.monthValue, start.dayOfMonth,
                    start.plusDays(6).monthValue, start.plusDays(6).dayOfMonth,
                ),
                style = MaterialTheme.typography.labelSmall,
                color = InkFaint,
            )
        }
        IconButton(onClick = onNext) {
            Icon(Icons.Filled.ChevronRight, contentDescription = "下一周", tint = InkSoft)
        }
    }
}

@Composable
private fun DimensionChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.labelLarge,
        color = if (selected) androidx.compose.ui.graphics.Color.White else InkSoft,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) Brand else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 6.dp),
    )
}

@Composable
private fun TotalBlock(
    total: Long,
    income: Long,
    balance: Long,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(18.dp)
    ) {
        Text("支出合计", style = MaterialTheme.typography.labelSmall, color = InkSoft)
        Text(
            "¥ ${Money.toYuanDisplay(total)}",
            style = MoneyLarge,
            color = ExpenseRed,
        )
        Spacer(Modifier.height(14.dp))
        Row {
            MiniStat("收入", income, IncomeGreen, Modifier.weight(1f))
            MiniStat("结余", balance, if (balance >= 0) Brand else ExpenseRed, Modifier.weight(1f))
        }
    }
}

@Composable
private fun MiniStat(label: String, minor: Long, color: androidx.compose.ui.graphics.Color, modifier: Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = InkSoft)
        Text(
            "¥ ${Money.toYuanDisplay(minor)}",
            style = MoneyMedium,
            color = color,
        )
    }
}

@Composable
private fun CategoryBar(
    name: String,
    minor: Long,
    ratio: Float,
    share: Float,
) {
    val tone = categoryTone(name)
    val animated by animateFloatAsState(
        targetValue = ratio.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 420),
        label = "bar",
    )
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 分类图标：有 emoji 用 emoji（对标咔皮的彩色图标），没有退回色点。
        val emoji = CategoryEmoji.emojiOf(name)
        if (emoji != null) {
            Text(
                text = emoji,
                fontSize = 16.sp,
                modifier = Modifier.width(28.dp),
            )
        } else {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(tone.base)
            )
            Spacer(Modifier.width(10.dp))
        }
        Text(
            text = name,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(72.dp),
        )
        Spacer(Modifier.width(10.dp))
        Box(
            modifier = Modifier
                .weight(1f)
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(animated)
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(tone.base)
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(
            text = "¥ ${Money.toYuanDisplay(minor)}",
            style = MoneyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = formatShare(share),
            style = MaterialTheme.typography.bodySmall,
            color = InkFaint,
        )
    }
}

/**
 * 占比文案。
 *
 * 记账 App 里小额很常见（42 元分在 8800 元的月支出里就是 0.5%）。直接
 * `toInt()` 会把它显示成「0%」——用户会以为这一栏是空的、金额算错了。
 * 小于 1% 就明说小于 1%，别假装是 0。
 */
fun formatShare(share: Float): String {
    if (share.isNaN() || share <= 0f) return "0%"
    val pct = share * 100f
    if (pct < 1f) return "<1%"
    if (pct > 99.4f) return "100%"
    return "${pct.toInt()}%"
}

