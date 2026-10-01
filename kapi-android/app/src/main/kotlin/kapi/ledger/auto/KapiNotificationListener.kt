package kapi.ledger.auto

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import kapi.core.auto.AutoBook
import kapi.ledger.container
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 通知栏自动记账。
 *
 * 这条路**不需要无障碍权限**，是 Android 的公开 API，用户在系统设置里点一个
 * 开关就行。信息量和读屏一样全：微信、支付宝、各家银行的付款成功通知里本来就
 * 写着「¥23.50 星巴克」——那是它们自己写给用户看的，不存在"对方屏蔽读屏"的问题。
 *
 * 所以虽然无障碍那条覆盖更广，这条才是**更可靠的主路**。两条都做，互为兜底。
 */
class KapiNotificationListener : NotificationListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onListenerConnected() {
        super.onListenerConnected()
        connected = true
        Log.i(TAG, "通知监听已连接")
    }

    override fun onListenerDisconnected() {
        connected = false
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        connected = false
        scope.cancel()
        super.onDestroy()
    }

    /**
     * 只在 [NotificationListenerService.requestRebind] 之后调。
     *
     * 系统在内存紧张时会断开这个服务，这是 Android 12 起的正常行为，不是 bug。
     * 官方推荐的做法是在这里重新请求绑定，而不是在别处轮询。
     */
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val n = sbn ?: return
        val pkg = n.packageName ?: return

        val store = runCatching { container.autoBookStore }.getOrNull() ?: return
        if (!store.notificationEnabled) return
        if (pkg !in store.watchedPackages) return

        val text = readableText(n) ?: return
        if (text.isBlank()) return

        // 白名单里任何一个 App 发了通知，就把原文记下来。
        //
        // 2026-09-30 真机验收时栽在这里：微信付完款**一条支付通知都没发**
        // （logcat -b events 的 notification_enqueue 只有三条 category=msg 的
        // 聊天消息），而监听器对"收到了但被支付门挡掉"和"根本没收到"的表现
        // 一样——日志里一片空白。没有这一行就无法区分「微信不发」和
        // 「我们收到了但没认出来」，而这两者的修法完全相反。
        Log.i(TAG, "收到 $pkg 的通知：${text.take(120).replace('\n', '|')}")

        // 用通知的 postTime 而不是当前时刻：通知可能挂在通知栏里几小时才被处理，
        // 记成"刚刚花的"会让那天的账目凭空挪位。
        val at = n.postTime.takeIf { it > 0L } ?: System.currentTimeMillis()

        val pipeline = runCatching { container.autoCapture }.getOrNull() ?: return
        scope.launch {
            runCatching {
                pipeline.capture(
                    AutoCapturePipeline.CaptureInput(
                        channel = AutoBook.CaptureChannel.NOTIFY,
                        originPackage = pkg,
                        rawText = text,
                        occurredAt = at,
                    )
                )
            }.onFailure {
                Log.w(TAG, "通知捕获失败：${it.javaClass.name}: ${it.message}", it)
            }
        }
    }

    /**
     * 同一笔支付，银行 App 常常会先发一条"交易进行中"再更新成"交易成功"。
     * 那条进行中的通知里没有金额，解析不出来，**不该**写草稿——但也不该让
     * 界面上出现一条"交易进行中 ¥0.00"的东西吓人。所以整条忽略。
     *
     * 被忽略的通知**同样参与去重**这件事有点诱人（更新一次就少一次遍历），
     * 但那是把去重语义搞混：去重管的是"同一笔记了几次"，不是"读过几次"。
     * 这里保持只读不写。
     */
    override fun onNotificationRemoved(sbn: StatusBarNotification?) = Unit

    /**
     * 把一条通知拼成一段文字。
     *
     * 顺序是刻意的：标题、文本、大字、进度、子文本。银行 App 把金额放在
     * `EXTRA_BIG_TEXT`、微信放在 `EXTRA_TEXT` 里的都有，顺序只影响商户名
     * 猜测的命中率，不影响金额能不能读出来。
     */
    private fun readableText(sbn: StatusBarNotification): String? {
        val n: Notification = sbn.notification ?: return null
        val e = n.extras ?: return null
        val sb = StringBuilder(512)
        fun add(key: String) {
            val v = e.getCharSequence(key)?.toString()?.trim()
            if (!v.isNullOrEmpty()) sb.append(v).append('\n')
        }
        add(Notification.EXTRA_TITLE)
        add(Notification.EXTRA_TEXT)
        add(Notification.EXTRA_BIG_TEXT)
        add(Notification.EXTRA_SUB_TEXT)
        add(Notification.EXTRA_INFO_TEXT)
        add(Notification.EXTRA_SUMMARY_TEXT)

        // 会话样式（MessagingStyle）的消息体。
        //
        // 微信支付的通知是**带会话的**（会话名"微信支付"），正文放在
        // `EXTRA_MESSAGES` 里，而不在 EXTRA_TEXT。不解析这一项的话，
        // 拼出来的可能只有"微信支付"四个字——没有金额，于是被支付门挡掉，
        // 现象和"微信没发通知"一模一样，但原因完全不同。
        //
        // 注意 Kotlin 把 `Bundle.getParcelableArray(String)` 映射成了
        // `getParcelableArray: Array<Parcelable>` **属性**（不是返回 Parcelable[] 的函数），
        // 所以这里用 `?.size` 拿长度、元素要自己 cast 回 Bundle。
        @Suppress("DEPRECATION")
        val messages = e.getParcelableArray(Notification.EXTRA_MESSAGES)
        if (messages != null) {
            for (raw in messages) {
                val m = raw as? android.os.Bundle ?: continue
                m.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim()
                    ?.takeIf { it.isNotEmpty() }?.let { sb.append(it).append('\n') }
                m.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim()
                    ?.takeIf { it.isNotEmpty() }?.let { sb.append(it).append('\n') }
            }
        }
        // 会话名本身也可能是唯一的线索（"微信支付"），补在最后
        e.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()?.trim()
            ?.takeIf { it.isNotEmpty() }?.let { sb.append(it).append('\n') }

        return sb.toString()
    }

    companion object {
        private const val TAG = "KapiNotify"

        @Volatile
        var connected: Boolean = false
            private set
    }
}
