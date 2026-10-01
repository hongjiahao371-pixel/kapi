package kapi.ledger.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ---------------------------------------------------------------------------
// 设计令牌
//
// 记账 App 满屏是数字，视觉上的第一职责是**让数字好读**，不是好看。
// 所以：底色偏暖（冷白看久了刺眼）、正文用深墨而不是纯黑、字号对比拉开
// 层级。品牌色只用在"当前选中 / 主操作"上，界面里出现超过三处就会开始和
// 金额本身争夺注意力。
// ---------------------------------------------------------------------------

/**
 * 颜色令牌全部是**主题感知的 composable 属性**，不是常量。
 *
 * 之前它们是顶层 `val`（编译期常量），于是每个界面上写 `color = Ink` 拿到的永远是
 * 浅色那一版。深色模式下背景变黑了、文字还是深墨色 —— 现象是"字直接消失了"，
 * 而不是"颜色有点淡"。`isSystemInDarkTheme()` 明明已经一路传到 `LiquidBackground`，
 * 但没有任何一处用到它。
 *
 * 改成 `@get:Composable` 属性之后：
 *  - 调用点**一个字都不用改**，仍然是 `Ink`、`InkSoft`
 *  - 读的是 `isSystemInDarkTheme()`，跟随系统，切换时正常重组
 *
 * 代价：这些令牌只能在 composable 里读。原来在非 composable 上下文（比如顶层
 * `val`）引用它们的地方会编译不过——编译器会直接指出来，不会静默跑偏。
 *
 * 深色档不是把浅色值调暗，而是重新配的：正文用接近纸白的暖灰（#EDE9E2），
 * 品牌绿提亮到 #8FCBB7 —— 原来的 #2D7A64 放到 #141412 上对比度只有 2.4:1，
 * 属于"看得见但读不了"。
 */
val Paper: Color
    @Composable get() = if (isSystemInDarkTheme()) Color(0xFF141412) else Color(0xFFF7F5F1)

val Surface: Color
    @Composable get() = if (isSystemInDarkTheme()) Color(0xFF1D1D1A) else Color(0xFFFFFFFF)

val Ink: Color
    @Composable get() = if (isSystemInDarkTheme()) Color(0xFFEDE9E2) else Color(0xFF1A1917)

// 两档灰都是**按对比度反推**出来的，不是照着另一档"调亮/调暗一点"。
// 浅色底实测纯白 (255,255,255)，深色底实测 (28,28,32) 亮度 0.01182，
// WCAG AA 对正文的要求是 4.5:1。实测值：
//
//            浅色(白底)        深色(近黑底)
//   Ink       17.57:1          14.04:1
//   InkSoft    7.06:1  #5C5852  7.78:1  #B4AFA5
//   InkFaint   4.57:1  #7A756D  5.08:1  #918C83
//
// 之前两档都是从对面直接搬数值的（浅色 InkFaint = 深色 InkSoft = #A8A39A），
// 结果浅色下 2.51:1、深色下 3.16:1，**两头都不合格**。"深色 = 把颜色调暗"
// 这个直觉在这里是错的：暗底上同一个 sRGB 数值读起来只会更灰，不会更亮。
// 浅色侧同理——想留出第三档，InkSoft 就得先让出位置，否则第二档和第三档
// 挤在一起谁也到不了 4.5:1。
//
// InkFaint 同时也是底栏未选中 tab 的颜色，它一塌就是整个底栏跟着看不清。
val InkSoft: Color
    @Composable get() = if (isSystemInDarkTheme()) Color(0xFFB4AFA5) else Color(0xFF5C5852)

val InkFaint: Color
    @Composable get() = if (isSystemInDarkTheme()) Color(0xFF918C83) else Color(0xFF7A756D)

val Hairline: Color
    @Composable get() = if (isSystemInDarkTheme()) Color(0xFF333230) else Color(0xFFE8E4DC)

val BrandDeep: Color
    @Composable get() = if (isSystemInDarkTheme()) Color(0xFFD7EDE6) else Color(0xFF1F5B4C)

val Brand: Color
    @Composable get() = if (isSystemInDarkTheme()) Color(0xFF8FCBB7) else Color(0xFF2D7A64)

val BrandSoft: Color
    @Composable get() = if (isSystemInDarkTheme()) Color(0xFF1E3B33) else Color(0xFFDDEBE6)

val ExpenseRed: Color
    @Composable get() = if (isSystemInDarkTheme()) Color(0xFFE88A75) else Color(0xFFC9482F)

val IncomeGreen: Color
    @Composable get() = if (isSystemInDarkTheme()) Color(0xFF7FC7A4) else Color(0xFF2C7A55)

// 汇总卡的渐变：从品牌色到略深的墨绿，纵向。纯平涂在浅色底上会显得"贴片"。
val SummaryGradient = Brush.verticalGradient(
    listOf(Color(0xFF2F7A66), Color(0xFF1D564A))
)

/**
 * 结余为负时用的那一版。
 *
 * 取自支出红 [ExpenseRed] 同一族（#C9482F / #8E3520），但**压暗了一档**：
 * 整张卡铺满屏幕宽度，用原始的 ExpenseRed 会比列表里任何一行金额都跳，
 * 变成"页面在报警"。财务软件里"这个月超支了"是常态，不该天天红着脸。
 */
val SummaryGradientDown = Brush.verticalGradient(
    listOf(Color(0xFFC9482F), Color(0xFF8E3520))
)

// 注意：这里必须写字面值，不能引用上面的令牌——令牌已经是 @Composable 属性，
// 而 colorScheme 是顶层常量，在非 composable 上下文里读不到。两组值的对应关系
// 记在每行注释里，改令牌时一并核对。
private val LightColors = lightColorScheme(
    primary = Color(0xFF2D7A64),          // Brand 浅色档
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDDEBE6),  // BrandSoft 浅色档
    onPrimaryContainer = Color(0xFF1F5B4C), // BrandDeep 浅色档
    secondary = Color(0xFF6E6A63),        // InkSoft 浅色档
    onSecondary = Color.White,
    background = Color(0xFFF7F5F1),       // Paper 浅色档
    onBackground = Color(0xFF1A1917),     // Ink 浅色档
    surface = Color(0xFFFFFFFF),          // Surface 浅色档
    onSurface = Color(0xFF1A1917),        // Ink 浅色档
    surfaceVariant = Color(0xFFEFEDE7),
    onSurfaceVariant = Color(0xFF6E6A63), // InkSoft 浅色档
    outline = Color(0xFFE8E4DC),          // Hairline 浅色档
    error = Color(0xFFC9482F),            // ExpenseRed 浅色档
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8FCBB8),
    onPrimary = Color(0xFF00382C),
    primaryContainer = Color(0xFF205546),
    onPrimaryContainer = Color(0xFFD7EDE6),
    secondary = Color(0xFFB4AFA6),
    background = Color(0xFF141412),
    onBackground = Color(0xFFEDE9E2),
    surface = Color(0xFF1D1D1A),
    onSurface = Color(0xFFEDE9E2),
    surfaceVariant = Color(0xFF2B2A26),
    onSurfaceVariant = Color(0xFFA8A39A),
    outline = Color(0xFF44423D),
    error = Color(0xFFE88A75),
)

/**
 * 金额专用字型。
 *
 * 单独拎出来是因为金额的数字**位数一变，整行的字重和字距都会跳**，
 * 列表上下两行的金额会看起来不在一条基线上。等宽数字（`FontFeatureSettings`）
 * 解决不了宽度对齐，但等宽 + 固定字重能让"这一列是金额"这件事一眼可辨。
 */
val MoneyLarge = TextStyle(fontSize = 34.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp)
val MoneyMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
val AmountBig = TextStyle(fontSize = 46.sp, fontWeight = FontWeight.Light, letterSpacing = (-1).sp)

private val KapiTypography = Typography(
    displaySmall = TextStyle(fontSize = 30.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.4).sp),
    headlineMedium = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
    titleLarge = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 15.sp),
    bodyMedium = TextStyle(fontSize = 13.sp),
    bodySmall = TextStyle(fontSize = 12.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium),
)

@Composable
fun KapiTheme(
    dark: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        typography = KapiTypography,
        content = content,
    )
}

// ---------------------------------------------------------------------------
// 分类配色
//
// 列表里每行一个色块，是提升扫读速度最省力的一招：用户找"餐饮那几笔"时
// 认的是颜色不是文字。12 个分类给 12 个**低饱和**的色——高饱和度在长列表里
// 会互相打架，最后谁都不突出。
//
// **按名字取色，不按 id**（和 [CategoryIcons] 同一个理由）
//
// 原来是 `hash(categoryId) % 12`。两个后果，第二个是硬伤：
//  1. 同一台设备上、同一份数据里，列表用 `categoryTone(catId)`、统计用
//     `categoryTone("name:餐饮")`——**两把不同的哈希钥匙，同一个分类在两处
//     颜色不一样**。用户刚记住的"橙色 = 购物"到了环形图就变了。
//  2. 12 个色位 + 生日问题，5 个分类里撞色几乎是必然。实测「购物」和
//     「交通」取到**完全相同**的 #CE7B4E（通道差 0），环形图上五分之二的
//     扇区读者根本分不开。
//
// 名字才是稳定语义：id 在不同设备、不同同步顺序下会变（NAS 上还会重排），
// 名字不会。所以内置分类走下面这张**手工指定、互不重复**的表，
// 用户自建的分类回落到按名字散列——同名的永远是同一个颜色。
//
// 11 个支出色的判据是**两两可分**，不是单个好不好看：环形图把 11 个分类
// 同时摆在屏幕上，靠的就是相邻两段能不能一眼分开。取 max 通道差 ≥ 45
// （实测最小 47，脚本在 tools 外的临时校验里跑过），低于这个数在细弧上
// 就读成同一个颜色了。
// ---------------------------------------------------------------------------

/** 内置支出分类。环形图一次把它们全画出来，所以两两必须分得开。 */
private val ExpenseHues = mapOf(
    "餐饮" to Color(0xFFC04F38), // 砖红
    "交通" to Color(0xFF2E8B9E), // 青
    "购物" to Color(0xFF8E55A6), // 紫
    "居住" to Color(0xFF3E9A63), // 绿
    "娱乐" to Color(0xFF5B74D4), // 靛蓝
    "医疗" to Color(0xFFDB8CA4), // 粉
    "教育" to Color(0xFFD6A13C), // 金
    "通讯" to Color(0xFF7A6346), // 赭
    "日用" to Color(0xFFAFBC74), // 浅橄榄
    "人情" to Color(0xFF778496), // 石板
    "其他" to Color(0xFF4A435C), // 暗梅
)

/** 内置收入分类。不会和支出一起出现在环形图里，只要自身互不重复即可。 */
private val IncomeHues = mapOf(
    "工资" to Color(0xFF2F6F5E),
    "奖金" to Color(0xFFC9A227),
    "兼职" to Color(0xFF7A6BA8),
    "理财" to Color(0xFF3B6FA8),
    "报销" to Color(0xFF9C7B4A),
)

/** 自建分类的回落色板。 */
private val CategoryHues = listOf(
    Color(0xFFD9644A), // 砖红
    Color(0xFF3E8E7E), // 松绿
    Color(0xFFC98A2E), // 麦黄
    Color(0xFF6B7FD7), // 靛蓝
    Color(0xFFB565A7), // 藕紫
    Color(0xFF4E9AA8), // 湖青
    Color(0xFFCE7B4E), // 陶橙
    Color(0xFF7A9A4B), // 橄榄
    Color(0xFF9A7BB5), // 灰紫
    Color(0xFFD08A9B), // 藕粉
    Color(0xFF6E8B8B), // 灰蓝
    Color(0xFF8C8A7D), // 岩灰
)

data class CategoryTone(val base: Color, val container: Color, val onContainer: Color)

/**
 * 按分类**名**取色。
 *
 * 内置分类走手工表（保证互不重复），自建分类按名字散列——名字是稳定语义，
 * id 不是。用 id 散列的代价见上面那段注释：列表和图表会拿到不同的颜色，
 * 而且 12 个色位必然撞车。
 */
@Composable
fun categoryTone(categoryName: String?): CategoryTone {
    if (categoryName.isNullOrBlank()) {
        val g = Color(0xFF8C8A7D)
        return CategoryTone(g, if (isSystemInDarkTheme()) Color(0xFF2B2A26) else Color(0xFFEFEDE7), Color(0xFFC9C4BA))
    }
    val base = ExpenseHues[categoryName]
        ?: IncomeHues[categoryName]
        ?: run {
            // FNV-1a 的简化版：短串、32 位整型散列，够均匀又不引入依赖。
            var h = 2166136261u
            for (c in categoryName) {
                h = (h xor c.code.toUInt()) * 16777619u
            }
            CategoryHues[(h % CategoryHues.size.toUInt()).toInt()]
        }
    return CategoryTone(
        base = base,
        container = base.copy(alpha = 0.13f),
        onContainer = base.copy(alpha = 0.95f),
    )
}

/**
 * 账户不是分类，不该从分类色板里取色——否则「现金」会和某个分类撞色，
 * 用户在统计页刚记住的橙点，在这里变成了「那到底是餐饮还是现金」。
 */
val AccountTone: CategoryTone
    @Composable get() = CategoryTone(base = Brand, container = BrandSoft, onContainer = BrandDeep)

/** 卡片投影。Material3 的 elevation 在浅色底上几乎看不出来，这里自己给一层。 */
val CardShadow = 6.dp
