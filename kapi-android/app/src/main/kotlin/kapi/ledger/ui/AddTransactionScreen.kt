package kapi.ledger.ui

import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ImageSearch
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.outlined.Backspace
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kapi.core.llm.Direction
import kapi.core.llm.Money
import kapi.core.llm.TransactionExtractor
import kapi.ledger.llm.LlmClient
import kapi.ledger.llm.LlmFailure
import kapi.ledger.llm.LlmStore
import kapi.ledger.llm.ScreenshotLoader
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val KEYPAD = listOf(
    listOf("1", "2", "3"),
    listOf("4", "5", "6"),
    listOf("7", "8", "9"),
    listOf(".", "0", "del"),
)

private val TIME_FMT = DateTimeFormatter.ofPattern("M月d日 HH:mm")

/** 截图识别的界面状态。三态:没开始 / 进行中 / 出结果或出错误。 */
private sealed interface RecognizeState {
    data object Idle : RecognizeState
    data object Busy : RecognizeState
    data class Done(val draft: kapi.core.llm.TransactionDraft) : RecognizeState
    data class Fail(val detail: String) : RecognizeState
}

/**
 * 记一笔。
 *
 * 金额是唯一的必填项，其余全部可以留空后补。理由很实际：付款通知弹出来的那
 * 一秒用户只想记下"花了多少"，让他先选分类再选账户会把这条路径的完成率拉到
 * 一半以下。所以键盘在最下面、大号金额在最上面，中间全是可跳过的。
 */
@Composable
fun AddTransactionScreen(
    vm: LedgerViewModel,
    onDone: () -> Unit,
    llmStore: LlmStore? = null,
    llmClient: LlmClient? = null,
    onOpenLlmSettings: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val categories by vm.categories.collectAsStateWithLifecycle()
    val accounts by vm.accounts.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var amount by remember { mutableStateOf("") }
    var isExpense by remember { mutableStateOf(true) }
    var categoryId by remember { mutableStateOf<String?>(null) }
    var accountId by remember { mutableStateOf(LedgerViewModel.DEFAULT_ACCOUNT_ID) }
    var note by remember { mutableStateOf("") }
    var merchant by remember { mutableStateOf("") }
    var occurredAt by remember { mutableStateOf(LocalDateTime.now()) }
    var recognize by remember { mutableStateOf<RecognizeState>(RecognizeState.Idle) }

    // ---- 截图识别 ----
    val llmConfig = remember(llmStore) { llmStore?.load() }
    val llmReady = llmConfig?.isConfigured == true && llmClient != null
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val config = llmConfig ?: return@rememberLauncherForActivityResult
        val client = llmClient ?: return@rememberLauncherForActivityResult
        recognize = RecognizeState.Busy
        scope.launch {
            recognize = try {
                val prepared = ScreenshotLoader.load(context, uri)
                val raw = client.recognizeScreenshot(
                    config = config,
                    imageBase64 = prepared.base64,
                    mimeType = prepared.mimeType,
                    categoryHints = categories.map { it.name },
                )
                val result = TransactionExtractor.extract(raw)
                val best = result.best
                if (best == null || !best.isUsable) {
                    RecognizeState.Fail(
                        "没能从这张图里读出金额。" +
                            (result.anomalies.firstOrNull()?.let { "（$it）" } ?: "") +
                            "可以直接手输。"
                    )
                } else {
                    // 先把结果填进表单。金额用「元」字符串回填，精度到分，
                    // 不能拿 Long 除 100 再拼——那样 23.50 会变成 23.5 看着像小数点错了。
                    amount = Money.toYuanDisplay(best.amountMinor)
                    isExpense = best.direction != Direction.INCOME
                    merchant = best.merchant ?: merchant
                    note = best.note ?: note
                    best.category?.let { name ->
                        // 按名字匹配本地分类。匹配不上就留空让用户自己选——
                        // 硬塞一个错的分类比空着更糟，它会悄悄进统计页。
                        categories.firstOrNull { it.name == name && it.kind == if (isExpense) "EXPENSE" else "INCOME" }
                            ?.let { categoryId = it.id }
                    }
                    if (best.occurredAt > 0) {
                        occurredAt = Instant.ofEpochMilli(best.occurredAt)
                            .atZone(ZoneId.systemDefault()).toLocalDateTime()
                    }
                    RecognizeState.Done(best)
                }
            } catch (e: ScreenshotLoader.LoadFailed) {
                // 必须单列。LoadFailed 带着写好的中文提示，掉进下面的通用分支
                // 就只剩一个类名——用户看到"识别失败:LoadFailed"，等于什么都没说。
                // 这和 SyncFailure 当初"界面上永远只有连接失败"是同一个坑。
                Log.e("AddTxn", "图片读取失败: ${e.message}", e)
                RecognizeState.Fail(e.message ?: "这张图读不出来，换一张试试")
            } catch (e: LlmFailure) {
                Log.e("AddTxn", "截图识别失败: ${e.javaClass.name}", e)
                RecognizeState.Fail(e.detail)
            } catch (e: Exception) {
                Log.e("AddTxn", "截图识别失败: ${e.javaClass.name}", e)
                RecognizeState.Fail("识别失败：${e.javaClass.simpleName}")
            }
        }
    }

    val visibleCategories = remember(categories, isExpense) {
        categories.filter { it.kind == if (isExpense) "EXPENSE" else "INCOME" }
    }

    // 切换收支方向后，原来选中的分类可能已经不在候选里。
    // 留着它会存下一笔"支出 + 收入分类"的矛盾数据，而界面上完全看不出来。
    LaunchedEffect(visibleCategories) {
        if (categoryId != null && visibleCategories.none { it.id == categoryId }) {
            categoryId = null
        }
    }

    val amountMinor = remember(amount) { parseAmountToMinor(amount) }
    val canSave = (amountMinor ?: 0L) > 0

    Column(
        // 同样不铺不透明底色，否则液态背景被盖死、玻璃退化成灰块。
        // 「记一笔」是全屏模态页，但它仍然走 KapiRoot 的同一套背景。
        modifier = modifier.fillMaxSize(),
    ) {
        // ---- 顶栏 ----
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onDone) {
                Icon(Icons.Filled.Close, contentDescription = "关闭", tint = InkSoft)
            }
            Text(
                "记一笔",
                style = MaterialTheme.typography.titleLarge,
                // 和设置页标题同一个坑：不写 color 就继承 LocalContentColor，
                // 而这一层没有 Surface 兜底，默认值是纯黑——深色模式下标题直接消失。
                color = Ink,
                modifier = Modifier.weight(1f),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            Spacer(Modifier.width(48.dp))
        }

        Column(Modifier.padding(horizontal = 20.dp)) {

            // ---- 截图识别 ----
            //
            // 放在金额**上面**而不是下面：它是"我不想手打"的那条路径，
            // 一进来就该看得见。放下面会被键盘挡掉，而且用户已经准备手打了。
            RecognizeBar(
                state = recognize,
                enabled = llmReady && llmConfig?.supportsVision == true,
                onPick = {
                    recognize = RecognizeState.Idle
                    picker.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                },
                onConfigure = onOpenLlmSettings,
            )
            Spacer(Modifier.height(12.dp))

            // ---- 金额 ----
            Box(
                Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (amount.isEmpty()) "0.00" else amount,
                    style = AmountBig,
                    maxLines = 1,
                    color = when {
                        amount.isEmpty() -> InkFaint.copy(alpha = 0.45f)
                        isExpense -> ExpenseRed
                        else -> IncomeGreen
                    },
                )
            }

            // ---- 支出 / 收入 ----
            Row(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                SegmentedTab("支出", isExpense, Modifier.weight(1f)) { isExpense = true }
                SegmentedTab("收入", !isExpense, Modifier.weight(1f)) { isExpense = false }
            }

            // ---- 分类 ----
            // 两排一模一样的圆角按钮没有标签的话，用户根本分不清哪排是分类、
            // 哪排是账户——首次使用的人会随便点一排然后困惑。
            if (visibleCategories.isNotEmpty()) {
                Spacer(Modifier.height(18.dp))
                SectionLabel("分类")
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 0.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(visibleCategories, key = { it.id }) { c ->
                        Chip(
                            text = c.name,
                            selected = c.id == categoryId,
                            tone = categoryTone(c.name),
                            onClick = { categoryId = if (c.id == categoryId) null else c.id },
                        )
                    }
                }
            }

            // ---- 账户 ----
            if (accounts.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                SectionLabel("账户")
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 0.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(accounts, key = { it.id }) { a ->
                        Chip(
                            text = a.name,
                            selected = a.id == accountId,
                            tone = AccountTone,
                            onClick = { accountId = a.id },
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // ---- 商户 / 备注 ----
            CompactField(merchant, "商户（可留空）") { merchant = it }
            Spacer(Modifier.height(8.dp))
            CompactField(note, "备注（可留空）") { note = it }

            // ---- 时间 ----
            Spacer(Modifier.height(10.dp))
            val isToday = occurredAt.toLocalDate() == LocalDate.now()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .clickable {
                        // 补记只需要「今天/昨天」两档。完整的日期选择器会把一个
                        // 两秒钟的操作变成一屏表单，是这类 App 最常见的流失点。
                        occurredAt = if (isToday) {
                            LocalDateTime.now().minusDays(1)
                        } else {
                            LocalDateTime.now()
                        }
                    }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("时间", style = MaterialTheme.typography.bodyMedium, color = InkSoft)
                Spacer(Modifier.width(12.dp))
                Text(
                    TIME_FMT.format(occurredAt),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    if (isToday) "改成昨天" else "改回今天",
                    style = MaterialTheme.typography.labelMedium,
                    color = Brand,
                )
            }
        }

        Spacer(Modifier.weight(1f))

        // ---- 保存 ----
        Button(
            onClick = {
                val minor = amountMinor ?: return@Button
                scope.launch {
                    vm.saveTransaction(
                        amountMinor = minor,
                        isExpense = isExpense,
                        accountId = accountId,
                        categoryId = categoryId,
                        note = note,
                        merchant = merchant,
                        occurredAt = localDateTimeToMillis(occurredAt),
                    )
                    onDone()
                }
            },
            enabled = canSave,
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isExpense) ExpenseRed else IncomeGreen,
                contentColor = Color.White,
                disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                disabledContentColor = InkFaint,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .heightIn(min = 50.dp),
        ) {
            Text("保存", style = MaterialTheme.typography.titleMedium)
        }

        Spacer(Modifier.height(12.dp))
        Keypad(
            onDigit = { d ->
                amount = when {
                    d == "." && amount.contains(".") -> amount
                    d == "." && amount.isEmpty() -> "0."
                    amount == "0" -> d
                    else -> amount + d
                }
            },
            onDelete = { amount = amount.dropLast(1) },
        )
    }
}

@Composable
private fun SegmentedTab(
    text: String,
    selected: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .height(40.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) MaterialTheme.colorScheme.surface else androidx.compose.ui.graphics.Color.Transparent)
            .glassClickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium,
            color = if (selected) MaterialTheme.colorScheme.onSurface else InkSoft,
        )
    }
}

/**
 * 截图识别入口 + 结果横幅。
 *
 * 状态全部在这个组件里表达，不去改表单本身：识别失败绝不能让用户已经手输的
 * 金额消失。所以 [RecognizeState.Fail] 只显示一句话，表单一个字段都不动。
 */
@Composable
private fun RecognizeBar(
    state: RecognizeState,
    enabled: Boolean,
    onPick: () -> Unit,
    onConfigure: () -> Unit,
) {
    when (state) {
        is RecognizeState.Busy -> Banner(Brand, "正在看这张图…")

        is RecognizeState.Fail -> Banner(ExpenseRed, state.detail)

        is RecognizeState.Done -> {
            val d = state.draft
            val low = d.confidence < 0.5
            Banner(
                if (low) ExpenseRed else Brand,
                buildString {
                    append("识别出来了")
                    if (d.merchant != null) append(" · ${d.merchant}")
                    // 置信度低必须说出来。悄悄把一笔没把握的账填进表单，
                    // 用户不看就直接保存，错的账就进账本了。
                    if (low) {
                        append("。把握不大，麻烦核一下金额和分类")
                    } else {
                        append("，核一下再保存")
                    }
                },
            )
        }

        RecognizeState.Idle -> {
            if (enabled) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .glassClickable { onPick() }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Filled.ImageSearch,
                        contentDescription = null,
                        tint = Brand,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "从截图识别",
                            style = MaterialTheme.typography.bodyLarge,
                            color = Ink,
                        )
                        Text(
                            "选一张账单截图，金额和分类自动填",
                            style = MaterialTheme.typography.bodySmall,
                            color = InkFaint,
                        )
                    }
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = InkFaint,
                    )
                }
            } else {
                // 没配大模型时给的是**下一步**而不是一句"不可用"——
                // 大多数用户到这里根本不知道有这回事。
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .glassClickable { onConfigure() }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Filled.Psychology,
                        contentDescription = null,
                        tint = InkFaint,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "用大模型识别截图",
                            style = MaterialTheme.typography.bodyLarge,
                            color = Ink,
                        )
                        Text(
                            "填上你自己的 API Key，点这里去设置",
                            style = MaterialTheme.typography.bodySmall,
                            color = InkFaint,
                        )
                    }
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = InkFaint,
                    )
                }
            }
        }
    }
}

@Composable
private fun Banner(color: Color, text: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(color.copy(alpha = 0.10f))
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            Modifier
                .padding(top = 2.dp)
                .size(7.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = color)
    }
}

@Composable
private fun SectionLabel(text: String) {    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = InkFaint,
        modifier = Modifier.padding(bottom = 6.dp),
    )
}

@Composable
private fun Chip(
    text: String,
    selected: Boolean,
    tone: CategoryTone,
    onClick: () -> Unit,
) {
    // 未选中时也保留一层很淡的分类色底 + 彩色字：分类色是这个 App 里唯一
    // 跨页面一致的东西，用户在统计页认的是颜色，这里不给色就等于断掉这条线索。
    // 选中靠「底色更实 + 描边 + 加粗」三重区分，不只靠颜色（色觉障碍也要能看出）。
    val border = if (selected) 1.5.dp else 1.dp
    val borderColor = if (selected) tone.base else tone.base.copy(alpha = 0.20f)
    val container = if (selected) tone.base.copy(alpha = 0.22f) else tone.base.copy(alpha = 0.09f)
    val content = if (selected) tone.base else tone.base.copy(alpha = 0.82f)
    Row(
        modifier = Modifier
            .height(34.dp)
            .clip(RoundedCornerShape(17.dp))
            .background(container)
            .border(border, borderColor, RoundedCornerShape(17.dp))
            .glassClickable { onClick() }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        // 图标和文字同色。分类一多（内置 11 个支出 + 6 个收入）就横着滚，
        // 全靠文字的话第一屏和第二屏长得一样；图标让每一颗 chip 一眼可辨。
        // chip 高度只有 34dp，图标给 15dp 再高一点字就被挤到贴边了。
        Icon(
            imageVector = CategoryIcons.of(text),
            contentDescription = null,
            tint = content,
            modifier = Modifier.size(15.dp),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = content,
        )
    }
}

@Composable
private fun CompactField(value: String, label: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        placeholder = {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = InkFaint)
        },
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
        textStyle = MaterialTheme.typography.bodyLarge,
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surface,
            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
            focusedBorderColor = Brand,
            unfocusedBorderColor = Hairline,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun Keypad(onDigit: (String) -> Unit, onDelete: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            // 必须避开系统导航条。edge-to-edge 之后键盘贴着屏幕底边画，
            // 最后一排的「0 / ⌫」会被手势条压住——而这两个恰恰是记账时最常按的键。
            .navigationBarsPadding()
    ) {
        for (rowKeys in KEYPAD) {
            Row(Modifier.fillMaxWidth()) {
                for (key in rowKeys) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(58.dp)
                            .clickable { if (key == "del") onDelete() else onDigit(key) },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (key == "del") {
                            Icon(
                                Icons.Outlined.Backspace,
                                contentDescription = "删除",
                                tint = InkSoft,
                                modifier = Modifier.size(22.dp),
                            )
                        } else {
                            Text(
                                text = key,
                                fontSize = 24.sp,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 纯函数
// ---------------------------------------------------------------------------

/**
 * 键盘上的数字串 → 分。
 *
 * 允许中间态（"0."、"12."），解析不出就返回 null，保存按钮据此禁用。
 * 只允许一个小数点、最多两位小数——用户在这里输"12.345"几乎一定是手滑，
 * 直接存成 1235 分比让他看到自己输错更糟。
 *
 * 走 BigDecimal 不走 Double：这里算错一分钱，用户记的就是错的账。
 */
fun parseAmountToMinor(text: String): Long? {
    val s = text.trim()
    if (s.isEmpty()) return null
    // 一个可选小数点 + 最多两位小数。中间态 "12." 也放行，用户打完小数点
    // 那一刻按钮不该突然变灰。
    if (!s.matches(Regex("^\\d*\\.?\\d{0,2}$"))) return null
    // 光一个 "." 没有数字，不是一笔账。
    if (s.none { it.isDigit() }) return null
    // BigDecimal("12.") 直接抛 NumberFormatException，先把尾随小数点摘掉。
    val normalized = s.removeSuffix(".")
    if (normalized.isEmpty()) return null
    return runCatching { BigDecimal(normalized).movePointRight(2).longValueExact() }.getOrNull()
}

fun localDateTimeToMillis(dt: LocalDateTime, zone: ZoneId = ZoneId.systemDefault()): Long =
    dt.atZone(zone).toInstant().toEpochMilli()
