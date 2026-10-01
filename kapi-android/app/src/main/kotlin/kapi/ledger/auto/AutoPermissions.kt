package kapi.ledger.auto

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

/**
 * 自动记账授权的真实状态。
 *
 * 界面上最容易骗人的就是这一块：显示"未授权"但其实已经开了（用户点进去发现开关
 * 本来就是亮的），或者显示"已授权"但服务早被系统杀掉了（用户以为在记账，其
 * 实什么也没发生）。所以这里查的是**系统里真正记录的状态**，不是我们自己存
 * 的那个开关。
 *
 * 2026-10-01 起只剩无障碍一条通路：微信、支付宝都不发支付通知，而微信在本机的
 * 无障碍树里又什么都读不到（见 [KapiAccessibilityService] 的类注释）。
 * 原先的"已授权但此刻没连上"那一档（`notificationConnected`）随通知服务一起删除——
 * 无障碍没有"授权了但此刻没连上"这个状态，它要么在 `Bound services` 里，要么不在。
 */
data class AutoPermissionState(
    /** 无障碍服务在系统里是否被勾上，且全局总开关是开的。 */
    val accessibilityGranted: Boolean,
) {
    /** 已授权且此刻真的在跑。 */
    val accessibilityLive: Boolean get() = accessibilityGranted

    /** 至少一条通路能用。 */
    val anythingWorks: Boolean get() = accessibilityLive
}

/** 查状态和跳转系统设置。分开是因为"查"每帧都可能调，"跳"必须由用户点。 */
object AutoPermissions {

    fun read(context: Context): AutoPermissionState {
        val ctx = context.applicationContext
        return AutoPermissionState(
            accessibilityGranted = isAccessibilityEnabled(ctx),
        )
    }

    /**
     * 无障碍的**全局总开关**。
     *
     * 这一项不能漏。`ENABLED_ACCESSIBILITY_SERVICES` 里可以留着组件名，而
     * `accessibility_enabled` 是 0——用户在快捷设置里把无障碍整个关掉时，系统**不会**
     * 清空那个列表。此时只查列表就会得出"已开启"，而实际 `Bound services` 是空的，
     * 一条事件都收不到。界面上会显示「已连上。支付成功页面上的文字会自动进来」——
     * 这正是本文件开头说的那种"最容易骗人"的状态，只是方向反了：不是显示未授权，
     * 是显示已授权而实际没在跑。
     */
    private fun isAccessibilityMasterOn(ctx: Context): Boolean =
        Settings.Secure.getInt(ctx.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1

    private fun isAccessibilityEnabled(ctx: Context): Boolean {
        if (!isAccessibilityMasterOn(ctx)) return false
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
     *    装的侧载）无法直接开启**无障碍**。
     *  - 绕过办法是 App 信息页右上角 ⋮ 里的「允许受限设置」。
     *  - **但这个菜单项平时不显示**，必须先在无障碍列表里点一次那个灰掉的
     *    开关、被弹窗拦下之后，系统才把它放出来。跳过这一步的用户会得出
     *    "我的手机没有这个选项"的结论——这正是绝大多数人卡住的地方。
     *  - 小米/红米（MIUI 14+/澎湃）需要**先打开开发者选项**才显示该入口。
     *
     * 所以界面上必须写全这三步，不能只写"去 App 信息里打开"。
     */
    const val SIDELOAD_RESTRICTION: String =
        "如果你在系统设置里找不到这个开关：\n" +
            "① 先去无障碍列表里点一次那个灰掉的开关，被弹窗拦下" +
            "② 再回到本 App 的信息页，右上角 ⋮ 里才会出现「允许受限设置」" +
            "③ 打开它，回来重新勾选\n" +
            "（小米 / 澎湃需要先开启开发者选项）"
}

/** 界面上给用户看的一句话：这个状态到底意味着什么、下一步做什么。 */
fun AutoPermissionState.explain(): String = when {
    accessibilityLive -> "已连上。支付成功页面上的文字会自动进来"
    else -> "还没打开无障碍开关。这是自动记账唯一的通路，必须开"
}
