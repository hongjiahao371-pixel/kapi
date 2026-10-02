package kapi.ledger.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Sync
import android.provider.Settings
import android.content.Intent
import android.net.Uri
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kapi.ledger.llm.LlmStore
import kapi.ledger.sync.SyncCoordinator
import kapi.ledger.sync.SyncEngine
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.Switch
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kapi.ledger.auto.AutoPermissions
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import kapi.ledger.auto.explain
import kapi.ledger.data.LedgerImportExport
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kapi.ledger.BuildConfig

private val STAMP = SimpleDateFormat("M月d日 HH:mm", Locale.getDefault())

/**
 * 设置 · 同步 + 大模型。
 *
 * 配对流程是三步而不是一步：探测 → 配对。合并成一步的话，"NAS 没开机"和
 * "配对码过期了"会共用一条错误文案，而这两件事用户接下来要做的事完全相反。
 */
@Composable
fun SyncSettingsScreen(
    vm: LedgerViewModel,
    onBack: () -> Unit,
    contentPadding: PaddingValues,
    onOpenLlmSettings: () -> Unit,
    onOpenAccounts: () -> Unit = {},
    llmStore: LlmStore? = null,
    autoBookStore: kapi.ledger.auto.AutoBookStore? = null,
    onOpenDrafts: () -> Unit = {},
    transfer: LedgerImportExport? = null,
    deviceId: String = "",
    // 和底栏的磨砂副本共用同一个滚动状态，否则玻璃里磨出来的是另一个位置。
    scrollState: ScrollState = rememberScrollState(),
    modifier: Modifier = Modifier,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var showPair by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    Column(
        // 刻意**不铺底色**：不透明背景会把 KapiRoot 的液态背景整个盖死，
        // 玻璃材质就变成"半透明的灰"，一点光斑都透不出来。整屏不铺底、
        // 只在卡片上铺，是液态玻璃唯一能生效的铺法。
        modifier = modifier.fillMaxSize(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(Modifier.width(48.dp))
            Text(
                "设置",
                style = MaterialTheme.typography.titleLarge,
                // 不给 color 的话它继承 LocalContentColor，而这一层没有 Surface
                // 兜底，默认值是**纯黑**——深色模式下就是黑底黑字，标题直接消失。
                // 另外三个屏的标题都显式给了 Ink，这里是唯一漏掉的。
                color = Ink,
                modifier = Modifier.weight(1f),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            Spacer(Modifier.width(48.dp))
        }

        Column(
            Modifier
                .weight(1f)
                .verticalScroll(scrollState)
                .padding(horizontal = 16.dp)
        ) {
            // ---- NAS 同步 ----
            SettingsCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(
                                if (state.isPaired) BrandSoft else MaterialTheme.colorScheme.surfaceVariant
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = if (state.isPaired) Icons.Filled.Dns else Icons.Filled.CloudOff,
                            contentDescription = null,
                            tint = if (state.isPaired) BrandDeep else InkFaint,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("NAS 同步", style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = syncStatusLine(state, busy),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (state.lastError != null) ExpenseRed else InkSoft,
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))

                if (state.isPaired) {
                    Button(
                        onClick = {
                            busy = true
                            message = null
                            scope.launch {
                                message = when (val r = vm.syncNow()) {
                                    is SyncEngine.SyncResult.Success ->
                                        "同步完成，推送 ${r.pushed} 笔，拉取 ${r.pulled} 条"
                                    is SyncEngine.SyncResult.Rejected ->
                                        "服务端拒绝了 ${r.count} 个字段，界面会显示服务端的值"
                                    is SyncEngine.SyncResult.NotPaired -> "还没配 NAS"
                                    is SyncEngine.SyncResult.Failed -> r.failure.detail
                                }
                                busy = false
                            }
                        },
                        enabled = !busy,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = BrandDeep),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 46.dp),
                    ) {
                        Icon(Icons.Filled.Sync, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(if (busy) "同步中…" else "立即同步")
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = {
                            vm.disconnect()
                            message = "已断开。本机的账还在，只是不会再推给 NAS。"
                        },
                        enabled = !busy,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 46.dp),
                    ) { Text("断开连接") }
                } else {
                    Text(
                        "没连 NAS 也能正常记账。连接之后账单会同步到你自己的机器上，" +
                            "不上传任何云端。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = InkSoft,
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = { showPair = true },
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = BrandDeep),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 46.dp),
                    ) { Text("连接 NAS") }
                }
            }

            if (message != null) {
                Spacer(Modifier.height(10.dp))
                Text(
                    message!!,
                    style = MaterialTheme.typography.bodyMedium,
                    color = BrandDeep,
                )
            }

            // ---- 账户管理 ----
            // 放在自动记账相关区块之前：账户是账本骨架（自动划转的两端、
            // 花呗消费的落点），比大模型这类增强功能更靠前。
            Spacer(Modifier.height(16.dp))
            SettingsCard {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.glassClickable { onOpenAccounts() },
                ) {
                    Box(
                        Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(BrandSoft),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Filled.AccountBalanceWallet,
                            contentDescription = null,
                            tint = Brand,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("账户管理", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "子钱包 · 划转 · 新增银行卡",
                            style = MaterialTheme.typography.bodySmall,
                            color = InkSoft,
                        )
                    }
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = InkFaint,
                    )
                }
            }

            // ---- 大模型 ----
            Spacer(Modifier.height(16.dp))
            //
            // 判据必须是 `isConfigured` 而不是「对象是不是 null」：
            // `LlmStore.load()` 永远返回一个对象（没配置时是全空的默认值），
            // 按 null 判会让**什么都没填**的新用户看到红色的云端告警和
            // "截图会发给你填的这个地址"——他在任何一个地址都没填过。
            val llm = remember(llmStore) { llmStore?.load() }
            val llmOn = llm?.isConfigured == true
            val llmLocal = llmOn && llm.isLocalEndpoint()
            SettingsCard {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.glassClickable { onOpenLlmSettings() },
                ) {
                    Box(
                        Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(
                                when {
                                    !llmOn -> MaterialTheme.colorScheme.surfaceVariant
                                    llmLocal -> BrandSoft
                                    else -> ExpenseRed.copy(alpha = 0.12f)
                                }
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            when {
                                !llmOn -> Icons.Filled.Psychology
                                llmLocal -> Icons.Filled.Lock
                                else -> Icons.Filled.Public
                            },
                            contentDescription = null,
                            tint = when {
                                !llmOn -> InkFaint
                                llmLocal -> Brand
                                else -> ExpenseRed
                            },
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("大模型识别", style = MaterialTheme.typography.titleMedium)
                        Text(
                            when {
                                !llmOn -> "还没配置"
                                llmLocal -> "${llm.model} · 在你自己机器上"
                                else -> "${llm.model} · 端点在云端"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = when {
                                !llmOn -> InkSoft
                                llmLocal -> Brand
                                else -> ExpenseRed
                            },
                        )
                    }
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = InkFaint,
                    )
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    when {
                        !llmOn ->
                            "填上之后可以用大模型识别截图；不填也能正常记账，" +
                                "通知栏文本会走本地规则识别，不联网也不消耗额度。"
                        llmLocal ->
                            "截图不经过任何第三方，识别在你自己的机器上完成。"
                        else ->
                            "账单仍然只同步到 NAS，但截图会发给你填的这个地址。" +
                                "介意的话可以在 NAS 上跑个本地端点。"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = InkSoft,
                )
            }

            // ---- 导入 / 导出 ----
            //
            // 刻意放在自动记账**前面**。四张屏共用的 contentPadding 底部只留
            // 30dp+导航栏，为的是让内容从玻璃底下穿过去、玻璃才有东西可磨；
            // 代价是滚到底时**最后一张卡的最后一组控件会压在胶囊下面**。
            // 放最后一张就正好踩中这个问题（导入按钮只露出 4px）。
            // 往前挪一位，滚到底时它已经在屏幕中段，怎么滚都点得到。
            if (transfer != null) {
                Spacer(Modifier.height(16.dp))
                TransferSection(transfer = transfer, deviceId = deviceId)
            }

            // ---- 自动记账 ----
            if (autoBookStore != null) {
                Spacer(Modifier.height(16.dp))
                AutoBookSection(
                    store = autoBookStore,
                    onOpenDrafts = onOpenDrafts,
                )
            }

            Spacer(Modifier.height(28.dp))
        }
    }

    if (showPair) {
        PairDialog(
            busy = busy,
            onDismiss = { if (!busy) showPair = false },
            onSubmit = { url, code ->
                busy = true
                scope.launch {
                    when (val outcome = vm.pair(url, code)) {
                        is SyncCoordinator.PairOutcome.Paired -> {
                            message = "已连接，NAS 上有 ${outcome.rowsOnServer} 笔"
                            showPair = false
                        }
                        is SyncCoordinator.PairOutcome.Failed -> message = outcome.message
                    }
                    busy = false
                }
            },
        )
    }
}

private fun syncStatusLine(state: LedgerUiState, busy: Boolean): String = when {
    busy -> "正在同步…"
    !state.isPaired -> "未连接"
    state.lastError != null -> "上次同步失败"
    state.pendingCount > 0 -> "${state.pendingCount} 笔待同步"
    state.lastSyncAt > 0 -> "上次同步 ${formatSyncStamp(state.lastSyncAt)}"
    else -> "已连接，还没有同步过"
}

/**
 * 设置页的卡片。
 *
 * 换成 [GlassSurface] 而不是 `Surface`：这里原来的 `colorScheme.surface` 是不透明
 * 白，卡在液态背景上就是一块"贴纸"，玻璃材质全白费了。半透明之后光斑能从卡片
 * 底下透上来，设置页也跟着有层次。
 */
@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    SurfaceCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(Modifier.padding(16.dp), content = content)
    }
}

@Composable
private fun PairDialog(
    busy: Boolean,
    onDismiss: () -> Unit,
    onSubmit: (String, String) -> Unit,
) {
    var url by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    // 配对码是 6 位数字。位数对了才能提交，比点了没反应再报错友好得多。
    val codeValid = code.length == 6 && code.all { it.isDigit() }
    val urlValid = url.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(20.dp),
        title = { Text("连接 NAS") },
        text = {
            Column {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("服务器地址") },
                    placeholder = { Text("192.168.1.10:8787") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Brand,
                        unfocusedBorderColor = Hairline,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "NAS 容器日志里会打印局域网地址和配对码，直接抄过来。",
                    style = MaterialTheme.typography.bodySmall,
                    color = InkFaint,
                )
                Spacer(Modifier.height(14.dp))
                OutlinedTextField(
                    value = code,
                    onValueChange = { input -> code = input.filter { it.isDigit() }.take(6) },
                    label = { Text("配对码") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Brand,
                        unfocusedBorderColor = Hairline,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "地址带不带 http:// 都认，端口别漏。",
                    style = MaterialTheme.typography.bodySmall,
                    color = InkFaint,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSubmit(url, code) },
                enabled = urlValid && codeValid && !busy,
            ) { Text(if (busy) "连接中…" else "连接", color = BrandDeep) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text("取消", color = InkSoft) }
        },
    )
}

/** 把毫秒时间戳格式化成「9月29日 21:41」。零值显示成横线而不是 1970 年。 */
fun formatSyncStamp(millis: Long): String =
    if (millis <= 0L) "—" else STAMP.format(Date(millis))

/**
 * 导入 / 导出。
 *
 * ## 为什么用 SAF 而不是直接写文件 + FileProvider
 *
 * SAF（`ACTION_CREATE_DOCUMENT` / `ACTION_OPEN_DOCUMENT`）让**系统文件选择器**
 * 替我们问用户存哪、开哪个。三个好处都是硬的：
 *  1. **不要任何存储权限**。`READ_EXTERNAL_STORAGE` 是那种用户一看就警觉、
 *     审核也盯着的权限，而这里完全不需要。
 *  2. 不用自己造 `FileProvider` + `<paths>` + 中间文件再挪过去那一套，
 *     也就没有"临时文件留在 cache 里没清掉"的问题。
 *  3. 用户可以存到微信、网盘、下载目录——"发给自己"这种最常见的需求
 *     直接在选择器里就完成了。
 *
 * ## 三个按钮不是三选一
 *
 * CSV 给**人**看（Excel / Numbers，导出列名是中文、金额带正负号可直接求和）；
 * JSON 给**程序**看（完整保真、能原样导回来）；导入只收 JSON。
 * CSV 往返是有损的——分类名对不上就没了，而且"什么是转账"在文本里没有表示。
 * 所以不提供 CSV 导入：给一个必然半途出错的功能，比不给更糟。
 */
@Composable
private fun TransferSection(
    transfer: LedgerImportExport,
    deviceId: String,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<LedgerImportExport.ExportResult.Ok?>(null) }

    // 提示**就地显示**，不往页面顶部的 message 里塞。
    // 顶部那个 message 是给"连接 NAS / 立即同步"用的，渲染在 NAS 卡片下面；
    // 这一张卡在页面最底部，用户点导出时正盯着屏幕下半部分——
    // 提示跑到屏幕外，表现就是"点了没反应，重试三次，然后卸了 App"。
    // 动作的反馈必须出现在动作旁边。
    var note by remember { mutableStateOf<String?>(null) }
    var noteIsError by remember { mutableStateOf(false) }

    fun say(text: String, isError: Boolean = false) {
        note = text
        noteIsError = isError
    }

    fun writeTo(uri: android.net.Uri, text: String, ok: String) {
        scope.launch {
            busy = true
            val msg = withContext(Dispatchers.IO) {
                try {
                    // 少一个 ?: 的话 openOutputStream 返回 null 时后面就是
                    // "成功"——文件根本没写，界面还在说导出完成
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(text.toByteArray(Charsets.UTF_8))
                    } ?: return@withContext "这个位置写不进去，换一个试试"
                    ok
                } catch (t: Throwable) {
                    // 自定义异常要在兜底之前处理：显示"java.lang.SecurityException"
                    // 还是"没有权限写这个位置"，对用户是两件完全不同的事
                    if (t is SecurityException) "没有权限写这个位置，换一个试试"
                    else "导出没成功：${t.message?.take(40) ?: "未知原因"}"
                }
            }
            busy = false
            say(msg, isError = true)
        }
    }

    /**
     * 导出用同一个 launcher，MIME 固定成通配，**文件名**在 callback 之前
     * 算好存在 [pending] 里。分两个 launcher 的话每加一种格式就多一份
     * 几乎一样的代码，而它们唯一不同的就是那个后缀。
     *
     * （别在这段注释里直接写 MIME 那个通配串：它结尾是 `*` 加斜杠，
     * 会被当成块注释的结束标记，编译器只会报"语法错误"，指不到真正的行。）
     *
     * 文件名必须在**点按钮那一刻**算，不能在重组时算——否则导过一次之后
     * 每次重组建议名都跳一次，用户看到的和实际存下的对不上。
     */
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("*/*")
    ) { uri ->
        val r = pending
        pending = null
        if (uri == null) return@rememberLauncherForActivityResult   // 用户按了返回
        if (r == null) return@rememberLauncherForActivityResult
        writeTo(uri, r.text, "导出 ${r.rowCount} 笔到 ${r.fileName}")
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            val msg = withContext(Dispatchers.IO) {
                val text = try {
                    context.contentResolver.openInputStream(uri)?.use {
                        it.readBytes().toString(Charsets.UTF_8)
                    }
                } catch (_: Throwable) {
                    null
                }
                when {
                    text == null -> "没导入：这个文件读不出来，可能已损坏"
                    else -> when (val r = transfer.importJson(text, deviceId)) {
                        is LedgerImportExport.ImportResult.Ok ->
                            // 跳过的条数**必须**说出来。用户导两遍之后如果只看到
                            // "导入完成"，他会以为去重没生效，于是继续导。
                            if (r.skipped > 0)
                                "导入 ${r.added} 笔，${r.skipped} 笔和已有的重复已跳过"
                            else "导入 ${r.added} 笔"

                        is LedgerImportExport.ImportResult.Failed -> "没导入：${r.why}"
                    }
                }
            }
            busy = false
            say(msg, isError = true)
        }
    }

    /**
     * 账单导入的结果文案。
     *
     * **三类跳过必须分开说**，因为它们的性质完全不同：
     * 重复 = 你导过了；中性交易 = 本来就不算账；交易关闭 = 钱没出去。
     * 合成一句"跳过 5 笔"的话，用户会以为账单里有 5 笔坏了，
     * 于是反复换文件、反复导。
     *
     * 声明必须在两个 launcher **之前**：Kotlin 的局部函数和 val 一样，
     * 从声明处往下才可见，写在后面就是 unresolved reference。
     */
    fun describeBill(r: LedgerImportExport.ImportResult.Ok): String {
        val sb = StringBuilder()
        sb.append("从${r.from}账单导入 ${r.added} 笔")
        if (r.skipped > 0) sb.append("，${r.skipped} 笔和已有的重复已跳过")
        val extra = ArrayList<String>()
        if (r.skippedNeutral > 0) extra += "${r.skippedNeutral} 笔充值/提现/还款不算账"
        if (r.skippedBadStatus > 0) extra += "${r.skippedBadStatus} 笔交易关闭没记"
        if (r.skippedBroken > 0) extra += "${r.skippedBroken} 笔格式不对读不出来"
        if (extra.isNotEmpty()) sb.append("；另有 ").append(extra.joinToString("、"))
        return sb.toString()
    }

    /**
     * 账单导入。传的是**原始字节**，不是字符串。
     *
     * 支付宝账单是 GBK 编码，先 `toString(UTF_8)` 再解析的话，
     * 中文表头在进解析器之前就已经变成乱码了——而解析器找不到表头时
     * 只能报"没找到账单表头"，用户完全看不出真正原因是编码。
     */
    val billLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            val msg = withContext(Dispatchers.IO) {
                val bytes = try {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                } catch (_: Throwable) {
                    null
                }
                when {
                    bytes == null -> "没导入：这个文件读不出来，可能已损坏"
                    else -> when (val r = transfer.importBankBill(bytes, deviceId)) {
                        is LedgerImportExport.ImportResult.Ok -> describeBill(r)
                        is LedgerImportExport.ImportResult.Failed -> "没导入：${r.why}"
                    }
                }
            }
            busy = false
            say(msg, isError = true)
        }
    }

    fun startExport(json: Boolean) {
        scope.launch {
            busy = true
            val r = if (json) transfer.exportJson() else transfer.exportCsv()
            busy = false
            when (r) {
                is LedgerImportExport.ExportResult.Empty -> say("还没有账可以导出", isError = true)
                is LedgerImportExport.ExportResult.Ok -> {
                    pending = r
                    exportLauncher.launch(r.fileName)
                }
            }
        }
    }

    SettingsCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("导入导出", style = MaterialTheme.typography.titleMedium, color = Ink)
                Text(
                    "账目一直是你自己的。导出来存到哪、发去哪，你说了算",
                    style = MaterialTheme.typography.bodySmall,
                    color = InkSoft,
                )
            }
        }

        Spacer(Modifier.height(14.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(
                onClick = { startExport(false) },
                enabled = !busy,
                modifier = Modifier.weight(1f),
            ) { Text("导出 CSV", color = BrandDeep) }

            OutlinedButton(
                onClick = { startExport(true) },
                enabled = !busy,
                modifier = Modifier.weight(1f),
            ) { Text("导出 JSON", color = BrandDeep) }
        }

        Spacer(Modifier.height(10.dp))

        OutlinedButton(
            onClick = { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (busy) "处理中…" else "从 JSON 文件导入", color = BrandDeep)
        }

        Spacer(Modifier.height(10.dp))

        // 账单导入单独一档，不和 JSON 挤在一行。
        // 它的说明文字长（三行导出步骤），挤在两个等宽按钮里会被压成两行还截断。
        OutlinedButton(
            onClick = { billLauncher.launch(arrayOf("text/csv", "text/plain", "text/comma-separated-values", "*/*")) },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (busy) "处理中…" else "导入微信 / 支付宝账单", color = BrandDeep)
        }

        Spacer(Modifier.height(8.dp))
        Text(
            "微信：我 → 服务 → 钱包 → 账单 → 右上「…」→ 常见问题 → 下载账单 → 选「用于个人对账」，" +
                "微信会发到邮箱，解压码也在通知里。\n" +
                "支付宝：电脑浏览器打开 alipay.com 交易记录，下载查询结果。\n" +
                "充值、提现、信用卡还款这类钱在账户间搬家的不算账，会自动跳过并告诉你跳了多少。",
            style = MaterialTheme.typography.bodySmall,
            color = InkFaint,
        )

        Spacer(Modifier.height(10.dp))
        Text(
            "CSV 丢进中文表格能直接求和，JSON 能原样导回来。导入会自动跳过重复的，" +
                "同一个文件导两次不会变成两倍。",
            style = MaterialTheme.typography.bodySmall,
            color = InkFaint,
        )

        // 反馈紧跟在按钮下面。放在卡片最末尾而不是按钮正中间，
        // 是因为出错时（没账可导、格式不对）那句话往往比按钮还长，
        // 挤在按钮中间会把两个等宽按钮挤变形。
        if (note != null) {
            Spacer(Modifier.height(12.dp))
            Surface(
                color = if (noteIsError) ExpenseRed.copy(alpha = 0.12f) else BrandSoft,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    note!!,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (noteIsError) ExpenseRed else BrandDeep,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
        }
    }

    // ---- 版本号：放在页面最末尾，单独一小行
    //
    // 存在的理由是"装的是不是最新包"这个问题必须**一眼能答**。
    // 在 versionCode 写死成 1 的那段时间里，它既证明不了什么、界面上也看不见，
    // 于是每次装完包都要去 dex 里搜特征串才能确认——这一步人做不了，只能靠翻聊天记录。
    // versionCode 现在按天递增（见 app/build.gradle.kts），用户报一个数字过来，
    // 就能直接判断是不是新包，不用再拉 APK 验字节。
    Spacer(Modifier.height(12.dp))
    Text(
        buildString {
            append("版本 ")
            append(BuildConfig.VERSION_NAME)
            append("（")
            append(BuildConfig.VERSION_CODE)
            append("）")
            if (BuildConfig.DEBUG) append(" · 调试版")
        },
        style = MaterialTheme.typography.bodySmall,
        color = InkFaint,
    )
}

/**
 * 自动记账设置。
 *
 * 这一块要解决的核心矛盾：**权限是给系统的高危开关，功能是用户最省事的期待**。
 * 用户看到"无障碍"这三个字的第一反应是"这玩意能替我操作手机"，然后关掉。
 * 所以顺序必须是：先说清"只读屏幕上的文字、识别在本机"，再给开关，最后才说风险。
 *
 * 2026-10-01 起这里只剩一行。原设计是两条通路并排（通知栏 + 无障碍），并特意把
 * 通知栏标成主路、无障碍标成补充——那个判断当时有依据，但两条都删了之后
 * "补充"这个说法就成了自我贬低：它现在是**唯一**的通路，界面上不该这么自称。
 */
@Composable
private fun AutoBookSection(
    store: kapi.ledger.auto.AutoBookStore,
    onOpenDrafts: () -> Unit,
) {
    val context = LocalContext.current
    var a11yOn by remember { mutableStateOf(store.accessibilityEnabled) }

    // 从系统设置跳回来时状态会变，所以每次回到前台重新读一次。
    // 读的是**系统真实状态**而不是我们自己存的开关——那才是用户看到的东西。
    var perm by remember { mutableStateOf(AutoPermissions.read(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) perm = AutoPermissions.read(context)
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    SettingsCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("自动记账", style = MaterialTheme.typography.titleMedium, color = Ink)
                Text(
                    "读支付成功页面上的文字，识别在手机本地完成，不联网、不花额度",
                    style = MaterialTheme.typography.bodySmall,
                    color = InkSoft,
                )
            }
            Spacer(Modifier.width(10.dp))
            Text(
                if (perm.anythingWorks) "已开启" else "未开启",
                style = MaterialTheme.typography.labelMedium,
                color = if (perm.anythingWorks) Brand else InkFaint,
            )
        }

        Spacer(Modifier.height(16.dp))

        PermRow(
            icon = Icons.Outlined.Visibility,
            title = "读支付页面",
            subtitle = perm.explain(),
            granted = perm.accessibilityLive,
            enabled = a11yOn,
            onToggle = { a11yOn = it; store.accessibilityEnabled = it },
            onOpenSettings = { AutoPermissions.openAccessibilitySettings(context) },
        )

        Spacer(Modifier.height(12.dp))
        Text(
            "无障碍只读屏幕上的文字，不会点任何按钮、不会滑动屏幕、不会替你在任何 App 里操作。" +
                "账单详情页上能读到商户全称、支付方式、交易单号这些明细。",
            style = MaterialTheme.typography.bodySmall,
            color = InkSoft,
        )

        // 两条都没通的时候，把侧载那一大段引导写出来。这段字很长，
        // 但**每个侧载用户都要走一遍**，省略它等于让一半用户卡在第一步。
        if (!perm.anythingWorks) {
            Spacer(Modifier.height(12.dp))
            Text(
                "开关在系统里找不到？多半是被「受限设置」挡住了——" +
                    "本 App 是侧载安装的：\n\n" + AutoPermissions.SIDELOAD_RESTRICTION,
                style = MaterialTheme.typography.bodySmall,
                color = ExpenseRed,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(ExpenseRed.copy(alpha = 0.07f))
                    .padding(12.dp),
            )
        }

        // ---- 悬浮窗确认 ----
        //
        // 识别到支付后当场弹窗（占屏 2/3），类目已归好，用户点确认/不记了/忽略。
        // 需要系统的「显示在其它应用上层」特殊权限——这个权限设置页给不了，
        // 只能跳系统设置让用户手动开；没授权时悬浮窗静默跳过，草稿走待确认。
        Spacer(Modifier.height(12.dp))
        val canOverlay = remember { mutableStateOf(Settings.canDrawOverlays(context)) }
        var overlayOn by remember { mutableStateOf(store.overlayConfirmEnabled && canOverlay.value) }
        val lifecycleOwner2 = LocalLifecycleOwner.current
        DisposableEffect(lifecycleOwner2) {
            val obs = LifecycleEventObserver { _, e ->
                if (e == Lifecycle.Event.ON_RESUME) {
                    // 从授权页回来时重读授权状态；授权到手后自动把开关补开
                    canOverlay.value = Settings.canDrawOverlays(context)
                    if (canOverlay.value && store.overlayConfirmEnabled) overlayOn = true
                }
            }
            lifecycleOwner2.lifecycle.addObserver(obs)
            onDispose { lifecycleOwner2.lifecycle.removeObserver(obs) }
        }
        SettingsCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("悬浮窗确认", style = MaterialTheme.typography.titleMedium, color = Ink)
                    Text(
                        if (canOverlay.value) "识别后当场弹出，确认或不记，类目可改"
                        else "需要「显示在其它应用上层」权限，点此去授权",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (canOverlay.value) InkSoft else Brand,
                    )
                }
                Switch(
                    checked = overlayOn,
                    onCheckedChange = { v ->
                        when {
                            v && !canOverlay.value -> {
                                // 跳系统授权页；回来时 ON_RESUME 钩子重读并自动补开
                                context.startActivity(
                                    Intent(
                                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                        Uri.parse("package:" + context.packageName),
                                    ),
                                )
                            }
                            v -> {
                                store.overlayConfirmEnabled = true
                                overlayOn = true
                            }
                            else -> {
                                store.overlayConfirmEnabled = false
                                overlayOn = false
                            }
                        }
                    },
                )
            }
        }

        Spacer(Modifier.height(14.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.glassClickable { onOpenDrafts() },
        ) {
            Text("去看待确认", style = MaterialTheme.typography.labelLarge, color = Brand)
            Spacer(Modifier.width(4.dp))
            Icon(
                Icons.Filled.ChevronRight, null,
                tint = Brand, modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun PermRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    granted: Boolean,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    onOpenSettings: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(if (granted) BrandSoft else MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon, null,
                tint = if (granted) BrandDeep else InkFaint,
                modifier = Modifier.size(19.dp),
            )
        }
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = Ink)
                Spacer(Modifier.width(6.dp))
                Text(
                    if (granted) "已开" else "未开",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (granted) Brand else ExpenseRed,
                )
            }
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = InkSoft,
                maxLines = 3,
            )
        }
        Spacer(Modifier.width(8.dp))
        // 开关的状态必须取 `enabled && granted`，不能只取 enabled。
        // 这两个是不同的东西：enabled 是 App 内的开关（默认开），granted 是系统
        // 权限。只看 enabled 的话，系统权限没给的用户会看到一个亮着的开关，旁边
        // 却写着"未开"——而且点了它也确实什么都不会发生。
        //
        // 同理，打开时如果权限还没给，不去把 enabled 置 true（那只是让开关好看），
        // 直接把人送去系统设置：那才是唯一能真正改变结果的动作。
        Switch(
            checked = enabled && granted,
            onCheckedChange = { want ->
                if (want && !granted) onOpenSettings() else onToggle(want)
            },
        )
    }
    if (!granted) {
        Spacer(Modifier.height(4.dp))
        Text(
            "点这里去系统设置打开 →",
            style = MaterialTheme.typography.labelMedium,
            color = Brand,
            modifier = Modifier
                .padding(start = 47.dp)
                .glassClickable { onOpenSettings() },
        )
    }
}
