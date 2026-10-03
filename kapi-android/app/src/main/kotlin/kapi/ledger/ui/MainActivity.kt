package kapi.ledger.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Rule
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.FactCheck
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.outlined.FactCheck
import androidx.compose.material.icons.outlined.PieChart
import androidx.compose.material.icons.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kapi.ledger.container
import kotlinx.coroutines.launch
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.SizeTransform
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer

private sealed interface Screen {
    data object Ledger : Screen
    data object Drafts : Screen
    data object Stats : Screen
    data object Settings : Screen
    data object Add : Screen
    data object LlmSettings : Screen
    data object Accounts : Screen
    data object Budget : Screen
    data object Rules : Screen
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            KapiTheme {
                // 接上窗口级真背景模糊（API 31+）。**必须**在 setContent 里、且要在
                // 任何一个 Surface 铺满全屏之前调：窗口背景一旦被画死，模糊就再也
                // 透不出来——`setBackgroundDrawable(null)` 那一行不是可选的洁癖。
                LiquidGlassEffect()
                KapiRoot()
            }
        }
    }
}

/**
 * 把源列表的滚动位置镜像到目标列表。
 *
 * 为什么需要它、以及为什么**不能**改成共用一个 state，见 KapiRoot 里的注释。
 *
 * 同步的是 `(首项 index, 首项内像素偏移)` 而不是 `scrollOffset`：
 * 后者在两个列表的 item 高度不一致时会漂，前者只要布局参数一样就是精确等价的。
 * 写入前先比对一次——fling 期间 snapshotFlow 每帧都发，scrollToItem 本身会触发
 * 目标列表重组，无条件写等于自己给自己制造一条重组风暴。
 */
@Composable
private fun SyncLazyScroll(src: LazyListState, dst: LazyListState) {
    LaunchedEffect(src, dst) {
        snapshotFlow { src.firstVisibleItemIndex to src.firstVisibleItemScrollOffset }
            .collect { (index, offset) ->
                if (dst.firstVisibleItemIndex != index ||
                    dst.firstVisibleItemScrollOffset != offset
                ) {
                    dst.scrollToItem(index, offset)
                }
            }
    }
}

/**
 * 根 Composable 与导航。
 *
 * 三个一级页 + 一个模态的「记一笔」，用 sealed interface + 列表就够。引入
 * navigation-compose 只会多一层依赖和一层间接，**真的开始出现多级路径时**
 * （比如「设置 → 大模型 → 模型管理」）才值得换。
 *
 * 名字**不叫** KapiApp —— `kapi.ledger.KapiApp` 是 Application 类，
 * 同名会让读代码的人分不清「App 容器」和「界面根节点」到底是哪个。
 */
@Composable
fun KapiRoot() {
    val container = LocalContext.current.container
    val factory = remember(container) {
        LedgerViewModelFactory(container.repository, container.syncCoordinator)
    }
    val vm: LedgerViewModel = viewModel(factory = factory)
    val scope = rememberCoroutineScope()

    var backStack by remember { mutableStateOf(listOf<Screen>(Screen.Ledger)) }
    val current = backStack.last()
    val pop: () -> Unit = { if (backStack.size > 1) backStack = backStack.dropLast(1) }

    // 首次启动铺默认账本/账户/分类。放在 Composable 里而不是 Activity.onCreate
    // 是因为那是 suspend 的数据库操作，不能在主线程上同步做。
    LaunchedEffect(Unit) { container.repository.ensureSeeded() }

    BackHandler(enabled = backStack.size > 1) { pop() }

    // 待确认数量挂在底栏的角标上。这是自动记账唯一的"有活干"信号——
    // 没有角标的话，用户会以为通知栏那条路没生效，然后把整个功能关掉。
    val draftCount by remember(container) {
        container.db.autoDraftDao().observeCount()
    }.collectAsState(initial = 0)

    // 「记一笔」是模态页：盖住整个界面，不参与底部导航的高亮。
    //
    // 【必须自己铺 LiquidBackground】下面 298 行那个背景在 KapiRoot 的主流程里，
    // 而这里提前 return 了。AddTransactionScreen 的根 Column 又刻意不铺不透明底色
    // （为了不盖死液态背景），于是它变成一块**完全透明**的区域，底下那一屏
    // 原样透上来——标题叠标题、设置页的按钮压在金额上面。不是动画残影，
    // 是两屏真的同时在画面上。
    if (current is Screen.Add) {
        LiquidBackground(Modifier.fillMaxSize(), dark = isSystemInDarkTheme()) {
            AddTransactionScreen(
                vm = vm,
                onDone = { pop() },
                llmStore = container.llmStore,
                llmClient = container.llmClient,
                onOpenLlmSettings = { backStack = backStack + Screen.LlmSettings },
            )
        }
        return
    }

    // 大模型设置是二级页：全屏、有自己的返回、不参与底部导航。
    if (current is Screen.LlmSettings) {
        LiquidBackground(Modifier.fillMaxSize(), dark = isSystemInDarkTheme()) {
            LlmSettingsScreen(store = container.llmStore, client = container.llmClient, onDone = { pop() })
        }
        return
    }

    // 账户管理是二级页，同 LlmSettings 的模式。
    if (current is Screen.Accounts) {
        LiquidBackground(Modifier.fillMaxSize(), dark = isSystemInDarkTheme()) {
            AccountsScreen(container = container, onDone = { pop() })
        }
        return
    }

    // 预算管理是二级页（2026-10-03 咔皮对齐），从设置进。
    if (current is Screen.Budget) {
        LiquidBackground(Modifier.fillMaxSize(), dark = isSystemInDarkTheme()) {
            BudgetScreen(container = container, onDone = { pop() })
        }
        return
    }

    // 分类规则是二级页：只读展示自动记账学到的商户归类。
    if (current is Screen.Rules) {
        LiquidBackground(Modifier.fillMaxSize(), dark = isSystemInDarkTheme()) {
            RulesScreen(container = container, onDone = { pop() })
        }
        return
    }

    // 待确认已从底栏撤下（2026-10-02）：悬浮窗确认成为主路径，草稿的入口
    // 降级到 设置→自动记账→去看待确认。底栏只留日常三屏。
    val tabs = listOf(
        Screen.Ledger to "账本",
        Screen.Stats to "统计",
        Screen.Settings to "设置",
    )
    val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    // 底部导航条画在内容之上。留出的安全区**故意比导航条矮**：留满的话，列表滚到
    // 末尾时底栏后面一整条都是空白，玻璃里就什么都没有可磨，出来是一块死白——
    // 那正是"看着像毛玻璃、其实不是"的样子。压到 30dp 让内容有大约三分之二从栏下
    // 穿过去，玻璃才有东西可模糊；可读性由 55% 的白色罩层和模糊本身兜住。
    // 白卡 dock 不透明，内容不能再从卡底穿过（会被吃掉）：
    // padding = 卡实际高度（把手 12 + 行 ~56 + 余量）+ 与内容的间距。
    val bottomBarHeight = 84.dp + bottomInset
    val contentPadding = PaddingValues(top = topInset, bottom = bottomBarHeight)

    val dark = isSystemInDarkTheme()

    val tabOrder = remember { listOf(Screen.Ledger, Screen.Stats, Screen.Settings) }

    // 滚动状态分**两套**：真实内容一套，磨砂副本一套。
    //
    // 【为什么不共用】底栏的磨砂是把内容再画一遍得到的（录图层 + RenderEffect 那条
    // 路在 Compose 1.9.4 上拿不到模糊，理由见 GlassSurface 的注释）。最初这里是
    // 四个 state 提上来、两边共用的——**两个 LazyColumn 共用一个 LazyListState 是
    // 不支持的**：state 内部只保存一份测量结果（itemProvider + 每个 item 的摆放），
    // 后测量的那次会把前一次的覆盖掉。实测现象是真实列表正常、副本整块透明，
    // 玻璃里只剩背景色，而且**不报任何错**。
    //
    // 【为什么不共用也不裁剪副本】副本必须按整屏高度测量才能让"底对齐 + 负偏移"
    // 的坐标映射成立（副本 y 与屏幕 y 一一对应），所以它必然是一次完整列表组合，
    // 成本约等于两份列表。这是当前方案唯一的代价，记在这里以免以后被人当 bug 优化掉。
    val ledgerList = rememberLazyListState()
    val draftsList = rememberLazyListState()
    val statsList = rememberLazyListState()
    val settingsScroll = rememberScrollState()

    val frostedLedger = rememberLazyListState()
    val frostedDrafts = rememberLazyListState()
    val frostedStats = rememberLazyListState()
    val frostedSettings = rememberScrollState()

    // 副本的滚动位置跟着真实内容走。同步的是 (首项 index, 首项内偏移 px)：
    // 副本和原画面用同一份 contentPadding、同样按整屏高度布局，index+offset 一致
    // 就等于**画的是同一屏内容**，只是被模糊了。差一帧看不出来——本来就该看不太出来。
    SyncLazyScroll(ledgerList, frostedLedger)
    SyncLazyScroll(draftsList, frostedDrafts)
    SyncLazyScroll(statsList, frostedStats)
    LaunchedEffect(settingsScroll, frostedSettings) {
        snapshotFlow { settingsScroll.value }.collect { frostedSettings.scrollTo(it) }
    }

    // 页面推移方向。在**点击的那一刻**就算好：等到 AnimatedContent 重组时再比
    // 前后索引，两次点击之间的中间态已经丢了，方向会随机翻转。
    var navDirection by remember { mutableIntStateOf(1) }
    fun goToTab(target: Screen) {
        val from = tabOrder.indexOf(current)
        val to = tabOrder.indexOf(target)
        navDirection = if (from < 0 || to < 0 || to >= from) 1 else -1
        backStack = listOf(target)
    }

    @Composable
    fun ScreenBody(screen: Screen, frosted: Boolean = false) {
        // frosted=true 是给玻璃里那份副本用的：只换滚动状态，其余参数必须和原画面
        // **完全一致**（尤其 contentPadding），否则两边的坐标对不上，磨出来的位置就错了。
        when (screen) {
            is Screen.Ledger -> LedgerScreen(
                vm = vm,
                onDelete = { id -> scope.launch { vm.deleteTransaction(id) } },
                onOpenSettings = { backStack = listOf(Screen.Settings) },
                contentPadding = contentPadding,
                listState = if (frosted) frostedLedger else ledgerList,
            )

            is Screen.Drafts -> DraftsScreen(
                container = container,
                contentPadding = contentPadding,
                onOpenAutoSettings = { backStack = listOf(Screen.Settings) },
                listState = if (frosted) frostedDrafts else draftsList,
            )

            is Screen.Stats -> StatsScreen(
                vm = vm,
                container = container,
                contentPadding = contentPadding,
                listState = if (frosted) frostedStats else statsList,
            )

            is Screen.Settings -> SyncSettingsScreen(
                vm = vm,
                onBack = {},
                contentPadding = contentPadding,
                onOpenLlmSettings = { backStack = backStack + Screen.LlmSettings },
                onOpenAccounts = { backStack = backStack + Screen.Accounts },
                onOpenBudget = { backStack = backStack + Screen.Budget },
                onOpenRules = { backStack = backStack + Screen.Rules },
                llmStore = container.llmStore,
                onOpenDrafts = { backStack = listOf(Screen.Drafts) },
                draftCount = draftCount,
                autoBookStore = container.autoBookStore,
                transfer = container.transfer,
                deviceId = container.tokenStore.deviceId,
                scrollState = if (frosted) frostedSettings else settingsScroll,
            )

            is Screen.Add, is Screen.LlmSettings, is Screen.Accounts, is Screen.Budget, is Screen.Rules -> Unit // 上面已经 return 了
        }
    }

    LiquidBackground(Modifier.fillMaxSize(), dark = dark) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize()) {
            AnimatedContent(
                targetState = current,
                transitionSpec = {
                    // 方向由外层算好传进来，**不**在 transitionSpec 里反推
                    // initialState/targetState：那两个是 transition scope 的属性，
                    // 一旦版本变动取不到，报错会指向完全无关的地方
                    //（"Unresolved reference 'using'"），排查成本极高。
                    //
                    // 推移 + 淡入淡出。偏移只走 1/6 屏——整屏横移在中文长列表上
                    // 会让人觉得"内容被甩出去了"，1/6 刚好读成"翻了一页"。
                    val sign = if (navDirection > 0) 1 else -1
                    // 注意 slideIn/slideOutHorizontally 的偏移量是 **Int 像素**，
                    // 不是 Float。写成 `it / 6f * sign` 会在编译期报
                    // "operator modifier is required"——报错完全指不到偏移量上。
                    (slideInHorizontally(tween(320, easing = FastOutSlowInEasing)) { it / 6 * sign } +
                        fadeIn(tween(240))) togetherWith
                        (slideOutHorizontally(tween(280, easing = FastOutSlowInEasing)) { -it / 6 * sign } +
                            fadeOut(tween(160))) using SizeTransform(clip = false)
                },
                label = "page",
            ) { screen ->
                ScreenBody(screen)
            }

        }

        BottomBar(
            current = current,
            tabs = tabs,
            onSelect = ::goToTab,
            onRecord = { backStack = backStack + Screen.Add },
            dark = dark,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
        }   // Box
    }   // BoxWithConstraints
}       // LiquidBackground → KapiRoot
/**
 * 底部导航 —— **咔皮式白色悬浮 dock**（2026-10-03 第二次重做）。
 *
 * 上一版是毛玻璃胶囊（把整个屏幕复制一份磨砂进胶囊），用户点名批评"做的
 * 又是什么东西"——对照商汤咔皮记账的底栏：它根本不是玻璃，是一张**干净的
 * 纯白悬浮卡**（大圆角、冷灰淡投影、左右留边），顶边一条小把手，内容行
 * 呼吸感足，主操作是一颗**扁平蓝圆**。本版照这个构造重做：
 *
 *   1. 材质：不透明白卡（深色 #1C2333），不再磨砂——磨砂要整屏副本的
 *      双倍组合成本，换来的"玻璃感"用户不认，删。
 *   2. 顶边把手：36×4dp 圆角条，贴在卡内上沿——咔皮展开面板的构造，
 *      读作"这是一块可以停靠的面板"。
 *   3. 主操作：46dp **纯色**品牌蓝圆（白 + 号），不再渐变——渐变球是
 *      "四个按钮里唯一的彩色大物"，扁平圆才和三个 tab 同一族。
 *
 * ## 内容 padding 必须盖过卡高
 *
 * 玻璃时代内容**故意**从胶囊底下穿过去（反正会被磨掉）。白卡是不透明的，
 * 穿过去就是被吃掉——contentPadding 的 bottom 必须大于卡的实际高度，
 * 否则列表最后一两行永远滚不出来。
 */
@Composable
private fun BottomBar(
    current: Screen,
    tabs: List<Pair<Screen, String>>,
    onSelect: (Screen) -> Unit,
    onRecord: () -> Unit,
    dark: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    Box(
        modifier
            .fillMaxWidth()
            .padding(horizontal = Glass.BAR_H_MARGIN)
            .padding(bottom = 10.dp + bottomInset),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Surface(
            shape = RoundedCornerShape(30.dp),
            color = if (dark) Color(0xFF1C2333) else Color.White,
            shadowElevation = 12.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column {
                // 顶边把手（咔皮面板构造）：纯造型，不接事件。
                Box(
                    Modifier
                        .padding(top = 8.dp)
                        .size(width = 36.dp, height = 4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(InkFaint.copy(alpha = 0.35f))
                        .align(Alignment.CenterHorizontally),
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 6.dp, end = 6.dp, top = 4.dp, bottom = 8.dp),
                    // 五个子节点全部 weight(1f) 等分，分布只由槽宽决定。
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val fabIndex = 2
                    for ((i, tab) in tabs.withIndex()) {
                        if (i == fabIndex) {
                            Box(
                                Modifier.weight(1f),
                                contentAlignment = Alignment.Center,
                            ) {
                                RecordButton(onClick = onRecord)
                            }
                        }
                        TabItem(
                            label = tab.second,
                            selected = current === tab.first,
                            badge = 0,
                            icon = iconFor(tab.first, selected = current == tab.first),
                            onClick = { onSelect(tab.first) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

/**
 * 「记一笔」——dock 正中的**扁平**品牌蓝圆（咔皮的 + 构造：纯色、白 + 号、
 * 极淡投影）。渐变球是上一版被点名的东西，扁平圆才和三个 tab 同一族。
 */
@Composable
private fun RecordButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed) 0.90f else 1f,
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "recordScale",
    )
    Box(
        modifier
            .size(46.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            // 落影跟品牌色走：圆是从白卡上顶起来的，不是贴纸。
            .shadow(6.dp, CircleShape, ambientColor = Color(0xFF2868D8), spotColor = Color(0xFF2868D8))
            .clip(CircleShape)
            // 固定配色不跟主题：主操作锚点，深浅两档同一块蓝。
            .background(Color(0xFF3B79E0))
            .clickable(
                interactionSource = interaction,
                indication = androidx.compose.material3.ripple(bounded = true, color = Color.White),
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Filled.Add,
            contentDescription = "记一笔",
            tint = Color.White,
            modifier = Modifier.size(24.dp),
        )
    }
}

@Composable
private fun iconFor(screen: Screen, selected: Boolean): ImageVector = when (screen) {
    is Screen.Ledger ->
        if (selected) Icons.Filled.ReceiptLong else Icons.Outlined.ReceiptLong
    is Screen.Drafts ->
        if (selected) Icons.Filled.FactCheck else Icons.Outlined.FactCheck
    is Screen.Stats ->
        if (selected) Icons.Filled.PieChart else Icons.Outlined.PieChart
    is Screen.Settings ->
        if (selected) Icons.Filled.Settings else Icons.Outlined.Settings
    is Screen.Add -> Icons.Filled.Add
    is Screen.LlmSettings -> Icons.Filled.Psychology
    is Screen.Accounts -> Icons.Filled.AccountBalanceWallet
    is Screen.Budget -> Icons.Filled.Savings
    is Screen.Rules -> Icons.Filled.Rule
}

@Composable
private fun TabItem(
    label: String,
    selected: Boolean,
    icon: ImageVector,
    onClick: () -> Unit,
    badge: Int = 0,
    modifier: Modifier = Modifier,
) {
    val tint = if (selected) Brand else InkFaint
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            // **不要**再用 `indication = null`：上一版为了让自定义样式"干净"把涟漪
            // 关掉了，结果整个 App 点下去毫无反馈。这里用 glassClickable 补回
            // 涟漪 + 按压缩放两层。
            .glassClickable(onClick = onClick)
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // 选中态除了换色，还加一个实心胶囊底。单靠颜色区分在色觉障碍用户那里
        // 是不可靠的，而这是整个 App 里最常点的一个控件。
        Box(
            modifier = Modifier
                .size(width = 42.dp, height = 26.dp)
                .clip(RoundedCornerShape(13.dp))
                .background(if (selected) BrandSoft else Color.Transparent),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(22.dp))
            // 角标画在图标右上角，不占额外的行高。数字超过 99 就显示「99+」：
            // 挤在 46dp 里的三位数会糊成一团，而那个数字本身用户也不会去数。
            if (badge > 0) {
                Text(
                    text = if (badge > 99) "99+" else "$badge",
                    style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .clip(CircleShape)
                        .background(ExpenseRed)
                        .padding(horizontal = 4.dp, vertical = 1.dp)
                        .defaultMinSize(minWidth = 15.dp, minHeight = 15.dp),
                )
            }
        }
        Spacer(Modifier.height(3.dp))
        Text(
            text = label,
            style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
            color = tint,
            textAlign = TextAlign.Center,
        )
    }
}

