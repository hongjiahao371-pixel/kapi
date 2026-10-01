package kapi.ledger.ui

import android.app.Activity
import android.os.Build
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.material3.LocalContentColor
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * 液态玻璃。
 *
 * ## 玻璃只用在底栏
 *
 * 上一版把玻璃铺满每张卡片、底下再垫一层彩虹渐变，看起来像"到处都在发光"，
 * 实际上没有层次——每样东西都在抢注意力。高德地图 2026 焕新版那套实践说得很直接：
 *
 * > 玻璃集中出现在主要功能按钮、底部 Bar 等关键操作位置，**而不是铺满整个界面**。
 * > 液态玻璃本身视觉重量较高，核心控件过多反而会让页面焦点分散。
 *
 * 现在的分工：**背景**安静让位，**卡片**用实体材质靠排版建立层级，
 * **底栏**是唯一一块真玻璃——因为只有它压在内容上，磨得出东西。
 *
 * ## 为什么用 Haze 而不是自己录图层
 *
 * 自己 `rememberGraphicsLayer` + `record` + `drawLayer` + `RenderEffect` 这条路走过，
 * 结论是不该走：
 *
 *  1. **每帧要把整屏重录一遍。** 1/5 降采样只是把带宽降到 1/25，绘制调用一个不少。
 *     底栏静止不动的时候也在录，只是为了等下一次滚动。
 *  2. **`GraphicsLayer.renderEffect` 对 `DrawScope.drawLayer` 不生效。** 属性设上了、
 *     不报错、画面一像素没变——纯色塞进图层能看见（录制/绘制通路是通的），
 *     改半径从 9 到 100 画面也一动不动（不是半径问题）。属性只对图层自己的绘制
 *     路径生效，`drawLayer` 走的是另一条。
 *  3. **坐标对齐全是隐式约定。** `DrawScope.scale` 的 pivot 默认是**节点中心**不是原点，
 *     录的时候绕中心缩、画的时候绕中心放大，两处都不写 `pivot = Offset.Zero` 的话
 *     采样窗口整个跑到屏幕外；而 `drawLayer` 不报错，只是画不出来。
 *
 * Compose 1.8 起的 **Haze** 就是为这件事做的：内容录一次（`hazeSource`），
 * 玻璃只在自己的边界范围内做 `RenderEffect` 模糊（`hazeEffect`），中间的重绘、
 * 坐标对齐、bounds 扩展全部由框架处理。Material 3 Expressive 的液态玻璃
 * 底层就是它。少写 200 行换掉一整类"效果悄悄消失"的 bug。
 */
object Glass {

    /**
     * 录音用的降采样比例。越小越糊、越便宜。
     *
     * 这只影响**录制**成本：录制 1/5 的画布，绘制调用一个不少，但像素填充和
     * 后续模糊的输入都降到 1/25。放大回去的双线性插值又叠了一层柔化，
     * 对磨砂玻璃来说反而是加分项。
     */
    const val DOWNSCALE = 0.2f

    /**
     * 模糊半径，屏幕像素（这里用 Dp 写，跟着密度走）。
     *
     * 判据不是"好看"，是**读不出来**。底栏同时压着四个 tab 标签和列表正文，
     * 底下透上来的东西只要还能读，两层文字就在打架。所以这个值偏大。
     *
     * 22 → 30dp：按 OPPO ColorOS 原图实测反解。光罩是中性灰 245 @50%，
     * 它的遮蔽力比原来的纯白 @44% 更强，但中性灰本身没有纯白那么"顶"，
     * 需要更大的模糊半径把透上来的内容压到读不出来才够。
     */
    val BLUR: Dp = 30.dp

    /**
     * 磨砂颗粒强度。
     *
     * 真玻璃的表面不是数学上的高斯模糊，是无数微面把光散开的结果，所以总有一层
     * 极细的颗粒。纯高斯模糊出来的是"糊"，加上这层噪声才是"磨砂"。
     * 取值 0.15~0.3：再高就变成噪点贴纸，玻璃反而变脏。
     */
    const val NOISE = 0.22f

    /**
     * 玻璃的奶白底。低版本没有模糊层，靠它撑住层次。
     *
     * ## 从纯白改成**中性灰**，不是审美选择，是量出来的
     *
     * OPPO ColorOS 原图逐像素实测，玻璃的光罩行为是：
     *
     *     结果 = 0.5 × 245(中性灰) + 0.5 × 背后内容
     *
     * 暗底(明度 21) → 实测 127，模型 133，差 6
     * 亮底(明度 240) → 实测 243，模型 242，差 1
     * 圆形按钮衬底(明度 90) → 实测 164，模型 167.5，差 3.5
     *
     * 三个独立样本全部命中，所以这组参数是拟合出来的，不是"看着差不多"。
     *
     * 原来的 `0x70FFFFFF` 有两处和实测对不上：
     *  1. **色相错了**：纯白 Tint 会把背后的暖色（kapi 的暖纸底、账目红）洗掉，
     *     玻璃看上去是白的而不是"透着颜色"。实测色渗只有 R−B ±10，很淡，
     *     纯白 Tint 会把这最后一点也吃掉。
     *  2. **浓度不对**：0x70 = 43.9%，实测是 50%。
     *
     * 换中性灰之后玻璃会透出一点底色，这是对的——玻璃本来就是"透着背后颜色的东西"。
     *
     * ⚠️ **这一项目前不参与任何实际渲染**，别在这里调期望能改底栏。
     * 全工程 `GlassSurface` 只有一个调用点（MainActivity 里的 BottomBar），而它
     * 永远显式传 `tint = Glass.BarTint`，所以浅色下走的一直是 [BarTint]，
     * 这个值只是 `GlassSurface` 的兜底默认值。底栏的浓度请改 [BarTint]。
     */
    val TintLight = Color(0x80F5F5F5)
    val TintDark = Color(0xB32A2A27)

    /**
     * 底栏专用的玻璃浓度——**这才是浅色下底栏真正用的光罩**。
     *
     * 底栏和卡片对"透"的容忍度完全不同：卡片是内容载体必须实，
     * 底栏是操作层，底下压着列表文字必须磨到读不出来。
     *
     * 取值不是拍脑袋，是拿一块已知色标在真机上量的（OPPO PMA110 / ColorOS 17）：
     * 临时在设置页顶部铺一条 1200dp 高的校准条——左半纯红、右半红白竖条纹——
     * 一次截图里同时拿到玻璃上方（锐利）和玻璃内（透过）的同一份信号：
     *
     *   浓度    光罩透过率*   条纹高频残留   模糊衰减
     *   0x8F     45.9%         12.6%         87.4%
     *   0xA0     40.7%         10.9%         89.1%   ← 当前
     *   ColorOS  ——             ~7%          ~70%
     *
     * \* 光罩透过率＝纯红区 R−B 从 255 掉到玻璃内多少，**只反映光罩**：模糊对
     *   纯色无效，所以纯红这一路测不到 blur。条纹那一路才同时含 blur+veil。
     *
     * 0x8F→0xA0 把综合透过从 12.6% 压到 10.9%，向 ColorOS 靠了 13%。
     * 没有继续加浓：要补齐到 ColorOS 的 ~7% 需要 α≈0.77（0xC4），实测会让
     * 底栏接近不透明、玻璃感基本消失——对一个压在列表文字上的操作层是净损失。
     * [BLUR] 同理**没有**再往上加：kapi 的 87~89% 衰减已经强于 ColorOS 的 ~70%，
     * 底栏底下是正文，再加只会更糊更贵。
     */
    val BarTint = Color(0xA0F5F5F5)

    /** 内圈暗边。玻璃是有厚度的，光在侧壁会衰减。 */
    val InnerShadeLight = Color(0x1A4A4238)
    val InnerShadeDark = Color(0x40000000)

    /** 边缘高光：模拟光从上方来。 */
    val RimLight = Color(0xE6FFFFFF)
    val RimDark = Color(0x33FFFFFF)

    /**
     * 边缘的**暗侧**。
     *
     * 只画亮边在浅色底上是失效的：0xE6 的白描边压在暖白背景上差值不到 10/255，
     * 肉眼等于没有，底栏就"消失"了。真实玻璃在浅背景上的边缘是**上亮下暗**的
     * 一圈——顶面反光，侧壁和底面把环境光挡回去。
     */
    val RimShadeLight = Color(0x2E2B2620)
    val RimShadeDark = Color(0x66000000)

    val BAR_RADIUS: Dp = 36.dp
    val BAR_H_MARGIN: Dp = 10.dp
    /**
     * 72dp 不是随手加的：记一笔的圆是 58dp，要在胶囊**内部**上下各留 7dp，
     * 66dp 装不下（只能各留 4dp，圆几乎贴着边）。而把圆顶到胶囊外面去又会
     * 让胶囊缺一块、圆重心偏上——两个都不是想要的。
     */
    val BAR_HEIGHT: Dp = 72.dp

    /*
     * 这里曾经有一层"虹彩"，已删——它建立在一个取错的样本上
     *
     * 早先测到 ColorOS 底栏"顶边 R−B = −41（青 #C9EEF2）"，于是照着加了
     * 一层上下缘色散，形态改过两轮（径向 → 纵向线性渐变），每轮都装完机量。
     * **那个 −41 取错了。** 回头逐像素复查，那片青色是**背景 App 里一条
     * 蓝色折线图**透过玻璃，不是玻璃自带的颜色。
     *
     * 重新在四块独立玻璃上复核 R−B（红减蓝，冷暖偏差），结论一致：
     * **ColorOS 玻璃本身不带色。**
     *
     * ```
     * 表面                      玻璃内 R−B      紧邻背景 R−B       判定
     * 问小布胶囊（深色毛绒壁纸）    0（全程）        ±0                不加色
     * 浮动栏（浅色应用页 ref4）    74% 中性        右侧 +23（地图）    不加色
     * 浮动栏顶缘（彩色壁纸 ref3）  −16.0           −18.3（正上方壁纸）  透上来的
     * 圆形按钮 ×2（ref3）         −1.3 / −0.8     −1.5 / −1.6        不加色
     * ```
     *
     * 第三行是决定性的：玻璃顶缘的 −16，和它**正上方**那 46px 壁纸的 −18.3
     * 几乎相等。色是背后的，玻璃只是把它模糊着透上来。ref4 里剩下的那 0.1%
     * 冷色像素，就是这条折线图。
     *
     * 圆形按钮更直白：内部 R−B 极差只有 −6..+2，紧挨着的壁纸却是 −36..+80。
     * 玻璃把色度**强力压掉**，但**不凭空造色**。
     *
     * 所以色散这层是凭空加的，删了。玻璃现在只剩三样，三样在参考图上都能对上：
     * 中性雾（[TintLight] / [BarTint]）、上亮下暗的边缘描边、顶部窄高光。
     *
     * 代价记在这：同一个错误改了三版才查出来，白花了不少时间。
     * **教训**——拿色差当玻璃的固有属性之前，先确认那块像素**不是背景透上来的**。
     * 当时没做这一步。以后复核颜色一律要带"紧邻背景"对照列。
     *
     * 真正该跟着背景走的那部分，已经跟着走了：模糊和 alpha 是真的在透内容
     * （实测卡片色度透过率 13.8%，ColorOS 那边 20~26% 的像素超出 ±10，同一量级）。
     * 不需要额外一层颜色去"补"虹彩。
     */
}

/** 真有系统级模糊吗。界面上不该在低版本上宣称"已启用毛玻璃"。 */
fun glassBlurAvailable(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

/**
 * 接上**窗口级**真背景模糊（API 31+）。
 *
 * 这条模糊的是"窗口背后"（桌面、其他 App），和 Haze 的卡片级背景模糊是两件事，
 * 都要有：前者负责窗口整体通透，后者负责玻璃压住自己的内容。
 *
 * 只在 setContent 里调一次。
 */
@Composable
fun LiquidGlassEffect() {
    if (!glassBlurAvailable()) return
    val view = LocalView.current
    val window = (view.context as? Activity)?.window ?: return
    // 窗口 API 要的是**像素**。直接传 Dp 的数值在 3 倍屏上只有预想的三分之一，
    // 模糊几乎看不出来——一个"看起来像没生效"的 bug。
    val radiusPx = with(androidx.compose.ui.platform.LocalDensity.current) {
        Glass.BAR_RADIUS.roundToPx()
    }
    // 不设成 null 的话，模糊出来的还是窗口自己的底色，等于什么都没做
    window.setBackgroundDrawable(null)
    window.setBackgroundBlurRadius(radiusPx)
}

/** Shape → Path。玻璃的裁剪和描边必须用同一条路径，否则两者会差半个像素，
 *  表现出来就是"描边比裁剪大一圈"，高光边看起来像有毛刺。 */
private fun DrawScope.outlinePath(shape: Shape, target: Size): Path {
    val o = shape.createOutline(target, layoutDirection, this)
    return when (o) {
        is Outline.Generic -> o.path
        is Outline.Rounded -> Path().apply { addRoundRect(o.roundRect) }
        else -> Path().apply { addRect(Rect(Offset.Zero, target)) }
    }
}

/**
 * 一块玻璃。
 *
 * 层次从下往上：
 *  1. **磨砂后的背景**（API 31+）——把内容副本画在一条**只覆盖底栏的窄条**上，
 *     给这条窄条加 `RenderEffect` 模糊
 *  2. 半透明白底——给模糊结果加一层"雾"，让它不像直接贴在屏幕上
 *  3. 内圈暗边——玻璃的厚度
 *  4. 边缘**上亮下暗**的双向描边——把边界从背景里切出来
 *  5. 顶部内侧一道更亮的窄高光——体积感
 *
 * ## 为什么是"再画一遍内容"而不是"录图层再画回来"
 *
 * 后者（`rememberGraphicsLayer` + `record` + `drawLayer`）在这套 Compose（1.9.4）上
 * 拿不到任何模糊，全试过了：
 *  - `GraphicsLayer.renderEffect`：采样图层是**游离**的 RenderNode，不在视图树里，
 *    没有父级能给它离屏纹理。挂上效果后合成拿不到目标缓冲，RenderThread 干脆
 *    什么都不画——不报错、无日志，画面上只剩"玻璃下面的真实内容"。
 *  - `Modifier.graphicsLayer { renderEffect }` / `Modifier.blur`：
 *    挂在**承载 drawLayer 的那个节点**上时，节点内容被 RenderEffect 处理的同时
 *    又要内联绘制另一个 RenderNode，合成器拒绝，整块空白。
 *
 * 顺带记下对齐的坑（踩了很久）：采样图层的放大倍数挂在它自己的 `scaleX/scaleY` 上时，
 * **平移量必须正好是 0**。推导：node 局部 p 处要显示层像素 q = (rootOffset + p) * DOWNSCALE，
 * 而图层把 q 画到 q/DOWNSCALE + translation，两边一约掉 translation 就没了。
 * 之前按直觉写了 `translate(-rootOffset)`，把整张图层推出屏幕 2138px——
 * 现象和"完全没画"一模一样。
 *
 * ## 为什么模糊只作用在窄条上
 *
 * 内容画两遍已经是双倍成本，模糊再覆盖全屏就是第二份浪费：每帧对 260 万像素做
 * 高斯。而底栏只需要自己那条。所以这里只让一个窄条参与模糊——约 1/20 的像素量，
 * 视觉上完全一样。
 *
 * [frostedContent] 会和真正显示的那份内容**画两遍**，所以它必须自带滚动状态
 * （不能和原画面共用 `LazyListState`，理由见 KapiRoot 的注释），并由调用方把
 * 原画面的滚动位置同步过来，否则用户一滚动，玻璃里磨出来的就是另一个位置的内容。
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(Glass.BAR_RADIUS),
    radius: Dp = Glass.BAR_RADIUS,
    dark: Boolean = false,
    tint: Color? = null,
    /** 内容副本：按整屏尺寸布局，只露出底部条带那一段。 */
    frostedContent: (@Composable () -> Unit)? = null,
    /** 真正显示内容的那一屏有多高。副本要按这个高度布局，位置才对得上。 */
    contentHeight: Dp = 0.dp,
    content: @Composable BoxScope.() -> Unit,
) {
    val baseTint = tint ?: if (dark) Glass.TintDark else Glass.TintLight
    val rim = if (dark) Glass.RimDark else Glass.RimLight
    val rimShade = if (dark) Glass.RimShadeDark else Glass.RimShadeLight
    val shade = if (dark) Glass.InnerShadeDark else Glass.InnerShadeLight
    val density = LocalDensity.current
    val cornerPx = with(density) { radius.toPx() }
    // 低版本没有系统模糊，玻璃会退化成一层空气，底栏直接"消失"在背景里。
    val effectiveTint = if (glassBlurAvailable()) baseTint else baseTint.copy(alpha = 0.94f)
    val blurPx = with(density) { Glass.BLUR.toPx() }
    val stripDp = Glass.BAR_HEIGHT + Glass.BLUR * 2
    val stripPx = with(density) { stripDp.toPx() }
    val fullPx = with(density) { contentHeight.toPx() }

    Box(modifier.clip(shape)) {
        // ① 磨砂层。条带比玻璃上下各多出一个模糊半径，好让模糊核在玻璃边缘
        //    也有真实的邻居像素可采样——否则靠边那圈会发虚，像贴纸而不是玻璃。
        //    多出来的部分被外层的 clip 裁掉。
        if (frostedContent != null && glassBlurAvailable()) {
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(stripDp)
                    // 条带比胶囊高 BLUR*2，多出来的那截是**给模糊当邻居用的**：
                    // BlurEffect 的采样半径就是 BLUR，胶囊上沿往上的像素全靠它，
                    // 少了就会在上沿出现一圈半透明的毛边（"毛玻璃发虚"就是从这来的）。
                    //
                    // 顺序说明：renderEffect 挂在**外**层节点上，作用于该节点最终的
                    // 输出——也就是 clipPath 之后的结果。所以真正的次序是"先裁后糊"，
                    // 看似和直觉相反，但邻居仍然够用，因为裁的是内容、不是条带。
                    .graphicsLayer {
                        renderEffect = BlurEffect(blurPx, blurPx, TileMode.Clamp)
                    }
                    .drawWithContent {
                        clipPath(outlinePath(shape, size)) { this@drawWithContent.drawContent() }
                    },
            ) {
                // 内容副本：按整屏高度测量，再往上挪到只剩底部条带。
                // 挪的量是 strip - full，正好让内容的最后 strip 像素落在条带里。
                Box(
                    Modifier.layout { measurable, constraints ->
                        val placed = measurable.measure(
                            Constraints(
                                minWidth = constraints.maxWidth,
                                maxWidth = constraints.maxWidth,
                                minHeight = fullPx.roundToInt(),
                                maxHeight = fullPx.roundToInt(),
                            )
                        )
                        layout(constraints.maxWidth, stripPx.roundToInt()) {
                            placed.place(0, (stripPx - fullPx).roundToInt())
                        }
                    },
                ) {
                    // 【关键】副本必须自带那一层不透明底。
                    //
                    // 页面真正的底色是 `LiquidBackground`，而它在 KapiRoot 里包的是
                    // **整个 Box**，不在 `ScreenBody` 里面。磨砂副本只调 ScreenBody，
                    // 画出来的是"列表浮在透明上"——模糊一个透明的东西，结果还是透明，
                    // 底栏底下真正那屏内容原封不动地透上来，**一个字都没糊**。
                    //
                    // 现象极具迷惑性：磨砂层确实在画、RenderEffect 确实生效（塞一张
                    // 实色条纹进去会被糊成均色），可列表文字依旧清晰可读——因为读到的
                    // 根本不是副本，是副本**背后的原件**。层次一量就露馅：栏外文字
                    // 边缘能量 63，栏内 74，比没磨砂时还锐。
                    //
                    // 补上同色的 LiquidBackground 之后副本才是不透明的，模糊才作用在
                    // "内容"上而不是作用在"空气"上。
                    LiquidBackground(dark = dark) {
                        frostedContent()
                    }
                }
            }
        }

        // ② 玻璃的"实体"部分：奶白、厚度、边缘。压在磨砂层之上，本身不参与模糊。
        Box(
            Modifier.matchParentSize().drawBehind {
                // 磨砂玻璃总是有一层"雾"。渐变而不是平涂：上缘略重、中间略透、
                // 下缘回来一点，和顶部那道高光一起把玻璃"顶"起来。
                drawRect(
                    Brush.verticalGradient(
                        listOf(
                            effectiveTint.copy(alpha = effectiveTint.alpha * 0.97f),
                            effectiveTint.copy(alpha = effectiveTint.alpha * 0.86f),
                            effectiveTint.copy(alpha = effectiveTint.alpha * 0.93f),
                        )
                    )
                )

                // 内圈暗边：玻璃侧壁的厚度。
                val t = 1.5.dp.toPx()
                drawRect(
                    brush = Brush.verticalGradient(
                        0f to shade, 1f to shade.copy(alpha = 0f),
                        startY = 0f, endY = t,
                    ),
                    topLeft = Offset.Zero,
                    size = Size(size.width, t),
                )
                drawRect(
                    brush = Brush.verticalGradient(
                        0f to shade.copy(alpha = 0f), 1f to shade,
                        startY = size.height - t, endY = size.height,
                    ),
                    topLeft = Offset(0f, size.height - t),
                    size = Size(size.width, t),
                )
                drawRect(
                    brush = Brush.horizontalGradient(
                        0f to shade, 1f to shade.copy(alpha = 0f),
                        startX = 0f, endX = t,
                    ),
                    topLeft = Offset.Zero,
                    size = Size(t, size.height),
                )
                drawRect(
                    brush = Brush.horizontalGradient(
                        0f to shade.copy(alpha = 0f), 1f to shade,
                        startX = size.width - t, endX = size.width,
                    ),
                    topLeft = Offset(size.width - t, 0f),
                    size = Size(t, size.height),
                )

                // 顶部内侧的窄高光。位置严格控制在 shape 内缩 1.5dp 处，比渐变描边
                // 更可控——描边的亮部会被圆角摊开，顶部反而最暗。
                val inset = 1.5.dp.toPx()
                drawRect(
                    brush = Brush.verticalGradient(
                        listOf(rim, rim.copy(alpha = 0f)),
                        startY = inset,
                        endY = inset + 2.5.dp.toPx(),
                    ),
                    topLeft = Offset(cornerPx * 0.55f, inset),
                    size = Size(size.width - cornerPx * 1.1f, 2.5.dp.toPx()),
                )

                // 边缘：上亮下暗，一条路径搞定。
                //
                // 刻意只描一遍。之前试过"亮边 + 内缩半条线的暗边"两遍叠加，但
                // drawPath 没法真正内缩路径，两条线叠在同一处，亮边被暗边盖掉一半，
                // 边缘反而发灰。
                drawPath(
                    path = outlinePath(shape, size),
                    brush = Brush.verticalGradient(
                        0f to rim.copy(alpha = 0.95f),
                        0.40f to rim.copy(alpha = 0.34f),
                        1f to rimShade,
                    ),
                    style = Stroke(width = 1.dp.toPx()),
                )
            },
        )

        // 曾经在这里有第 ③ 层"虹彩"（上下缘色散），现已整层删除。
        // 删的理由和实测数据写在 `object Glass` 末尾那段注释里。

        content()
    }
}

// ---------------------------------------------------------------------------
// 顶部光效
// ---------------------------------------------------------------------------

/**
 * 顶部光效 = **常驻微光** + **滚动磨砂**。
 *
 * ## 为什么原来那块读成"白条"
 *
 * 原先顶部什么都没有，`LiquidBackground` 的竖向渐变在 y=0 是 `#F1EEE8`、到 45%
 * 才是 `#F8F6F2`，于是最上面天然比中段暗一截；再叠一道 alpha 0.10 的绿，一眼看过去
 * 就是"一条颜色略深的横带"——**平涂就是白条，跟它多暗、多绿无关**。
 *
 * 真机实测（OPPO PMA110，账本页 x=200）：y=0..200 稳定在 `(230,231,224)`，
 * 一个数都没变。对比同屏中段纸色 `(247,245,241)`——顶上比中段暗 17 级，
 * 且**完全没有梯度**。所以要修的不是颜色，是"它太平"。
 *
 * ## ColorOS 上是什么（同一台机器实测，别照着想象做）
 *
 * - 设置页**静止**时：顶部是**完全平涂 `#F0F1F2`**，y=0..450 每个像素都是
 *   `(240,241,242)`。**没有光效。**
 * - 相册页：同样平涂 `#F0F1F2`，460px 高。**也没有光效。**
 * - 设置页**滚动后**：顶部才变成磨砂——列表内容（红 SOS 图标、"SOS 紧急联络"）
 *   滚上去被磨成残影，搜索框同时浮成磨砂胶囊。
 *
 * 结论：ColorOS 那个"不是白条"的效果**只在滚动时存在**，本质是内容透出被磨掉。
 * 本函数把它拆成两半，两半都要：微光负责静止时的质感，磨砂负责滚动时的通透。
 *
 * @param frost 0..1，滚动磨砂强度。调用方按"当前列表是否已滚离顶部"驱动并做动画。
 * @param frostedContent 内容副本。**只画到 [TopAura] 那条带子那么高**——
 *   底栏那份副本是按整屏高度测量的，顶部没必要再付一次全屏合成的钱。
 */
@Composable
fun TopAura(
    topInset: Dp,
    frost: Float,
    dark: Boolean = false,
    frostedContent: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val band = topInset + 6.dp
    val blurPx = with(density) { Glass.BLUR.toPx() }
    val stripDp = band + Glass.BLUR * 2
    val stripPx = with(density) { stripDp.toPx() }
    val veil = if (dark) Glass.TintDark else Glass.BarTint

    Box(
        modifier
            .fillMaxWidth()
            .height(band)
            // 先糊后裁：模糊要拿到带子外那两截 BLUR 的邻居像素，否则下缘会发虚。
            // 裁发生在本节点，模糊在子节点上，次序和 GlassSurface 一致。
            .clipToBounds(),
    ) {
        // ① 滚动磨砂。
        if (frost > 0.01f && frostedContent != null && glassBlurAvailable()) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(stripDp)
                    .graphicsLayer {
                        renderEffect = BlurEffect(blurPx, blurPx, TileMode.Clamp)
                        alpha = frost
                    },
            ) {
                // 副本视口就等于 stripPx——和底栏那份"按整屏高度测量"不同，
                // 这里只合成落在这条带子里的那几个 item。坐标依然对得上：
                // 带子顶端就是屏幕顶端，副本 place(0,0) 即是屏幕 y=0。
                Box(
                    Modifier.layout { measurable, constraints ->
                        val placed = measurable.measure(
                            Constraints(
                                minWidth = constraints.maxWidth,
                                maxWidth = constraints.maxWidth,
                                minHeight = stripPx.roundToInt(),
                                maxHeight = stripPx.roundToInt(),
                            )
                        )
                        layout(constraints.maxWidth, stripPx.roundToInt()) {
                            placed.place(0, 0)
                        }
                    },
                ) {
                    // 【关键】副本必须自带不透明底，否则模糊的是空气不是内容。
                    // 理由和 GlassSurface 里那段一样，此处不再重复。
                    LiquidBackground(dark = dark) { frostedContent() }
                }
            }

            // 磨砂之上的雾。模糊本身不够——文字只是"糊了"，不是"隔着一层玻璃"，
            // 还得压一层中性白。这一层同时承担渐隐：上缘重、下缘轻。
            Box(
                Modifier.matchParentSize().drawBehind {
                    drawRect(
                        Brush.verticalGradient(
                            0f to veil.copy(alpha = veil.alpha * 0.95f * frost),
                            1f to veil.copy(alpha = veil.alpha * 0.55f * frost),
                        )
                    )
                }
            )

            // 下缘一道亮边。没有它，磨砂区只是"上面糊了一点"，
            // 读不出这是一条独立的玻璃；有了它，带子才和正文**脱开**。
            Box(
                Modifier.matchParentSize().drawBehind {
                    val t = 1.dp.toPx()
                    val edge = if (dark) Color(0x40FFFFFF) else Color(0xE6FFFFFF)
                    drawRect(
                        brush = Brush.verticalGradient(
                            0f to Color.Transparent,
                            1f to edge.copy(alpha = edge.alpha * frost),
                            startY = size.height - t,
                            endY = size.height,
                        ),
                        topLeft = Offset(0f, size.height - t),
                        size = Size(size.width, t),
                    )
                }
            )
        }

        // ② 常驻微光。滚不滚动都在，是"这块玻璃被上方光源照着"的那层光。
        //
        // 【为什么用竖向渐变而不是径向】第一版用的是径向（圆心在带子上方），
        // 看着像光，量出来根本不是：
        //   白色 0.60 提亮后 L≈244，接着深绿 0x2D7A64（L=89，比纸面暗 140）
        //   以 0.14 叠上去，直接把亮度吃回 222——**比不画还暗 6 级**，
        //   顶部于是比下方纸面更沉更绿，恰好就是"一条白条"被强化的样子。
        //   而且径向渐变在带底还剩 0.2 左右的不透明度，y≈185 处一刀硬切。
        //
        // 现在两层都走竖向渐变，且**都在带底收到完全透明**：
        //   ① 白色提亮，负责"亮"；② 极淡品牌绿，负责"不是纯白"。
        // 绿只给 0.055——再高就又变成压暗。
        Box(
            Modifier.matchParentSize().drawBehind {
                if (dark) {
                    drawRect(
                        Brush.verticalGradient(
                            0f to Color(0xFF4A4A55).copy(alpha = 0.40f),
                            0.5f to Color(0xFF2A2A32).copy(alpha = 0.18f),
                            1f to Color.Transparent,
                        )
                    )
                } else {
                    drawRect(
                        Brush.verticalGradient(
                            0f to Color(0xFFFFFFFF).copy(alpha = 0.62f),
                            0.45f to Color(0xFFFFFFFF).copy(alpha = 0.22f),
                            1f to Color.Transparent,
                        )
                    )
                    drawRect(
                        Brush.verticalGradient(
                            0f to Color(0xFF2D7A64).copy(alpha = 0.055f),
                            0.45f to Color(0xFF2D7A64).copy(alpha = 0.020f),
                            1f to Color.Transparent,
                        )
                    )
                }
            }
        )
    }
}

// ---------------------------------------------------------------------------
// 背景
// ---------------------------------------------------------------------------

/**
 * 应用背景。
 *
 * ## 为什么把彩虹渐变全删了
 *
 * 之前为了"让玻璃有颜色可透"，把背景做成绿→蓝→粉→紫的全屏渐变。逻辑是
 * "玻璃是提纯背后颜色的东西，背后没颜色它就白"。**方向反了。**
 *
 * 玻璃要磨的是**内容**，不是壁纸。底栏之所以像玻璃，靠的是它压着一条红色
 * 横幅、几个列表行——有结构才磨得出东西。壁纸再鲜艳，底栏底下依然是空的，
 * 照样是一块塑料。
 *
 * 底色实测（1080×2400 模拟器）：
 *  上一版 背景饱和度 0.16~0.18、卡片内 0.14~0.18 → 两者一样，像贴纸浮在彩纸上
 *  这一版 背景饱和度 0.01~0.02 → 卡片的白才是画面里最亮的东西，层级立刻立住
 */
@Composable
fun LiquidBackground(
    modifier: Modifier = Modifier,
    dark: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier.fillMaxSize().drawBehind {
            if (dark) {
                drawRect(
                    Brush.verticalGradient(
                        0f to Color(0xFF16161A),
                        0.55f to Color(0xFF101013),
                        1f to Color(0xFF0C0C0E),
                    ),
                )
            } else {
                // 暖白打底。中间最亮、两端略沉，做出"一张纸被顶光照着"的错觉。
                drawRect(
                    Brush.verticalGradient(
                        0f to Color(0xFFF1EEE8),
                        0.45f to Color(0xFFF8F6F2),
                        1f to Color(0xFFEFECE5),
                    ),
                )
                // 顶部一抹极淡的品牌绿。只在状态栏那一段，几乎看不见——
                // 它的作用是让冷白不那么"电脑屏"，而不是"这是一张渐变背景"。
                drawRect(
                    Brush.radialGradient(
                        colors = listOf(Color(0xFF2D7A64).copy(alpha = 0.10f), Color.Transparent),
                        center = Offset(size.width * 0.5f, -size.height * 0.06f),
                        radius = size.width * 0.95f,
                    ),
                )
                // 底部一抹暖光，和顶部的绿形成一个很轻的对位。
                // 它正好落在底栏后面，磨出来的胶囊会带一点暖调而不是死白。
                drawRect(
                    Brush.radialGradient(
                        colors = listOf(Color(0xFFC99A6B).copy(alpha = 0.09f), Color.Transparent),
                        center = Offset(size.width * 0.82f, size.height * 1.04f),
                        radius = size.width * 0.85f,
                    ),
                )
            }
        },
    ) {
        // 【根上兜底】Material3 的 LocalContentColor 默认是 Color.Black。任何漏写
        // `color =` 的 Text 都会拿到纯黑——浅色下白底黑字没问题，深色下就是
        // **黑字画在近黑卡片上，对比度 1.24:1，等于隐形**。
        //
        // 踩过三次同源：「设置」标题、记一笔标题、NAS 同步卡片标题，全是同一类。
        // 逐个补 `color = Ink` 是打地鼠，漏一个就再黑一个，而它**不报错**——
        // 截图查看器还会自动拉对比度，肉眼看过去只是"有点暗"，于是被当成
        // 审美问题而不是 bug 一路放过去。这里改成在根上给一个主题正确的默认值，
        // 以后新写的 Text 忘了写 color，最差也只是"用正文色"，不会变成隐形。
        CompositionLocalProvider(LocalContentColor provides Ink) {
            content()
        }
    }
}

// ---------------------------------------------------------------------------
// 卡片
// ---------------------------------------------------------------------------

/**
 * 内容卡片。
 *
 * 刻意**不是玻璃**。玻璃留给底栏，卡片用实体材质，理由见 [LiquidBackground]。
 *
 * 层级靠三样东西建立，不靠半透明：
 *  1. 白底和暖灰底的明度差——卡片永远是画面里最亮的东西
 *  2. 一层很淡的投影——让它浮起来，而不是贴在纸上
 *  3. 一圈发丝边——浅色界面上投影容易糊，这条边是"这是卡片"的最终确认
 *
 * 圆角 24dp：比 16dp 更软，比 32dp 更收。记账 App 一屏要放四张卡，
 * 圆角太大会让卡片之间糊成一片。
 */
@Composable
fun SurfaceCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(24.dp),
    // 默认跟随系统。四个界面都调 SurfaceCard，漏传一个就是"深色下白卡片"，
    // 而这种半深半浅的界面比全浅还难看，所以默认值比逐处传参更不容易出错。
    dark: Boolean = androidx.compose.foundation.isSystemInDarkTheme(),
    content: @Composable BoxScope.() -> Unit,
) {
    val fill = if (dark) Color(0xFF1C1C20) else Color(0xFFFFFFFF)
    val edge = if (dark) Color(0x14FFFFFF) else Color(0x0F1A1917)
    Box(
        modifier
            .shadow(
                elevation = if (dark) 2.dp else 10.dp,
                shape = shape,
                ambientColor = if (dark) Color.Black else Color(0xFF2A2620),
                spotColor = if (dark) Color.Black else Color(0xFF2A2620),
            )
            .clip(shape)
            .background(fill)
            .drawBehind {
                // 发丝边画在**内容之下**、clip 之内，所以不会被圆角吃掉半条线。
                drawPath(
                    path = outlinePath(shape, size),
                    color = edge,
                    style = Stroke(width = 1.dp.toPx()),
                )
            },
        content = content,
    )
}

// ---------------------------------------------------------------------------
// 点按动效
// ---------------------------------------------------------------------------

/**
 * 统一的点按反馈：**涟漪 + 轻微缩放**。
 *
 * 上一版大量用了 `indication = null`，是为了让自定义样式的按钮"干净"，
 * 结果是整个 App 点下去毫无反应。涟漪只是开始，缩放补上了"被按下去"这一层——
 * 0.955 的缩放在 Material 的动效语汇里叫 pressed state，比纯涟漪更"实"。
 *
 * 用弹簧而不是 tween：松手时要有一点点回弹，才像"弹起来"而不是"滑回去"。
 */
@Composable
fun Modifier.glassClickable(
    enabled: Boolean = true,
    role: Role? = null,
    pressedScale: Float = 0.955f,
    onClickLabel: String? = null,
    onClick: () -> Unit,
): Modifier {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) pressedScale else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "pressScale",
    )
    return this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .clickable(
            enabled = enabled,
            interactionSource = interaction,
            indication = androidx.compose.material3.ripple(),
            role = role,
            onClickLabel = onClickLabel,
            onClick = onClick,
        )
}