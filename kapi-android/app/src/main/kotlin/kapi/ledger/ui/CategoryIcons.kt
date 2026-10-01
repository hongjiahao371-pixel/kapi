package kapi.ledger.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Apartment
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Handyman
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LocalHospital
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material.icons.filled.Work
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * 分类 / 账户的图标。
 *
 * ## 为什么要图标而不是首字
 *
 * 原来圆圈里放的是分类名的**第一个字**。问题不在"不好看"，在**扫不出来**：
 * "餐"、"交"、"购"、"居"、"娱"、"医" 六个字里，字形复杂度差不多、都在同一
 * 个尺寸上，用户在列表里快速下滑时区分不出哪行是哪类，只能逐字读。一屏
 * 十几行的时候这个差别很明显。
 *
 * 换成图标之后，餐饮是叉子、交通是车、医疗是十字、居住是房子——形状差异
 * 远大于字形差异，眼睛扫过去就能分类。这也正是几份独立的记账 App 设计
 * 拆解里都把"类别图标"列为账单行的必备元素的原因。
 *
 * ## 为什么按**名字**匹配而不是按 id
 *
 * 分类 id（cat-1…）在不同设备、不同同步顺序下不一样，NAS 上还会被重排。
 * 名字才是稳定的语义。按名字查表还带来一个额外好处：用户自己新建的分类
 * 只要起名跟内置的常用词一致（比如"餐饮"），就自动有图标。
 *
 * 查不到时回落到 [Icons.Outlined.MoreHoriz] 而不是空白圆圈——空白会被读成
 * "没加载出来"，三个点读成"其他"，都不骗人。
 */
object CategoryIcons {

    private val byName: Map<String, ImageVector> = mapOf(
        // ---- 支出 ----
        "餐饮" to Icons.Filled.Restaurant,
        "交通" to Icons.Filled.DirectionsCar,
        "购物" to Icons.Filled.ShoppingBag,
        "居住" to Icons.Filled.Home,
        "娱乐" to Icons.Filled.Movie,
        "医疗" to Icons.Filled.LocalHospital,
        "教育" to Icons.Filled.School,
        "通讯" to Icons.Filled.PhoneAndroid,
        "日用" to Icons.Filled.CleaningServices,
        "人情" to Icons.Filled.Groups,
        "还款" to Icons.Filled.CreditCard,
        "房租" to Icons.Filled.Apartment,

        // ---- 收入 ----
        "工资" to Icons.Filled.Work,
        "奖金" to Icons.Filled.EmojiEvents,
        "兼职" to Icons.Filled.Handyman,
        "理财" to Icons.Filled.TrendingUp,
        "报销" to Icons.Filled.ReceiptLong,
        "红包" to Icons.Filled.CardGiftcard,
        "利息" to Icons.Filled.Savings,

        // ---- 兜底 ----
        // 支出和收入两边都有"其他"，共用一个问号脸。查表 miss 时会落到这里，
        // 所以它**必须**在表里，否则 byName["其他"] 永远是 null，退化成三个点。
        "其他" to Icons.Outlined.HelpOutline,

        // ---- 账户 ----
        "现金" to Icons.Filled.Payments,
        "银行卡" to Icons.Filled.CreditCard,
        "支付宝" to Icons.Filled.AccountBalanceWallet,
        "微信" to Icons.Filled.AccountBalance,
    )

    /** 查不到就用"其他"那张脸；连名字都没有就给三个点。 */
    fun of(name: String?): ImageVector =
        when {
            name == null -> Icons.Outlined.MoreHoriz
            else -> byName[name] ?: byName["其他"] ?: Icons.Outlined.MoreHoriz
        }
}
