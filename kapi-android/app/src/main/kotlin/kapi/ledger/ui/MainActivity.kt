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
    val bottomBarHeight = 30.dp + bottomInset
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
                contentPadding = contentPadding,
                listState = if (frosted) frostedStats else statsList,
            )

            is Screen.Settings -> SyncSettingsScreen(
                vm = vm,
                onBack = {},
                contentPadding = contentPadding,
                onOpenLlmSettings = { backStack = backStack + Screen.LlmSettings },
                onOpenAccounts = { backStack = backStack + Screen.Accounts },
                llmStore = container.llmStore,
                onOpenDrafts = { backStack = listOf(Screen.Drafts) },
                draftCount = draftCount,
                autoBookStore = container.autoBookStore,
                transfer = container.transfer,
                deviceId = container.tokenStore.deviceId,
                scrollState = if (frosted) frostedSettings else settingsScroll,
            )

            is Screen.Add, is Screen.LlmSettings, is Screen.Accounts -> Unit // 上面已经 return 了
        }
    }

    LiquidBackground(Modifier.fillMaxSize(), dark = dark) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val screenHeight = maxHeight
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
            badge = 0, // 待确认已撤下底栏；漏掉的弹窗在 设置→自动记账 里看
            onSelect = ::goToTab,
            onRecord = { backStack = backStack + Screen.Add },
            dark = dark,
            // 磨砂玻璃要的是内容的**副本**：独立滚动状态，同步自上面那两套。
            frostedContent = { ScreenBody(current, frosted = true) },
            contentHeight = screenHeight,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
        }   // Box
    }   // BoxWithConstraints
}       // LiquidBackground → KapiRoot
/**
 * 底部导航。
 *
 * ## 为什么要做成悬浮胶囊而不是通栏
 *
 * 通栏的半透明条会读成"一条被调淡的色带"，而参考图里的效果之所以像玻璃，
 * 关键在两件事同时成立：**它是一块被切下来的独立物体**（左右留边、圆角收口），
 * **并且底下有内容**。通栏 + 无内容 = 一块贴上去的塑料。
 *
 * 底色也**不能**调得很实。上一版为了盖住从底下透上来的文字加了 tintBoost，
 * 结果玻璃感全没了——正确做法是让内容透上来（模糊之后它已经不是文字了），
 * 而不是把它挡死。
 */
@Composable
private fun BottomBar(
    current: Screen,
    tabs: List<Pair<Screen, String>>,
    onSelect: (Screen) -> Unit,
    onRecord: () -> Unit,
    badge: Int = 0,
    dark: Boolean = false,
    frostedContent: @Composable () -> Unit,
    contentHeight: Dp,
    modifier: Modifier = Modifier,
) {
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    // 记一笔的按钮。参考图里它是**从胶囊里"挖"出来的一个实心圆**，
    // 而不是并排的第五个图标——那一个圆同时是"记一笔"和"把胶囊断开"的视觉锚点。
    // 记一笔恒定占**正中那一个槽**（5 槽里的第 3 槽，也就是屏幕正中线）。
    // 它是整个 App 的主操作，藏在某个 tab 里反而降低可达性；放在正中而不是
    // 靠左，是因为主操作本来就该落在拇指最容易够到、视线最容易回到的那条中轴上，
    // 偏在一侧会让整根栏的重心失守——五个元素里唯一有填充色、有投影的那个
    // 压在其中一侧上，人眼会立刻觉得整根栏歪了。
    val fabIndex = 2

    Box(
        modifier
            .fillMaxWidth()
            .padding(horizontal = Glass.BAR_H_MARGIN)
            .padding(bottom = 10.dp + bottomInset),
        contentAlignment = Alignment.BottomCenter,
    ) {
        GlassSurface(
            frostedContent = frostedContent,
            contentHeight = contentHeight,
            modifier = Modifier
                .fillMaxWidth()
                .height(Glass.BAR_HEIGHT)
                // 投影是"悬浮"的另一半。玻璃本身只有边缘那 1dp 的明暗变化，
                // 在浅色背景上仍然偏轻；加一层很淡的落影之后，胶囊才
                // 真的离开纸面，而不是画在纸上。
                //
                // 18dp / #375376：视觉提示词的 `--shade: 0 18px 48px rgba(55,83,118,.14)`。
                // 阴影是**冷蓝灰**不是暖褐——旧值 #2A2620 是配暖纸底的，在冷白纸上
                // 会让胶囊底下洇出一圈脏色。深色下用纯黑，见 LiquidGlass 里同一条。
                .shadow(
                    elevation = 18.dp,
                    shape = RoundedCornerShape(Glass.BAR_RADIUS),
                    ambientColor = Color(0xFF375376),
                    spotColor = Color(0xFF375376),
                ),
            shape = RoundedCornerShape(Glass.BAR_RADIUS),
            radius = Glass.BAR_RADIUS,
            dark = dark,
            // 底栏用比卡片更实的玻璃：它底下压着列表文字，必须磨到读不出来。
            tint = if (dark) Glass.BarTintDark else Glass.BarTint,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 6.dp),
                // 刻意不写 horizontalArrangement：五个子节点全部是 weight(1f)，
                // 等分已经把剩余空间吃干净，SpaceEvenly 拿到的剩余量恒为 0，
                // 是个看着在干活、其实什么都不做的参数。分布**只**由槽宽决定——
                // 任何一个子节点不给等权，这个栏就会歪，而且没有任何警告。
                verticalAlignment = Alignment.CenterVertically,
            ) {
                for ((i, tab) in tabs.withIndex()) {
                    if (i == fabIndex) {
                        // 记一笔占**和 tab 一样宽**的槽（weight(1f)，不是固定 62.dp）。
                        // 固定宽度会让这一槽比另外四个窄 14dp：四个 tab 瓜分剩下的
                        // 空间，左半边的中心距被压到 185.5px、右半边撑到 208px，
                        // 肉眼就是"记一笔把左边两个挤过去了"。它必须是第五个等分槽，
                        // 不是第五个元素——58dp 的圆放进 ~76dp 的槽里既不会盖住
                        // 相邻图标（各留 23px），点按区仍然大于 48dp 最小值。
                        Box(
                            Modifier.weight(1f),
                            contentAlignment = Alignment.Center,
                        ) {
                            RecordButton(
                                onClick = onRecord,
                                // **不再往上顶**。原来 -13dp 让圆探出胶囊上沿 12px，
                                // 看着像"从玻璃里钻出来"，实际上圆的下沿离胶囊底还有
                                // 32px 空档——上下不对称，重心是偏的，而且整个圆有
                                // 一截挂在胶囊外面，胶囊就不再是一个完整的形状了。
                                // 现在靠加高胶囊（66→72dp）把它整个装进去：上下各留
                                // 7dp，"挖出来的圆"这个视觉照样在，但胶囊是完整的。
                            )
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

/**
 * 「记一笔」——胶囊正中的实心圆。
 *
 * 58dp 的圆坐在 72dp 胶囊的**里面**，上下各留 7dp。它是整个界面上唯一一个
 * 有填充色、有投影的元素，"凸起"感靠投影和明度差做出来，不靠让它挂到胶囊外面
 * ——挂出去的话胶囊就缺了一块，而圆自己的重心也是偏的（上沿探出 12px、
 * 下沿离底 32px）。
 * 做法是给一个圆形 Surface 加上和胶囊同源的模糊底，否则这个圆会像贴纸。
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
    val glow by animateFloatAsState(
        if (pressed) 0f else 1f,
        tween(700, easing = LinearEasing),
        label = "recordGlow",
    )
    Box(
        modifier
            .size(58.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            // 记一笔那颗圆的落影跟着品牌色走。圆本身是品牌色的实心块，
            // 落影和它同色，圆才像是从纸上**顶起来**的，而不是贴了张色纸片。
            .shadow(10.dp, CircleShape, ambientColor = Color(0xFF2868D8), spotColor = Color(0xFF2868D8))
            .clip(CircleShape)
            .background(
                // 固定配色，**不跟着主题走**。这个圆是「记一笔」的主操作锚点，
                // 深浅两档都得是同一块品牌色——它变了，用户每次切主题都要重新
                // 认一次。而且它的图标是白色，加上一旦变浅就等于白圆画白加号。
                //
                // 换到冷蓝这一套后这里是 #3A8B74→#1F5B4C 的**墨绿**，和满屏的
                // 品牌蓝对不上——那颗圆是全 App 视觉权重最高的元素，它一绿，
                // 整屏立刻读成"还有一半没换完"。改成同族的两档蓝。
                Brush.verticalGradient(
                    listOf(
                        Color(0xFF4A8AE8).copy(alpha = glow),
                        Color(0xFF1F4699),
                    )
                )
            )
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
            modifier = Modifier.size(28.dp),
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
        // 选中态除了换色，还加一个实心圆点。单靠颜色区分在色觉障碍用户那里
        // 是不可靠的，而这是整个 App 里最常点的一个控件。
        Box(
            modifier = Modifier
                .size(width = 46.dp, height = 28.dp)
                .clip(CircleShape)
                .background(if (selected) BrandSoft else Color.Transparent),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(21.dp))
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

