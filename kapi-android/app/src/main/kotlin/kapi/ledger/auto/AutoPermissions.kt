package kapi.ledger.auto

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

/**
 * 自动记账两项授权的真实状态。
 *
 * 界面上最容易骗人的就是这一块：显示"未授权"但其实已经开了（用户点进去发现开关
 * 本来就是亮的），或者显示"已授权"但服务早被系统杀掉了（用户以为在记账，其
 * 实什么也没发生）。所以这里查的是**系统里真正记录的状态**，不是我们自己存
 * 的那个开关。
 */
data class AutoPermissionState(
    /** 通知栏监听在系统里是否被勾上。 */
    val notificationGranted: Boolean,
    /** 无障碍服务在系统里是否被勾上。 */
    val accessibilityGranted: Boolean,
    /** 监听服务此刻是否真的连着（系统内存紧张会主动断开）。 */
    val notificationConnected: Boolean,
) {
    /** 授权了但此刻没连上——用户看到的是"已开但没在跑"，这两件事不能混成一句"已授权"。 */
    val notificationLive: Boolean get() = notificationGranted && notificationConnected
    val accessibilityLive: Boolean get() = accessibilityGranted

    /** 至少一条通路能用。 */
    val anythingWorks: Boolean get() = notificationLive || accessibilityLive
}

/** 查状态和跳转系统设置。分开是因为"查"每帧都可能调，"跳"必须由用户点。 */
object AutoPermissions {

    fun read(context: Context): AutoPermissionState {
        val ctx = context.applicationContext
        return AutoPermissionState(
            notificationGranted = isNotificationListenerEnabled(ctx),
            accessibilityGranted = isAccessibilityEnabled(ctx),
            notificationConnected = KapiNotificationListener.connected,
        )
    }

    /**
     * 通知监听的真实开关状态。
     *
     * **不能**只看 `KapiNotificationListener.connected`：系统会在内存紧张时断开
     * 监听服务，之后 connected 就是 false，但用户的授权还在，下次系统会自己
     * 重新绑。两个信号要一起看，界面上也要分开说。
     */
    private fun isNotificationListenerEnabled(ctx: Context): Boolean {
        val flat = Settings.Secure.getString(
            ctx.contentResolver, "enabled_notification_listeners",
        ) ?: return false
        val expected = ComponentName(ctx, KapiNotificationListener::class.java)
        return flat.split(':').any { ComponentName.unflattenFromString(it) == expected }
    }

    private fun isAccessibilityEnabled(ctx: Context): Boolean {
        val enabled = Settings.Secure.getString(
            ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        val expected = ComponentName(ctx, KapiAccessibilityService::class.java)
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == expected }
    }

    /**
     * 跳到无障碍设置列表。
     *
     * **第一次点开就是"触发拦截"那一步**——见 [SIDELOAD_RESTRICTION]。
     */
    fun openAccessibilitySettings(context: Context): Boolean {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return start(context, intent)
    }

    /**
     * 跳到本 App 的信息页，也就是「允许受限设置」所在的地方。
     *
     * 用 `ACTION_APPLICATION_DETAILS_SETTINGS` + `package:` Uri。注意
     * `ACTION_APP_INFO_SETTINGS` 这个常量**不存在**——`Settings` 类里只有
     * `EXTRA_APP_PACKAGE` 那个字符串，没有配套的 action，硬写会编译不过。
     */
    fun openAppInfoSettings(context: Context): Boolean {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.fromParts("package", context.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return start(context, intent)
    }

    /**
     * 跳到通知使用权设置。
     *
     * Android 13+ 用户可以在**单个 App** 的粒度上关掉通知使用权，系统的总开关
     * 分不开，所以"已授权"必须查这一项，不能只查 `areNotificationsEnabled()`。
     */
    fun openNotificationListenerSettings(context: Context): Boolean {
        val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (start(context, intent)) return true
        // 部分 ROM（尤其国内定制系统）没有这个 Activity，退到 App 详情页
        val fallback = Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
            .putExtra(
                Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                ComponentName(context, KapiNotificationListener::class.java).flattenToString(),
            )
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (start(context, fallback)) return true
        return openAppInfoSettings(context)
    }

    private fun start(context: Context, intent: Intent): Boolean = runCatching {
        context.startActivity(intent)
        true
    }.getOrDefault(false)

    /**
     * 侧载 APK 被系统「受限设置」挡住时的完整手动路径。
     *
     * 事实基础（多来源一致：Android Police、XDA、Mishaal Rahman 的说明、
     * 以及几份厂商整理）：
     *  - Android 13 起，**非会话安装器**装的包（也就是从浏览器/聊天/文件管理器
     *    装的侧载）无法直接开启**无障碍**和**通知使用权**。
     *  - 绕过办法是 App 信息页右上角 ⋮ 里的「允许受限设置」。
     *  - **但这个菜单项平时不显示**，必须先在无障碍/通知列表里点一次那个灰掉的
     *    开关、被弹窗拦下之后，系统才把它放出来。跳过这一步的用户会得出
     *    "我的手机没有这个选项"的结论——这正是绝大多数人卡住的地方。
     *  - 小米/红米（MIUI 14+/澎湃）需要**先打开开发者选项**才显示该入口。
     *
     * 所以界面上必须写全这三步，不能只写"去 App 信息里打开"。
     */
    const val SIDELOAD_RESTRICTION: String =
        "如果你在系统设置里找不到这个开关：\n" +
            "① 先去无障碍（或通知使用权）列表里点一次那个灰掉的开关，被弹窗拦下" +
            "② 再回到本 App 的信息页，右上角 ⋮ 里才会出现「允许受限设置」" +
            "③ 打开它，回来重新勾选\n" +
            "（小米 / 澎湃需要先开启开发者选项）"

    /**
     * 微信侧的前置条件：**授权了通知使用权，微信自己不发货**。
     *
     * ## 这是一个会让人以为"已经配好了"的黑洞
     *
     * 2026-09-30 真机实测：用户的通知使用权是开的、监听服务也连着，
     * 微信的聊天消息通知能收到（`收到 com.tencent.mm 的通知：koryking|智谱嘛|`），
     * 但**付款时段里一条支付通知都没有**。
     *
     * 界面上此时显示的是「已开启·已连上」，没有任何异常信号——
     * 用户会得出"App 坏了"的结论，而真实原因在**另一个 App 里面**。
     * 这类跨应用的配置依赖必须由我们写出来，用户不可能自己想到要去微信里翻设置。
     *
     * ## 为什么微信要用户手动开
     *
     * 微信把支付通知收在「服务通知」这个聚合会话下面，默认档位偏向省电。
     * 多份独立资料（腾讯自己的说明 + 若干份设置教程）都指向同一组开关。
     * **尚未在本机验证过**——设备当时已断开。回来后第一件事就是拿真机确认，
     * 确认之前这段文字按"待验证的前置条件"对待。
     */
    const val WECHAT_NOTIFY_PREREQUISITE: String =
        "微信默认不弹支付通知，这一段要你自己去微信里开（本 App 无权代开）：\n" +
            "① 微信 → 我 → 设置 → 消息通知（旧版叫「新消息通知」）→ 确认「接收新消息通知」是开的" +
            "→ 往下滑找到「服务通知」→ 打开「微信支付通知」\n" +
            "② 微信 → 我 → 服务 → 钱包 → 右上角 ⋯ → 消费者保护 → 服务通知管理" +
            "→ 打开「支付到账通知」和「退款通知」\n" +
            "（两处都要开，缺一个微信就还是不发。改完可能要等一会儿才生效）"
}

/** 界面上给用户看的一句话：这个状态到底意味着什么、下一步做什么。 */
fun AutoPermissionState.explain(channel: String): String = when (channel) {
    "NOTIFY" -> when {
        notificationLive -> "已连上。微信、支付宝、银行的支付通知会自动进来"
        notificationGranted -> "已授权，但此刻没连上（系统会在内存紧张时断开，过一会儿会自己回来）"
        else -> "还没打开「通知使用权」。这是最省事的一条路，建议先开这个"
    }
    "A11Y" -> when {
        accessibilityLive -> "已连上。支付成功页面上的文字会自动进来"
        else -> "还没打开无障碍开关。这条路失败率更高，是通知栏的补充，不是替代"
    }
    else -> ""
}
