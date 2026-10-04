package kapi.ledger.auto

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kapi.core.auto.AutoBook

/**
 * 自动记账的开关、去重窗口与指纹时间戳。
 *
 * 存储用 SharedPreferences 而不是纯内存：服务进程会被系统杀掉重开，内存去重表
 * 一丢，用户在微信里返回上一页的动作就会又记一笔。这是重复，不是丢失，但仍该
 * 避免——界面上凭空多一行，用户会怀疑是不是自己真买了两次。
 *
 * 内存里保留一份缓存，两层原因：
 *  1. 读改写不是原子的。无障碍事件和通知栏事件可能并发到达，两个线程都读到
 *     旧表、都判定"没见过"，就各写一次——一次支付记两笔。`@Synchronized`
 *     配缓存才能把"判定"和"落笔"收进同一个临界区。
 *  2. 每个无障碍事件都要查一次，SharedPreferences 的反序列化在这个频率下
 *     能明显感觉到。
 *
 * 去重的判据全部在 [AutoBook] 里，那边有单测钉死。这边只负责存取。
 */
class AutoBookStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("kapi_auto", Context.MODE_PRIVATE)

    /** 指纹 → 上次处理时刻。读改写只在锁内发生。 */
    private val lock = Any()

    private val stamps: MutableMap<String, Long> by lazy { loadStamps(KEY_STAMPS) }

    /**
     * 平台交易号 → 上次处理时刻。与 [stamps] 分开存，因为两者的寿命差了两个数量级。
     *
     * 合在一张表里会让清理逻辑没法写：文本指纹 3 分钟就该扔，交易号要留 90 天，
     * 而两者用同一个键空间，谁也不能按各自规则裁剪。
     */
    private val idStamps: MutableMap<String, String> by lazy { loadIdStamps(KEY_ID_STAMPS) }

    init {
        // 2026-10-02 去重语义迁移（v2）：旧版在门检**之前**落戳，被门拒掉的页面
        // 也留了 90 天戳——「加载中」误杀时代的微信转账页，门修好后同一笔永远
        // 录不进来。历史毒戳无法与正常戳区分，一次性清空（代价：迁移当天同一页
        // 重开可能多一条草稿，同源合并层会并掉，可接受）。
        if (!prefs.getBoolean(KEY_DEDUP_V2, false)) {
            prefs.edit()
                .remove(KEY_STAMPS).remove(KEY_ID_STAMPS)
                .putBoolean(KEY_DEDUP_V2, true)
                .apply()
            Log.w(TAG, "去重语义迁移 v2：清空历史去重戳（旧版在门检前落戳会毒化去重表）")
        }
    }

    /**
     * 读屏通路开关。
     *
     * 2026-10-01：原先还有一个平行的 `notificationEnabled`，随通知栏通路整条删除。
     * 读屏这条路曾经被设计成"补充"，界面文案还写着"是通知栏的补充，不是替代"——
     * 那个前提没有了，唯一的通路不该再用补充的口吻描述自己。
     */
    var accessibilityEnabled: Boolean
        get() = prefs.getBoolean(KEY_A11Y, true)
        set(v) = prefs.edit().putBoolean(KEY_A11Y, v).apply()

    /**
     * 高置信度直接入账，不进待确认。
     *
     * 默认**关闭**，而且**规则解析永远不会走到这条路**——见 [AutoBook.route]。
     * `LocalTextParser` 的 0.55 不是概率，是"命中几个金额候选"的粗计数，
     * 拿它和阈值比是概念错误。规则解析的错没法靠阈值消掉，只能让人看过。
     */
    var autoPostHighConfidence: Boolean
        get() = prefs.getBoolean(KEY_AUTO_POST, false)
        set(v) = prefs.edit().putBoolean(KEY_AUTO_POST, v).apply()

    /**
     * 置信度达到这个值才算"高置信"。只对大模型解析生效。
     *
     * 存 Float：SharedPreferences 只有 Boolean/Float/Int/Long/String/StringSet，
     * **没有 Double**。`putDouble` 编译不过。这点精度对一个人工的阈值没有影响。
     */
    var autoPostThreshold: Double
        get() = prefs.getFloat(KEY_THRESHOLD, 0.5f).toDouble()
        set(v) = prefs.edit().putFloat(KEY_THRESHOLD, v.toFloat()).apply()

    /**
     * 识别后弹出悬浮窗确认（不再只进待确认）。
     *
     * 默认开启；悬浮窗还需要「显示在其它应用上层」特殊权限——未授权时
     * [BillOverlayService.show] 静默跳过，草稿照旧走待确认兜底，什么都不丢。
     */
    var overlayConfirmEnabled: Boolean
        get() = prefs.getBoolean(KEY_OVERLAY_CONFIRM, true)
        set(v) = prefs.edit().putBoolean(KEY_OVERLAY_CONFIRM, v).apply()

    /**
     * 弹窗偏好（2026-10-03 咔皮对齐）：**简约 = 自动入账 + 顶部轻提示**；
     * **详细 = 底部 2/3 确认窗**（原形态）。咔皮默认前者——用户被"每进一次
     * 详情页就弹一次大窗"惹恼过（2026-10-02 真机），两种都留着让用户挑。
     *
     * 简约模式同样需要悬浮窗权限（顶部条也是 TYPE_APPLICATION_OVERLAY）；
     * 没授权时自动退回详细确认的行为不成立——直接退回「只建草稿」，
     * 待确认页兜底，什么都不丢。
     */
    var popupStyle: String
        get() = prefs.getString(KEY_POPUP_STYLE, POPUP_DETAILED) ?: POPUP_DETAILED
        set(v) = prefs.edit().putString(KEY_POPUP_STYLE, v).apply()

    var dedupeWindowMs: Long
        get() = AutoBook.effectiveWindowMs(prefs.getLong(KEY_WINDOW, AutoBook.DEFAULT_WINDOW_MS))
        set(v) = prefs.edit().putLong(KEY_WINDOW, AutoBook.effectiveWindowMs(v)).apply()

    /** 目标 App 白名单。为空表示"不自动识别任何 App"——**不能**解释成"全都认"。 */
    var watchedPackages: Set<String>
        get() = prefs.getStringSet(KEY_PACKAGES, DEFAULT_PACKAGES) ?: DEFAULT_PACKAGES
        set(v) = prefs.edit().putStringSet(KEY_PACKAGES, v).apply()

    /**
     * 这个指纹在窗口内是不是已经记过了。**只查询，不落戳。**
     *
     * @return true = 重复，调用方**必须丢弃**。false = 新的一笔，调用方去处理，
     *         处理**真正产出记录之后**再调 [markFingerprintSeen] 落戳。
     *
     * 名字用肯定式（`isDuplicate`）而不是"要不要记"，是因为唯一可能的误用就是
     * 把 true/false 写反，而那种错误在账本里表现为凭空多一笔或凭空少一笔，
     * 两者都不报错。
     */
    fun hasSeenFingerprint(fingerprint: String, now: Long = System.currentTimeMillis()): Boolean {
        if (fingerprint.isEmpty()) return false // 没读到文本时不参与去重
        synchronized(lock) {
            val previous = stamps[fingerprint] ?: 0L
            return AutoBook.isDuplicate(previous, now, dedupeWindowMs)
        }
    }

    /**
     * 真正产出记录（草稿/入账/合并）之后才落戳。
     *
     * ## 为什么检查和落戳必须分开（2026-10-02）
     *
     * 旧版 `isDuplicate` 是检查+落戳原子操作，管线在第 2 步（门检之前）调用——
     * 被门拒掉的页面**也留了 90 天戳**。微信 H5 的「加载中」哨兵误杀期间
     * （见 PaymentGate.SETTLED_FIELD_HINTS），所有微信转账页都被门拒却留了戳，
     * 门修好之后同一笔永远录不进来（-1200 现场实证：修复包上读取，
     * 「交易号…已经记过」）。新语义：只在真正产出记录后落戳，门拒不留痕。
     */
    fun markFingerprintSeen(fingerprint: String, now: Long = System.currentTimeMillis()) {
        if (fingerprint.isEmpty()) return
        synchronized(lock) {
            stamps[fingerprint] = now
            purgeLocked(now)
            persistLocked()
        }
    }

    /**
     * 这个平台交易号在 [AutoBook.HARD_ID_WINDOW_MS] 内是不是已经记过了。
     * **只查询，不落戳**——落戳见 [markTxnIdSeen]。
     *
     * 和 [hasSeenFingerprint] 刻意分开：窗口固定 90 天、不跟用户设置走，判据也更强——
     * 同一个微信交易单号只可能是一笔，返回 true 就**一定**可以直接丢弃，
     * 不必并成草稿让用户看。
     *
     * 单独一张表的另一个理由是持久化：用户在微信里翻账单是常事，昨天看过的那笔今天
     * 又点开第二次是常事，3 分钟窗口早就过了，重复会直接进账本。文本指纹挡不住这个。
     */
    /**
     * 交易号戳的查询结果。命中后调用方必须检查 [txnId] 指向的账**是否还存在**：
     * 用户删了账再进同一详情页，应该当作没见过、重新走采集——
     * 否则"删了账也永远不再触发"（2026-10-03 用户实测反馈）。
     */
    data class SeenTxn(val at: Long, val txnId: String?)

    /** 交易号戳查询。**只查询，不落戳**——落戳见 [markTxnIdSeen]。 */
    fun seenTxnId(txnKey: String): SeenTxn? {
        if (txnKey.isEmpty()) return null
        synchronized(lock) {
            val raw = idStamps[txnKey] ?: return null
            // 值格式 "时刻:txnId"（txnId 可空）；旧格式纯数字，兼容读取。
            val at = raw.substringBefore(':').toLongOrNull() ?: return null
            return SeenTxn(at, raw.substringAfter(':', "").takeIf { it.isNotEmpty() })
        }
    }

    /** 真正产出记录之后落交易号戳（90 天硬去重窗口），并记下它产生的账目 id。 */
    fun markTxnIdSeen(txnKey: String, now: Long, txnId: String?) {
        if (txnKey.isEmpty()) return
        synchronized(lock) {
            idStamps[txnKey] = "$now:" + (txnId ?: "")
            purgeIdsLocked(now)
            persistLocked()
        }
    }

    // -------------------------------------------------------------------
    // 重复弹卡的「页面会话」（2026-10-03 第二版）
    //
    // 锚的是**账单身份**（记录/草稿的 id），不是页面文本——详情页加载
    // 中途读到的文本会从半页变全页，文本锚一变就被误判成"离开"，
    // 横幅刚记完卡又弹出来（第一版真机复现）。放 store 里是因为草稿→
    // 账目的 id 转换发生在悬浮窗服务，两边必须共享同一份会话。
    // -------------------------------------------------------------------

    /** 本次停留已处理过的账单 id（刚记完的 / 已弹过卡的）。 */
    val dupSessionIds = mutableSetOf<String>()

    /** 会话最近一次被同账单事件确认的时刻；0 = 无会话。 */
    var dupSessionAt = 0L

    /** 草稿转成账目（确认/自动入账/再记一次）后，把单号戳和会话锚一起改挂到新 id。 */
    fun retagDraftToTxn(draftId: String, txnId: String) {
        synchronized(lock) {
            for ((k, v) in idStamps) {
                if (v.substringAfter(':', "") == draftId) {
                    idStamps[k] = "${v.substringBefore(':')}:$txnId"
                }
            }
            persistLocked()
        }
        synchronized(dupSessionIds) {
            if (dupSessionIds.remove(draftId)) dupSessionIds.add(txnId)
        }
    }

    /** 用本次停留产出的账单 id 开一个新会话（覆盖旧的）。 */
    fun anchorDupSession(ids: List<String?>, now: Long) {
        synchronized(dupSessionIds) {
            dupSessionIds.clear()
            dupSessionIds.addAll(ids.filterNotNull())
        }
        dupSessionAt = now
    }

    /** 这笔重复是否属于当前会话（同一笔且会话未过期）。命中顺带续期。 */
    fun dupSessionContains(id: String?, now: Long, staleMs: Long): Boolean {
        if (id == null) return false
        synchronized(dupSessionIds) {
            if (id !in dupSessionIds) return false
        }
        if (now - dupSessionAt >= staleMs) return false
        dupSessionAt = now
        return true
    }

    /** 人已离开那张详情页（列表屏等）：会话作废，回来再弹。 */
    fun clearDupSession() {
        synchronized(dupSessionIds) { dupSessionIds.clear() }
        dupSessionAt = 0
    }

    /** 读最近一次处理时刻，0 表示从没处理过。 */
    fun lastSeenAt(fingerprint: String): Long =
        synchronized(lock) { stamps[fingerprint] ?: 0L }

    /**
     * 用户删掉一笔账后，把它留下的去重痕迹一并抹掉：
     * 交易号戳里凡是记着这个 txnId 的条目全部清除。不清的话，
     * 同一详情页再进去会弹「已记录」，用户明明已经把账删了
     * （2026-10-04 OPPO 真机反馈）。
     *
     * 文本指纹戳（3 分钟窗）不含 txnId，等它自己过期即可。
     */
    fun forgetTxn(txnId: String) {
        synchronized(lock) {
            val it = idStamps.entries.iterator()
            var removed = false
            while (it.hasNext()) {
                if (it.next().value.substringAfter(':', "") == txnId) {
                    it.remove()
                    removed = true
                }
            }
            if (removed) persistLocked()
        }
        synchronized(dupSessionIds) { dupSessionIds.remove(txnId) }
    }

    /**
     * 清掉已经不可能再造成重复的指纹。
     *
     * SharedPreferences 的 StringSet 没有写入顺序保证也没有条数上限，指纹是 16 位
     * 十六进制串，攒到几百条时每次序列化都要走一遍。不裁剪的话这个表会无界增长，
     * 而它是被**每次自动记账**碰到的。
     *
     * 裁剪的判据是 `now - window * 2`：留一倍余量，免得用户刚好把窗口调大时，
     * 刚写进去的条目被自己刚执行的清理删掉。
     */
    private fun purgeLocked(now: Long) {
        val cutoff = now - AutoBook.effectiveWindowMs(dedupeWindowMs) * 2
        val it = stamps.entries.iterator()
        var removed = false
        while (it.hasNext()) {
            if (it.next().value < cutoff) {
                it.remove()
                removed = true
            }
        }
        if (removed) Log.i(TAG, "清理过期指纹，剩 ${stamps.size} 条")
    }

    /**
     * 裁剪交易号表。
     *
     * 90 天窗口**不能**再乘 2 去留余量——那会把表撑到半年，而半年前的交易号
     * 用户早就不会回头看了。这里直接按窗口本身裁。
     */
    private fun purgeIdsLocked(now: Long) {
        val cutoff = now - AutoBook.HARD_ID_WINDOW_MS
        val it = idStamps.entries.iterator()
        var removed = false
        while (it.hasNext()) {
            val at = it.next().value.substringBefore(':').toLongOrNull() ?: 0L
            if (at < cutoff) {
                it.remove()
                removed = true
            }
        }
        if (removed) Log.i(TAG, "清理过期交易号，剩 ${idStamps.size} 条")
    }

    /**
     * 落盘。
     *
     * 格式 `"<指纹>|<时刻>"`。指纹是十六进制串不含 `|`，时刻是十进制，所以
     * `substringAfterLast('|')` 一定拿到时刻。
     */
    private fun persistLocked() {
        prefs.edit()
            .putStringSet(KEY_STAMPS, encode(stamps))
            .putStringSet(KEY_ID_STAMPS, idStamps.map { "${it.key}|${it.value}" }.toSet())
            .apply()
    }

    private fun encode(m: Map<String, Long>): Set<String> =
        m.map { "${it.key}|${it.value}" }.toSet()

    private fun loadStamps(key: String): MutableMap<String, Long> {
        val raw = prefs.getStringSet(key, null) ?: return mutableMapOf()
        val out = HashMap<String, Long>(raw.size * 2)
        for (entry in raw) {
            val ts = entry.substringAfterLast('|', "").toLongOrNull() ?: continue
            if (ts <= 0L) continue
            val fp = entry.substringBeforeLast('|')
            if (fp.isEmpty()) continue
            out[fp] = ts
        }
        return out
    }

    /** 交易号戳加载：新格式 "key|ts:txnId"，旧格式 "key|ts"（txnId 视为空）。 */
    private fun loadIdStamps(key: String): MutableMap<String, String> {
        val raw = prefs.getStringSet(key, null) ?: return mutableMapOf()
        val out = HashMap<String, String>(raw.size * 2)
        for (entry in raw) {
            val ts = entry.substringAfterLast('|', "").substringBefore(':').toLongOrNull() ?: continue
            if (ts <= 0L) continue
            val fp = entry.substringBeforeLast('|')
            if (fp.isEmpty()) continue
            out[fp] = "$ts:" + entry.substringAfterLast('|', "").substringAfter(':', "")
        }
        return out
    }

    companion object {
        private const val TAG = "AutoBookStore"
        private const val KEY_A11Y = "a11y_enabled"
        private const val KEY_AUTO_POST = "auto_post_high_conf"
        private const val KEY_THRESHOLD = "auto_post_threshold"
        private const val KEY_WINDOW = "dedupe_window_ms"
        private const val KEY_PACKAGES = "watched_packages"
        private const val KEY_STAMPS = "handled_stamps"
        private const val KEY_ID_STAMPS = "handled_txn_ids"
        private const val KEY_DEDUP_V2 = "dedup_semantics_v2"
        private const val KEY_OVERLAY_CONFIRM = "overlay_confirm_enabled"
        private const val KEY_POPUP_STYLE = "popup_style"

        /** 弹窗偏好取值：底部详细确认窗（原形态）。 */
        const val POPUP_DETAILED = "detailed"

        /** 弹窗偏好取值：顶部简约条 + 自动入账（咔皮默认形态）。 */
        const val POPUP_MINIMAL = "minimal"

        /**
         * 默认监听包名。
         *
         * 全部是"会自己发支付成功通知"或"页面能被无障碍读到"的 App。
         *
         * ## 银行那几行必须以真机实测为准
         *
         * 2026-09-30 在 OPPO PMA110 上 `pm list packages` 实测发现，
         * 原来那批包名**大部分是错的**：
         * ```
         *   交行   实际 com.bankcomm.Bankcomm                  写的 com.chinamobiler.bankcomm ← 那是建行
         *   中行   实际 com.chinamworld.bocmbci                写的 com.chinamobiler.boc      ← 旧版
         *   招行   实际 com.cmbchina.ccd.pluto.cmbActivity     写的 cmb.pb                     ← 旧版
         *   工行   实际 com.icbc                                写的 com.icbc                   ← 对的
         * ```
         * 而银行 App 恰恰是**最容易出问题的一组**：微信、支付宝、各家银行
         * 的付款结果页都是"能读到但形状各异"，写错包名等于这整条通路从来没生效过，
         * 而且**不报任何错**——用户只会觉得"自动记账时灵时不灵"。
         *
         * 2026-09-30 已用 `am start` 逐个实测过：工行、招行、交行、中行、云闪付、
         * 支付宝、微信**全部派发无障碍事件且节点树读得到**。
         * （同一天早些时候我一度测出"这些 App 都不派发事件"，那是用 `monkey` 拉起
         * 造成的假象——它不产生窗口切换事件。教训见 KapiAccessibilityService。）
         *
         * 各家银行改包名的频率比想象中快，所以这一段是**快照不是契约**。
         * 改动前先跑一次：
         * ```
         * adb shell pm list packages | grep -E 'icbc|cmb|bankcomm|chinamworld|cebbank|citic|cib|ping'
         * ```
         * 拿不到真机时宁可少写：少一个包名只是少一条通路，写错同样也是少一条通路，
         * 但写错还会让人以为"银行这条路已经试过了、不行"。
         */
        val DEFAULT_PACKAGES: Set<String> = setOf(
            "com.tencent.mm",                          // 微信
            "com.eg.android.AlipayGphone",             // 支付宝
            "com.unionpay",                            // 云闪付
            "com.alipay.mobile.androidclient",        // 支付宝国际版
            // ---- 银行（2026-09-30 真机实测的包名）----
            "com.icbc",                                // 工商银行
            "com.bankcomm.Bankcomm",                   // 交通银行
            "com.chinamworld.bocmbci",                 // 中国银行
            "com.cmbchina.ccd.pluto.cmbActivity",      // 招商银行
            "com.chinamworld.main",                    // 建设银行
            "com.android.bankabc",                     // 农业银行
            "com.cebbank.mobile",                      // 光大银行
            "com.citic",                               // 中信银行
            "com.cib.mobile",                          // 兴业银行
            "com.pingan",                              // 平安
            // ---- 电商（页面结构稳定，商家名直出）----
            "com.taobao.taobao",                       // 淘宝
            "com.jd.jrapp",                            // 京东
            "com.sankuai.meituan",                     // 美团
            "com.sankuai.meituan.takeoutnew",          // 美团外卖
        )

        /** 用户熟悉的名字，按包名归到一组。设置页按这个分组显示。 */
        val PACKAGE_LABELS: Map<String, String> = mapOf(
            "com.tencent.mm" to "微信",
            "com.eg.android.AlipayGphone" to "支付宝",
            "com.unionpay" to "云闪付",
            "com.alipay.mobile.androidclient" to "支付宝国际版",
            "com.icbc" to "工商银行",
            "com.bankcomm.Bankcomm" to "交通银行",
            "com.chinamworld.bocmbci" to "中国银行",
            "com.cmbchina.ccd.pluto.cmbActivity" to "招商银行",
            "com.chinamworld.main" to "建设银行",
            "com.android.bankabc" to "农业银行",
            "com.cebbank.mobile" to "光大银行",
            "com.citic" to "中信银行",
            "com.cib.mobile" to "兴业银行",
            "com.pingan" to "平安",
            "com.taobao.taobao" to "淘宝",
            "com.jd.jrapp" to "京东",
            "com.sankuai.meituan" to "美团",
            "com.sankuai.meituan.takeoutnew" to "美团外卖",
        )
    }
}
