package kapi.ledger.ui

import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ImageSearch
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.outlined.Backspace
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kapi.core.auto.SubWallet
import kapi.core.llm.Direction
import kapi.ledger.data.CategoryEntity
import kapi.ledger.data.LedgerRepository
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
    /** 保存前的内容判重（同商户+同金额+同方向+同日）。命中不拦死，
     * 弹确认——识别同一笔两次是用户自由，但必须是他知情的选择。 */
    findDuplicateTxn: suspend (String, Long, String, Long) -> kapi.ledger.data.TxnEntity? = { _, _, _, _ -> null },
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
    var tags by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { /* 建议标签在输入时由 repo 提供（见下） */ }
    // 分类选择器（咔皮式下钻宫格）与系统键盘可见性
    var showCategoryPicker by remember { mutableStateOf(false) }
    var pickerMajor by remember { mutableStateOf<CategoryEntity?>(null) }
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0

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
        //
        // imePadding：edge-to-edge 下 adjustResize 不会自动给键盘让位，
        // 商户/备注拉起系统键盘时它会整个盖在内容上——"键盘显示不全/内容
        // 被吃掉"的根源。加 imePadding 后窗口内容随键盘上收。
        modifier = modifier
            .fillMaxSize()
            .imePadding(),
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

        // ---- 表单中段：可滚动 ----
        // 数字键盘必须**完整**可见（它是最常按的东西），所以它和保存键钉在
        // 底部，中段字段放进修不过的滚动区——内容总高超过一屏时滚上去看，
        // 键盘永远在。上一版没有这个分区，内容一多键盘就被顶出屏幕外。
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {

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

            // ---- 分类（咔皮式一行 + 下钻宫格）----
            // 原来是横滑小 chips 墙：50 多个分类一屏只露 4 颗，滑动找类目
            // 要划好几屏。咔皮的构造是一行式——「分类  支出：餐饮-快餐 ›」，
            // 点击下钻进宫格选择器。这里照搬：行显示已选路径，宫格两级
            // （大类 emoji 瓦片 → 细类），选中即回填。
            if (visibleCategories.isNotEmpty()) {
                val selectedCat = visibleCategories.firstOrNull { it.id == categoryId }
                val selectedPath = selectedCat?.let { c ->
                    val parent = visibleCategories.firstOrNull { it.id == c.parentId }
                    if (parent != null) parent.name + "/" + c.name else c.name
                }
                Spacer(Modifier.height(18.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .clickable {
                            pickerMajor = null
                            showCategoryPicker = true
                        }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("分类", style = MaterialTheme.typography.bodyMedium, color = InkSoft)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        selectedPath ?: "未选择",
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (selectedCat != null) MaterialTheme.colorScheme.onSurface else InkFaint,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = InkFaint,
                    )
                }
            }

            // ---- 账户 ----
            // 子钱包（余额宝/零钱/花呗…）是自动记账铺的平台行，手动记一笔
            // 默认**不显示**——十颗芯片的噪音（2026-10-03 用户反馈）。
            // 默认只列主账户（现金/银行卡/支付宝/微信）+ 用户自建；当前选中
            // 的恰好是子钱包时（识别回填）它单独保留；「子钱包」钮展开全部。
            if (accounts.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                SectionLabel("账户")
                val subAccounts = accounts.filter { it.id in SubWallet.ACCOUNT_IDS }
                val primary = accounts.filter { it.id !in SubWallet.ACCOUNT_IDS }
                val selectedIsSub = subAccounts.any { it.id == accountId }
                var showSubWallets by remember { mutableStateOf(selectedIsSub) }
                val visibleAccounts = if (showSubWallets) accounts else primary
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 0.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(visibleAccounts, key = { it.id }) { a ->
                        Chip(
                            text = a.name,
                            selected = a.id == accountId,
                            tone = AccountTone,
                            onClick = { accountId = a.id },
                        )
                    }
                    if (subAccounts.isNotEmpty() && !showSubWallets) {
                        item(key = "sub-toggle") {
                            Chip(
                                text = "子钱包 +${subAccounts.size}",
                                selected = false,
                                tone = AccountTone,
                                onClick = { showSubWallets = true },
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // ---- 商户 / 备注 ----
            CompactField(merchant, "商户（可留空）") { merchant = it }
            Spacer(Modifier.height(8.dp))
            CompactField(note, "备注（可留空）") { note = it }

            // ---- 标签（可选，逗号分隔；跨分类聚合用）----
            Spacer(Modifier.height(8.dp))
            CompactField(tags, "标签（可选，逗号分隔）") { tags = it }

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

        // ---- 保存（钉底，永远可见）----
        fun doSave(minor: Long) {
            scope.launch {
                vm.saveTransaction(
                    amountMinor = minor,
                    isExpense = isExpense,
                    accountId = accountId,
                    categoryId = categoryId,
                    note = note,
                    merchant = merchant,
                    occurredAt = localDateTimeToMillis(occurredAt),
                    tags = tags.split("，", ",").map { it.trim() }.filter { it.isNotEmpty() },
                )
                onDone()
            }
        }

        var showDupConfirm by remember { mutableStateOf(false) }
        var dupTxn by remember { mutableStateOf<kapi.ledger.data.TxnEntity?>(null) }
        Button(
            onClick = {
                val minor = amountMinor ?: return@Button
                scope.launch {
                    // 保存前查重。读屏路径早就拦自动重复了，这里补上手动/
                    // 截图识别路径：识别出的账单和已有记录同商户+同金额+
                    // 同发生日时，弹确认而不是默默记两笔（2026-10-03 用户报）。
                    val dup = findDuplicateTxn(
                        merchant.trim(), minor,
                        if (isExpense) "EXPENSE" else "INCOME",
                        localDateTimeToMillis(occurredAt),
                    )
                    if (dup != null) {
                        dupTxn = dup
                        showDupConfirm = true
                    } else {
                        doSave(minor)
                    }
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

        if (showDupConfirm && dupTxn != null) {
            val dupTime = dupTxn!!.let {
                java.time.Instant.ofEpochMilli(it.occurredAt)
                    .atZone(java.time.ZoneId.systemDefault())
                    .format(java.time.format.DateTimeFormatter.ofPattern("M月d日 HH:mm"))
            }
            AlertDialog(
                onDismissRequest = { showDupConfirm = false },
                title = { Text("账本里已有这笔", color = Ink) },
                text = {
                    Text(
                        "已有一笔「${dupTxn!!.merchant ?: "未命名"}」在 $dupTime 记过 " +
                            Money.toYuanDisplay(dupTxn!!.amountMinor) + " 元，和这笔同商户、同金额、同一天。\n\n" +
                            "是同一笔就不用再记；确实又消费了一次，再保存。",
                        color = InkSoft,
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        showDupConfirm = false
                        amountMinor?.let(::doSave)
                    }) { Text("确实又一笔，保存", color = Brand) }
                },
                dismissButton = {
                    TextButton(onClick = { showDupConfirm = false }) { Text("不记了", color = InkSoft) }
                },
            )
        }

        // 自定义数字键盘与系统键盘互斥：商户/备注拉起系统键盘时把数字盘
        // 收起来——两块键盘叠在一屏，既挤又会让系统键盘显示不全。
        if (!imeVisible) {
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
        } else {
            Spacer(Modifier.height(8.dp))
        }
    }

    if (showCategoryPicker) {
        CategoryPickerSheet(
            categories = visibleCategories,
            isExpense = isExpense,
            selectedId = categoryId,
            onPick = { categoryId = it?.id },
            onDismiss = { showCategoryPicker = false },
            // 自建分类落库后拿不回完整行（Flow 还没重发），按已知字段
            // 拼一个展示行——onPick 只用 id/名字/父级，够。
            onCreate = { name, parentId, icon ->
                val kind = if (isExpense) "EXPENSE" else "INCOME"
                vm.createCategory(name, kind, parentId, icon)?.let { id ->
                    CategoryEntity(
                        id = id,
                        ledgerId = LedgerRepository.DEFAULT_LEDGER_ID,
                        parentId = parentId,
                        name = name,
                        icon = icon,
                        kind = kind,
                        sortOrder = 99,
                    )
                }
            },
        )
    }
}

/**
 * 分类选择器（咔皮式两级宫格，ModalBottomSheet 承载）：
 * 第一级 = 支出/收入的全部大类（emoji 瓦片，4 列）；
 * 点开有细类的大类 → 第二级细类宫格（顶部带返回条），也可点「全部 ××」
 * 只记到大类。选中即回填并收起。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CategoryPickerSheet(
    categories: List<CategoryEntity>,
    isExpense: Boolean,
    selectedId: String?,
    onPick: (CategoryEntity?) -> Unit,
    onDismiss: () -> Unit,
    onCreate: suspend (String, String?, String?) -> CategoryEntity?,
) {
    val sheetState = rememberModalBottomSheetState()
    val scope = rememberCoroutineScope()
    val majors = categories.filter { it.parentId == null }
    var currentMajor by remember { mutableStateOf<CategoryEntity?>(null) }
    val minors = currentMajor?.let { m -> categories.filter { it.parentId == m.id } } ?: emptyList()
    var showCustom by remember { mutableStateOf(false) }
    var customName by remember { mutableStateOf("") }
    var customEmoji by remember { mutableStateOf<String?>(null) }

    fun submitCustom() {
        val name = customName.trim()
        if (name.isEmpty()) return
        scope.launch {
            val created = onCreate(name, currentMajor?.id, customEmoji)
            if (created != null) {
                onPick(created)
                onDismiss()
            }
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            // 标题 / 返回条
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                if (currentMajor != null) {
                    IconButton(onClick = { currentMajor = null }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回大类", tint = InkSoft)
                    }
                }
                Text(
                    text = when {
                        currentMajor != null -> currentMajor!!.name + " · 细分类"
                        else -> if (isExpense) "支出分类" else "收入分类"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = Ink,
                    modifier = Modifier.weight(1f),
                )
            }
            // 自定义输入区：点了「＋ 自定义」才出现。先挑一个图标
            //（可选，不挑且名字撞不上内置表时才落到问号），再起名字。
            if (showCustom) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "挑个图标（可跳过）",
                    style = MaterialTheme.typography.labelSmall,
                    color = InkSoft,
                )
                Spacer(Modifier.height(6.dp))
                LazyVerticalGrid(
                    columns = GridCells.Fixed(8),
                    modifier = Modifier.heightIn(max = 132.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(CategoryEmoji.CUSTOM_CHOICES.size) { i ->
                        val e = CategoryEmoji.CUSTOM_CHOICES[i]
                        val emojiSelected = customEmoji == e
                        Text(
                            text = e,
                            fontSize = 20.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(
                                    if (emojiSelected) BrandSoft
                                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                                )
                                .clickable { customEmoji = if (emojiSelected) null else e }
                                .padding(vertical = 6.dp),
                        )
                    }
                }
                // 48 个候选总有不贴切的：留一个任意输入——系统键盘的
                // emoji 面板里随便挑，存进 icon 的都是一个字符串，展示
                // 链路不区分来源。字数截到 8：emoji 有多码点序列（ZWIJ
                // 肤色/家庭组合），按字符数截才不会把一个表情截成残块。
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = customEmoji ?: "",
                    onValueChange = { customEmoji = it.take(8).ifEmpty { null } },
                    placeholder = { Text("没有合适的？输入任意表情", style = MaterialTheme.typography.bodySmall) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = customName,
                        onValueChange = { customName = it.take(20) },
                        placeholder = { Text("分类名字，比如 猫主子", style = MaterialTheme.typography.bodySmall) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    TextButton(
                        onClick = { submitCustom() },
                        enabled = customName.isNotBlank(),
                    ) { Text("建并选中", color = Brand) }
                }
            }
            Spacer(Modifier.height(8.dp))
            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 28.dp),
            ) {
                if (currentMajor == null) {
                    items(majors, key = { it.id }) { m ->
                        CategoryTile(
                            emoji = CategoryEmoji.emojiOf(m.name, m.icon),
                            name = m.name,
                            selected = selectedId == m.id,
                            onClick = {
                                val hasKids = categories.any { it.parentId == m.id }
                                if (hasKids) currentMajor = m
                                else {
                                    onPick(m)
                                    onDismiss()
                                }
                            },
                        )
                    }
                    // 「＋ 自定义」：新建后自动选中并回填。顶级自建分类挂根。
                    item(key = "custom") {
                        CategoryTile(
                            emoji = null,
                            name = "自定义",
                            selected = false,
                            onClick = { showCustom = true },
                        )
                    }
                } else {
                    // 「全部 大类」瓦片：只记到大类
                    item(key = currentMajor!!.id + "-all") {
                        CategoryTile(
                            emoji = CategoryEmoji.emojiOf(currentMajor!!.name, currentMajor!!.icon),
                            name = "全部" + currentMajor!!.name,
                            selected = selectedId == currentMajor!!.id,
                            onClick = {
                                onPick(currentMajor)
                                onDismiss()
                            },
                        )
                    }
                    items(minors, key = { it.id }) { kid ->
                        CategoryTile(
                            emoji = CategoryEmoji.emojiOf(kid.name, kid.icon)
                                ?: CategoryEmoji.emojiOf(currentMajor!!.name, currentMajor!!.icon),
                            name = kid.name,
                            selected = selectedId == kid.id,
                            onClick = {
                                onPick(kid)
                                onDismiss()
                            },
                        )
                    }
                    item(key = "custom-minor") {
                        CategoryTile(
                            emoji = null,
                            name = "自定义",
                            selected = false,
                            onClick = { showCustom = true },
                        )
                    }
                }
            }
        }
    }
}

/** 宫格瓦片：emoji 圆底 + 名称。选中 = 品牌描边 + 浅蓝底。 */
@Composable
private fun CategoryTile(
    emoji: String?,
    name: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(
                if (selected) BrandSoft
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
            )
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 4.dp),
    ) {
        Box(
            Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(Color.White),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = emoji ?: "❓",
                fontSize = 20.sp,
            )
        }
        Spacer(Modifier.height(5.dp))
        Text(
            text = name,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) BrandDeep else Ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
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
        //
        // 有 emoji（对标咔皮的彩色分类图标）优先 emoji——🍳🧋⛽ 的辨识度比
        // 一排同款单色线性图标高得多。emoji 表没收录的名字（账户四件套、
        // 自建分类）退回矢量图标，别硬塞问号。
        val emoji = CategoryEmoji.emojiOf(text)
        if (emoji != null) {
            Text(
                text = emoji,
                fontSize = 15.sp,
                maxLines = 1,
            )
        } else {
            Icon(
                imageVector = CategoryIcons.of(text),
                contentDescription = null,
                tint = content,
                modifier = Modifier.size(15.dp),
            )
        }
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
