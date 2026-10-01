package kapi.ledger.auto

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kapi.core.auto.AutoBook
import kapi.core.auto.PaymentShape
import kapi.ledger.container
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * 无障碍读屏自动记账。**只读不写。**
 *
 * ## 为什么这里绝不碰 `performAction` / `dispatchGesture`
 *
 * 两个原因，第二个是硬的：
 *
 *  1. 隐私。用户装的是记账 App，不是脚本工具。一个能点按钮的无障碍服务，
 *     能力上和"自动下单"只有一步之遥——用户凭什么信它只读了屏幕？
 *  2. 平台规则。Google Play 禁止无障碍服务"自主发起、规划及执行动作"，
 *     但明确允许"确定性的、基于规则的自动化"。只读就落在允许这一侧，
 *     能点就落在禁止这一侧。我们走侧载分发，但这条路以后想上架，
 *     差别就在这一个决定上。
 *
 * 所以这个类里**不存在**任何会改变屏幕状态的调用。这不是疏忽，是它存在的意义。
 *
 * ## 微信、支付宝到底能不能读（**分设备**，2026-09-30 / 2026-10-01 两次真机实测）
 *
 * ### 2026-09-30　OPPO PMA110 / Android 17 / 微信 8.0.78：**能读**
 *
 * 微信照常派发无障碍事件，节点树读得到（`am start` 冷启动读到 88 个节点）；用户手动走
 * 我→服务→钱包→账单 也能读到金额和商户名。工行、招行、交行、中行、云闪付、支付宝同样可读。
 *
 * ### 2026-10-01　realme RMX3852 / Android 16 / 微信 8.0.78（**同一个微信版本**）：**读不到**
 *
 * **微信在这台机器上完全不向无障碍暴露界面**，而且不是 WebView 页的问题，是全部页面：
 *
 * ```
 * 画面：转账详情页，内容极其完整
 *       转账-转给橘生 / −0.10 / 当前状态 等待对方确认收钱 /
 *       转账时间 2026年10月1日 14:45:40 / 支付方式 中国银行储蓄卡(0416) /
 *       转账单号 1000050001202610010626797537039
 * 无障碍树：
 *       <node index="0" text="" package="com.tencent.mm"
 *             content-desc="" bounds="[0,0][0,0]" />
 * ```
 *
 * 一个**零尺寸**的空节点。逐页验过：
 * | 页面 | 窗口 | 节点/文字 |
 * |---|---|---|
 * | 转账详情 | `plugin.webview.ui.tools.MMWebViewUI` | 1 / 0 |
 * | plugin 页 | `plugin.mall.ui.MallIndexUIv2` | 1 / 0 |
 * | **原生聊天列表** | `ui.LauncherUI` | 1 / 0 |
 *
 * 同一个 `uiautomator dump` 和本服务**两个互相独立的读取方**给出同一结果
 * （本服务打的是 `com.tencent.mm 的屏幕上没有可读文字`），
 * 所以不是"我们读错了"，是**树里确实什么都没有**。
 *
 * ### 同版本、不同结果 ⇒ 变量在设备/ROM 上，不在微信版本上
 *
 * 两台机器微信都是 8.0.78，读出来一个 88 节点一个 1 节点。
 * **所以"微信能读"不是一条可以照搬的结论**，它是有前提的。
 *
 * ### 对照组是必需的：同一时刻支付宝读得好好的
 *
 * 同一台机器、同一时刻：
 * - 系统设置：**39 节点 / 9 文字**
 * - 支付宝首页：**290 节点 / 45 文字**，本服务实际读到 68 节点，
 *   并正确判成 `不是一笔支付：整屏 68 个节点，没有任何金额`（首页确实不是支付页）
 * - 微信：**1 节点 / 0 文字**
 *
 * 读屏通路本身没坏，支付宝就是证明。**遇到"读不到"时，先跑一个已知能通的 App 做对照**，
 * 否则分不清是"对方不暴露"还是"我们这边坏了"——这正是 [lastTraced] 那段要求的做法。
 *
 * 尚未查清变量：同版本不同结果，是厂商 ROM 的差异、还是与本机某个设置有关。
 * **下结论前先复测**：
 * ```
 * adb shell dumpsys window | grep mCurrentFocus
 * adb shell uiautomator dump /sdcard/x.xml && adb shell cat /sdcard/x.xml
 * ```
 *
 * 通知栏那条路对微信仍然是**唯一**可用通路，不是"补充"。
 *
 * ### 早先的两次误判，都留在这里当反面教材
 *
 * ① 写「微信/支付宝会调 `setImportantForAccessibility(NO)` 屏蔽子树、失效率很高」。
 * 错因是测法：三轮「拉起微信」都用 `adb shell monkey -p com.tencent.mm -c LAUNCHER 1`，
 * 而 App 已在前台时 `monkey` **不产生窗口切换事件**——拿着自己造出来的 0 条事件
 * 断言了"对方屏蔽读屏"。拉前台要用 `am start -n <pkg>/<activity>`。
 *
 * ② 反过来把 OPPO 那一台的结果当成普遍结论写进本注释，于是 2026-10-01 在 realme 上
 * 排查时，第一反应是"权限没接上"，绕了一圈。**一次成功的实测不等于一条规律。**

 */
class KapiAccessibilityService : AccessibilityService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * 每个来源 App 的上次处理时刻。
     *
     * 去抖是必要的，不是优化：支付成功页上的动画会持续触发
     * `TYPE_WINDOW_CONTENT_CHANGED`，一秒钟能来十几次。虽然 [AutoBook] 的指纹
     * 去重能挡住重复，但**每次都去遍历节点树 + 跑一遍正则**的开销是真的，
     * 而这是系统级服务，开销记在用户的电池上。
     *
     * ConcurrentHashMap 而不是普通 HashMap：回调在主线程、解析在后台线程，
     * 普通 map 跨线程读会偶发死循环或读到半截状态。
     */
    private val lastHandled = ConcurrentHashMap<String, Long>()

    /**
     * 「读不到」这类警告的节流表。
     *
     * 和 [lastHandled] 分开是因为两种节流的目的不同：那个是**省开销**（1.5 秒），
     * 这个是**别刷屏**（30 秒）。用户停在微信里不动时，窗口事件会一直来，
     * 用同一个 1.5 秒的窗口打日志，几十秒就能刷出上百行，反而把有用的那行埋掉。
     */
    private val lastWarned = ConcurrentHashMap<String, Long>()

    /**
     * 每个 App 上次**安排**延迟重读的时刻。用来给补读限速。
     *
     * 和 [lastHandled]（去抖，派发成功才占位）目的不同：这个记的是"多久没安排过了"，
     * 不管那次重读有没有记到东西。理由见 [onAccessibilityEvent] 里那段——
     * 页面一秒能来十几次内容变化事件，每次都遍历一遍节点树是用户可见的掉帧。
     */
    private val lastSettleAt = ConcurrentHashMap<String, Long>()

    private fun warnThrottled(pkg: String, message: String) {
        val now = System.currentTimeMillis()
        val last = lastWarned[pkg] ?: 0L
        if (now - last < WARN_COOLDOWN_MS) return
        lastWarned[pkg] = now
        Log.w(TAG, message)
    }

    /**
     * 「事件到达」的节流表。
     *
     * 存在的唯一理由是让 [onAccessibilityEvent] 的入口**无条件留痕**。
     *
     * ## 为什么必须留
     *
     * 那个方法里有 5 个 `return`，命中任何一个都是"一行日志都没有"：
     * 拿不到包名、事件类型不关心、拿不到 store、不在白名单、用户关了开关、去抖中。
     * 于是在外面看，"服务被微信屏蔽了"和"微信根本不在我们的白名单里"、
     * "用户把开关关了"、"刚好在去抖窗口里"——**四种完全不同的情况长得一模一样**。
     *
     * 2026-09-30 22:27 就是这么白查的：用户在微信里点开了账单详情页、停 5 秒、
     * 返回再停 3 秒，日志里 KapiA11y **一条都没有**，而同一时刻系统自己的
     * AccessibilityManagerService 在打 `onUserStateChangedLocked`——说明事件确实发生了。
     * 到底是微信不派发、还是我们自己在入口就 return 了，单看空日志**分不出来**。
     *
     * 所以这一行是判定「事件到底进没进来」的唯一证据：
     * 有这行 = 系统派发了，锅在下游；没这行 = 事件压根没来。
     *
     * ## 但"没这行"绝不能直接写成"对方屏蔽"（2026-09-30 踩过）
     *
     * 当时就是从这一行没出现，推断出"微信整屏屏蔽读屏"，还写进了注释和记忆。
     * 真相是**测法**：`adb shell monkey -p <pkg> -c LAUNCHER 1` 在 App 已经在
     * 前台时**不产生窗口切换事件**，而无障碍只在界面变化时回调，于是测多少秒都是 0。
     * 换成 `am start -n <pkg>/<activity>`，同一个微信读到了 88 个节点。
     *
     * 推论：**"没收到"的第一嫌疑人永远是自己的测法。** 下结论前必须先证明
     * 「我这套测法能测出东西」——拿一个已知能通的输入（另一个 App、或换一个拉起方式）
     * 跑一遍，对照成立之后，空日志才终于能说明问题。
     *
     * ## 入口行和「为什么提前返回」必须各占一份额度
     *
     * 曾经两条共用 [lastTraced] 里同一个 pkg 的额度，而入口行是先写的那一条——
     * 于是 [readScreen] 里每一句提前返回的理由（不在白名单、开关被关、读不到屏幕）
     * 都在写完入口行**同一毫秒内**被 5 秒窗口吞掉。
     *
     * 后果不是"少几行日志"，而是**这一整段设计的意义被自己废掉**：
     * 它的全部作用就是让"事件进来了但我们提前返回"和"事件压根没来"在日志里可区分，
     * 而共用额度之后这两种情况又变成了同一个样子——只剩一行孤零零的入口行。
     * 2026-10-01 在 realme 上就是这样：只看到 `事件 com.tencent.mm：type=32`，
     * 既看不出是哪种提前返回，也看不出后面其实还有一句"屏幕上没有可读文字"要等
     * 30 秒的 warn 节流放行。查这一条花掉的功夫，超过了它本该省下的。
     *
     * 所以 [slot] 把两者分开计数。
     *
     * ## 为什么不直接去掉节流
     *
     * 支付成功页的动画一秒能派发十几次 `TYPE_WINDOW_CONTENT_CHANGED`，
     * 不节流的话用户停在支付页两分钟就能刷出上千行，把真正有用的那几行埋掉。
     */
    private val lastTraced = ConcurrentHashMap<String, Long>()

    private fun traceThrottled(pkg: String, message: String, slot: String = "why") {
        val key = "$pkg|$slot"
        val now = System.currentTimeMillis()
        val last = lastTraced[key] ?: 0L
        if (now - last < TRACE_COOLDOWN_MS) return
        lastTraced[key] = now
        Log.d(TAG, "事件 $pkg：$message")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "无障碍服务已连接")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        // 这一行是 2026-09-30 加的，原因很具体：
        // 那天下午用户说"微信付了没反应"，查下来服务当时**根本没绑着**——
        // 17:36:37 连上，中间静默掉了一次，17:41:41 系统才重新拉起来。
        // 掉线这段时间里无障碍这条路是死的，而界面上只显示"已授权"，
        // 从外面看和"对方屏蔽读屏"一模一样，排查绕了整整一圈。
        // 掉线本身没日志，就等于没有证据。
        Log.w(TAG, "无障碍服务被系统解绑——从现在起读屏这条路是停的，直到系统重新拉起")
        instance = null
        lastHandled.clear()
        lastWarned.clear()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        scope.cancel()
        super.onDestroy()
    }

    override fun onInterrupt() {
        // 什么都不用做：这是一个只读服务，没有正在进行的操作需要中断。
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return
        val type = event?.eventType ?: -1
        // 入口无条件留痕。理由见 [lastTraced]：没有这一行，
        // 「微信屏蔽读屏」和「我们自己提前 return」在日志里完全同形。
        // slot="entry" 让它和下面 readScreen 里的失败理由各占一份节流额度——
        // 共用额度会把"为什么提前返回"吞掉，这一行的意义就没了。
        traceThrottled(pkg, "type=$type", slot = "entry")

        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            type != AccessibilityEvent.TYPE_VIEW_SCROLLED
        ) {
            traceThrottled(pkg, "type=$type 不是关心的三种，忽略")
            return
        }
        // 白名单、开关、去抖、读屏、落库都在 readScreen 里。
        // 这里只负责"要不要为这一屏再补一次延迟重读"。
        val now = System.currentTimeMillis()
        if (readScreen(pkg, now)) return

        // 窗口刚切过去、或内容还在陆续填充时，读到的多半是**半屏**。
        //
        // ## 2026-10-01 realme：这一段原来只在 `WINDOW_STATE_CHANGED` 时跑，漏了一整类场景
        //
        // 实测现场（点进微信「橘生」会话里的转账详情）：
        // ```
        // 事件 com.tencent.mm：type=2048          ← CONTENT_CHANGED
        // 不是一笔支付：整屏 25 个节点，没有任何金额
        // 不是一笔支付：整屏 28 个节点，没有任何金额   ← 读了四五次，全是半屏
        // （此后没有任何事件，页面已经稳定，而那一屏始终没被读到）
        // ```
        // 一两秒后页面才画完，**但系统不再派发事件**——节点树没再变，系统认为没有
        // content-changed。于是这一笔就这么漏了，界面上什么痕迹都没有。
        //
        // 原来只在 `WINDOW_STATE_CHANGED` 时补读，是因为最初遇到的漏记是从列表页
        // 点进详情页（那是 STATE_CHANGED）。而"点进会话""页面异步填充"走的是
        // CONTENT_CHANGED —— **事件类型不能拿来判断这一屏稳不稳**，页面稳不稳
        // 只有再读一次才知道。所以现在两种都补。
        //
        // 代价：内容变化频繁的页面（支付页动画）一秒能来十几次事件。靠
        // [lastSettleAt] 把补读限制成**每个 App 每秒最多一次**——而这个上限
        // 对"等页面画完"是绰绰有余的（一两秒），却把最坏情况的遍历次数压到
        // 事件数量的 1/10 以下。
        //
        // 它不是"多试几次直到成功"，而是给动画和异步加载留一个沉降时间：
        // 页面稳定后再读一次，读到的是最终形态。
        // [lastHandled] 保证这次重读只在**没真的记到东西**时才有意义。
        val settleAt = lastSettleAt[pkg] ?: 0L
        if (now - settleAt >= SETTLE_MIN_GAP_MS) {
            lastSettleAt[pkg] = now
            scope.launch {
                delay(SETTLE_DELAY_MS)
                // 必须切回主线程。scope 跑在 Dispatchers.Default 上，
                // 而节点树是在无障碍回调的上下文里读的。
                // 赌 `rootInActiveWindow` 跨线程也能用，赌输了是**静默**的：
                // collectTexts 那个 catch(Throwable) 会把它变成一条
                // 「无障碍对微信无效」的日志——而那条结论早就被实测推翻了，
                // 它会再次把排查引到完全错误的方向。
                withContext(Dispatchers.Main.immediate) {
                    readScreen(pkg, System.currentTimeMillis())
                }
            }
        }
    }

    /**
     * 读一次当前屏并决定要不要交给管线。
     *
     * @return 已经派发了一次捕获（无论成不成功）时为 true。
     */
    private fun readScreen(pkg: String, now: Long): Boolean {
        val store = runCatching { container.autoBookStore }.getOrElse {
            traceThrottled(pkg, "拿不到 autoBookStore：${it.javaClass.simpleName}")
            return false
        }
        if (pkg !in store.watchedPackages) {
            traceThrottled(pkg, "不在监听名单里（当前 ${store.watchedPackages.size} 个 App）")
            return false
        }
        if (!store.accessibilityEnabled) {
            traceThrottled(pkg, "读屏通路在设置里被关了")
            return false
        }

        val last = lastHandled[pkg] ?: 0L
        if (now - last < COOLDOWN_MS) return false

        // 节点树只能在主线程上读，遍历放在这里；解析和落库扔到后台。
        val nodes = collectTexts(pkg)
        if (nodes == null) {
            // 这一条是**最贵**的失败：对方主动屏蔽读屏。
            // 注意 collectTexts 返回 null 有两种原因，这里只兜"真读不到"那一种——
            // 窗口还没切完那种情况它自己已经打过日志了，不然这里会对着
            // 「刚切到微信、屏幕上还画着工行」这种正常情况报一句"微信屏蔽读屏"，
            // 把排查引到完全错误的方向上。
            warnThrottled(pkg, "读不到 $pkg 的屏幕，无障碍这条路对它无效")
            return false
        }
        if (nodes.none { it.text.isNotBlank() }) {
            warnThrottled(pkg, "$pkg 的屏幕上没有可读文字")
            return false
        }

        val text = when (val w = PaymentShape.inspect(nodes)) {
            is PaymentShape.Verdict.ManyTransactions -> {
                // 这里**不节流**。形状闸的每一次判定都是一次结论，
                // 而上一版把它并进 warnThrottled 之后，"这一屏被判定成不是一笔"
                // 和"这一屏压根没读过"在日志里长得一模一样——
                // 23:33 那次漏记就是这么查了半天才发现不是没事件，是事件被吞了。
                Log.w(TAG, "$pkg 不是一笔支付：${w.why}")
                // 原始行序一并打出来。误判排查缺的就是这个：
                // `uiautomator dump` 的节点顺序和无障碍原始树**不一样**
                // （它按布局排序，原始树按 DOM），照着 dump 的顺序推邻接关系
                // 一定会推错——2026-10-01 修支付宝立减金时就是这么错的，
                // 单元测试用的是 dump 顺序、通过了，真机却没生效。
                warnThrottled(
                    pkg,
                    "这一屏的原始行序（判邻接关系只能用这个，不能用 uiautomator dump）：\n" +
                        nodes.joinToString(" │ ") { it.text }.take(700),
                )
                return false
            }
            is PaymentShape.Verdict.OneTransaction -> {
                Log.d(TAG, "${pkg} ${w.why}")
                w.text
            }
        }

        // 只有真的派发了才占掉去抖名额。判成"不是一笔"的那一屏，
        // 不该拦住紧接着打开的下一屏——那正是列表页 → 详情页的必经之路。
        lastHandled[pkg] = now

        val pipeline = runCatching { container.autoCapture }.getOrNull() ?: return false
        scope.launch {
            runCatching {
                pipeline.capture(
                    AutoCapturePipeline.CaptureInput(
                        channel = AutoBook.CaptureChannel.A11Y,
                        originPackage = pkg,
                        rawText = text,
                        occurredAt = now,
                    )
                )
            }.onFailure {
                Log.w(TAG, "读屏捕获失败：${it.javaClass.name}: ${it.message}", it)
            }
        }
        return true
    }

    /**
     * 把当前屏幕上的文字收集起来，交给 [PaymentShape] 判"一笔还是一屏"。
     *
     * **刻意只收集文字，不取坐标。** 上一版按金额节点的邻域裁剪出一小块，
     * 坐标全是从 `uiautomator dump` 抄的——而 dump 的节点划分和原始无障碍树
     * 并不一样（它把「转账成功」和「1.00」拆成两个节点，原始树里是合在一起的），
     * 于是那条路径在真机上**一次都没触发过**。按看不见的形状去裁剪，
     * 裁出来的东西也就没法验证。
     *
     * 真正需要拦的是"一屏记录"，而那件事数条目就够，不需要知道位置。
     *
     * 预算不是微优化：
     *  - [MAX_NODES]：微信会话列表那种页面节点能到几千个，遍历本身就会让
     *    主线程掉帧，而无障碍回调跑在主线程上，掉帧就是用户看到界面卡住。
     *  - [MAX_NODES_TOTAL]：一次性最多收集多少个有文字的节点。
     *
     * 遍历用**显式栈**而不是递归：某些 App 的节点树很深，递归会真的爆栈。
     *
     * @param eventPkg 触发这次回调的包名。用来核对读到的到底是哪一屏，见下面的说明。
     */
    private fun collectTexts(eventPkg: String): List<PaymentShape.Node>? {
        val root: AccessibilityNodeInfo = try {
            rootInActiveWindow ?: return null
        } catch (t: Throwable) {
            // 个别 App 会在这里抛（它们主动屏蔽读屏）。静默返回，不让服务挂掉。
            Log.d(TAG, "拿不到当前窗口：${t.javaClass.simpleName}")
            return null
        }

        // ## 窗口切换的那一瞬间，读到的可能是上一个 App 的屏幕
        //
        // 2026-09-30 22:55 实测抓到的现场：
        // ```
        // 22:55:52.933  事件 com.icbc      形状像一笔：88 个节点（已支付¥13.00）
        // 22:55:52.987  事件 com.tencent.mm 形状像一笔：88 个节点（已支付¥13.00）
        // ```
        // 同一屏内容，**被记成了两个来源**。原因是 App 刚被拉起、窗口还没画完时，
        // `event.packageName` 已经是新 App，而 `rootInActiveWindow` 返回的还是
        // 屏幕上**实际画着的那一屏**——也就是上一个 App。
        //
        // 危险在于它**不报错**：工行那笔消费被当成微信的账走完了全流程。今天只是
        // 被指纹去重挡了（同通道 + 同样文本 → 同一个指纹），换个金额、换一屏、
        // 或者事件晚半秒到达，就是一条**凭空多出来的假账**，而用户看不出它从哪来。
        //
        // 所以必须核对，核对不上就整个丢掉。宁可漏一次（用户自己会补），
        // 不能记错一次（假账污染统计且用户不会发现）。
        val rootPkg = runCatching { root.packageName?.toString() }.getOrNull()
        if (rootPkg != null && rootPkg != eventPkg) {
            warnThrottled(
                eventPkg,
                "事件说 $eventPkg，但屏幕上画的是 $rootPkg——窗口还没切完，这一屏丢掉",
            )
            return null
        }

        val out = ArrayList<PaymentShape.Node>(32)
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        var visited = 0
        while (stack.isNotEmpty()) {
            if (visited++ > MAX_NODES || out.size > MAX_NODES_TOTAL) break
            val node = stack.removeLast() ?: continue
            val label = node.text?.toString()?.trim()
            if (!label.isNullOrEmpty()) out.add(PaymentShape.Node(label))
            val content = node.contentDescription?.toString()?.trim()
            if (!content.isNullOrEmpty() && content != label) out.add(PaymentShape.Node(content))
            for (i in 0 until node.childCount) node.getChild(i)?.let { stack.addLast(it) }
        }
        return out
    }

    companion object {
        private const val TAG = "KapiA11y"
        private const val MAX_NODES = 400

        /**
         * 同一个 App 的去抖间隔。
         *
         * 比 [AutoBook.MIN_WINDOW_MS] 短得多：去抖是为了省掉重复的**遍历和正则**，
         * 而重复的**丢弃**交给指纹窗口。这里 1.5 秒足以让一次支付页的连续重绘
         * 归成一次，又不会漏掉用户回到账单页再翻一屏的动作。
         */
        private const val COOLDOWN_MS = 1_500L

        /**
         * 窗口切换后的沉降延迟。
         *
         * 取 1.2 秒：微信详情页的转场动画在 300ms 内结束，但「交易单号 / 商户单号」
         * 这几行是异步补齐的，实测在事件到达后 1~2 秒才稳定。短于它读到半屏，
         * 长于它用户会以为 App 卡住。
         */
        private const val SETTLE_DELAY_MS = 1_200L

        /**
         * 两次延迟重读之间的最小间隔。
         *
         * 1 秒：一两秒的渲染时间足够被覆盖，而把最坏情况下每个 App 的遍历次数
         * 压到事件数量的十分之一以下。再密就变成"每个事件都重读一遍"，
         * 那和没限速没有区别。
         */
        private const val SETTLE_MIN_GAP_MS = 1_000L

        private const val WARN_COOLDOWN_MS = 30_000L

        /**
         * 「事件到达」的日志节流。比 [WARN_COOLDOWN_MS] 短，因为这一条是**常驻**的——
         * 用户只要停在支付页，日志就得隔几秒续一行，否则看不出"到底还在不在收事件"。
         */
        private const val TRACE_COOLDOWN_MS = 5_000L

        private const val MAX_NODES_TOTAL = 120

        @Volatile
        var instance: KapiAccessibilityService? = null
            private set

        /** 界面上用来显示"已连接 / 未连接"。 */
        val isRunning: Boolean get() = instance != null
    }
}
