package kapi.ledger.auto

import android.util.Log
import kapi.core.auto.AutoBook
import kapi.core.auto.NoteComposer
import kapi.core.auto.OwnAccount
import kapi.core.auto.PaymentGate
import kapi.core.auto.PaymentId
import kapi.core.llm.LocalTextParser
import kapi.ledger.data.AutoDraftEntity
import kapi.ledger.data.KapiDatabase
import kapi.ledger.data.LedgerRepository
import kapi.ledger.sync.LocalEditWriter
import java.util.UUID

/**
 * 自动记账的**唯一入口**。无障碍只负责把文字递进来，判断全在这里。
 *
 * 2026-10-01 起运行时只有读屏一条通路（通知栏那套连同
 * `KapiNotificationListener` 一起删了），但**去重与合并的机制整条保留**：
 * 同一笔支付被读到第 2..N 次时必须挡掉，而不是记成好几笔——这是"自动"
 * 这两个字最基本的要求，与有几个来源无关。下一个来源接进来时它直接可用。
 *
 * 处理顺序是刻意的，**不能调换**：
 *  1. 白名单/开关 —— 挡在最前面，一次 SharedPreferences 读，别的不用做
 *  2. 指纹去重 —— 挡掉同一笔的第 2..N 次触发。**这是整条管线里唯一的自动丢弃**
 *  3. 支付门 —— 挡掉账单列表页那种"有金额但不是刚发生"的东西
 *  4. 解析 —— 粗排的候选，低置信度是常态
 *  5. 跨来源合并 —— 通到这里还带着**另一条通路的证据**，并成一条草稿
 *  6. 分流 —— 规则解析永远进草稿，只有用户明确开了开关且大模型够自信才入账
 *
 * 3 必须在 2 之后但 4 之前：账单列表页每一屏的文本都不同，挡不住去重，
 * 而它们每屏都能解析出"一笔三天前的消费"。
 *
 * ## 为什么不在这条管线里调大模型
 *
 * 一条通知就发一次请求，会有三个问题：延迟（用户已经走过支付页了）、
 * 花钱（每条通知都是 token）、以及**把别人的支付信息发到云端**。
 * 截图识别那条路已经能走模型，需要的时候用户主动用。
 * 通知栏这条路保持纯本地，代价是准确率，换来的是零延迟零成本零外发。
 */
class AutoCapturePipeline(
    private val db: KapiDatabase,
    private val repository: LedgerRepository,
    private val editor: LocalEditWriter,
    private val autoStore: AutoBookStore,
    private val deviceId: () -> String,
) {

    data class CaptureInput(
        val channel: AutoBook.CaptureChannel,
        val originPackage: String?,
        val rawText: String?,
        /** 事件真实发生的时间。通知用 postTime，读屏用当前时刻。 */
        val occurredAt: Long,
    )

    sealed interface Outcome {
        /** 已入账。[reason] 说明为什么没让用户确认。 */
        data class Posted(val txnId: String, val reason: String) : Outcome

        /** 新建了一条待确认草稿。 */
        data class Drafted(val draftId: String, val reason: String) : Outcome

        /** 并进了已有的草稿——跨来源的同一笔。 */
        data class Merged(val draftId: String, val sources: String) : Outcome

        /** 丢弃。[reason] 写进日志，用来调支付门。 */
        data class Ignored(val reason: String) : Outcome

        /**
         * 记成了一笔**自己口袋之间的转账**，不是消费。
         *
         * [fromAccountId] / [toAccountId] 为 null 表示两端没能对应到账本里的真实账户
         * （余额宝这些子钱包目前还不是 account 表里的行），这时**不入账**，只留日志——
         * 宁可少一条，也不要把它算成支出。
         */
        data class Transfer(
            val txnId: String?,
            val why: String,
        ) : Outcome
    }

    suspend fun capture(input: CaptureInput): Outcome {
        val outcome = runCatching { decide(input) }.getOrElse { t ->
            Log.e(TAG, "管线异常：${t.javaClass.name}: ${t.message}", t)
            Outcome.Ignored("管线异常：${t.javaClass.simpleName}")
        }
        // 每一个被丢弃的输入都要留下痕迹。
        //
        // 之前只有"成功"的三条路径打日志（新建草稿 / 跨来源合并 / 已入账），
        // 于是"什么都没记上"这个现象在日志里和"App 没跑起来"长得一模一样。
        // 2026-09-30 真机验收就卡在这：微信付了一笔，日志里 AutoCapture 一行都没有，
        // 只能靠逐层排除（数据库 mtime、shared_prefs、事件缓冲）才定位到是
        // **我们自己的无障碍服务当时没被系统真正拉起来**（在"已启用"列表里，
        // 但没绑上），而不是微信屏蔽。当时差点把责任归到微信头上——
        // 参见 KapiAccessibilityService 的 lastTraced 注释，那才是这次真正的收获。
        // 丢弃原因是调支付门的唯一输入，没有它就只能猜。
        if (outcome is Outcome.Ignored) {
            Log.i(
                TAG,
                "丢弃[${input.channel}/${input.originPackage}]：${outcome.reason}" +
                    "｜原文前 120 字=${input.rawText?.take(120)?.replace('\n', '|') ?: "(无文字)"}",
            )
        }
        return outcome
    }

    private suspend fun decide(input: CaptureInput): Outcome {
        val now = input.occurredAt
        val channel = input.channel

        // 1. 开关与白名单
        if (!channelOn(channel)) return Outcome.Ignored("用户在设置里关了这一条通路")
        val pkg = input.originPackage
        if (pkg == null) return Outcome.Ignored("读不到来源 App 包名")
        if (pkg !in autoStore.watchedPackages) return Outcome.Ignored("$pkg 不在监听名单里")

        val text = input.rawText ?: return Outcome.Ignored("没有文字")

        // 2. 指纹去重——整条管线唯一的自动丢弃点
        //
        // 账单详情页上带平台交易号时**改用交易号去重**，而且换的是两样东西：
        // 判据（硬 ID 而非文本哈希）和窗口（90 天而非 3 分钟）。理由见
        // [kapi.core.auto.PaymentId] 的类注释——文本指纹把交易号本身抹成了 `#`，
        // 用户隔几天再点开同一笔账单就会多一条重复记录。
        val payId = PaymentId.authoritative(text)
        val fingerprint = payId?.key ?: AutoBook.exactFingerprint(channel, text)
        val duplicate = if (payId != null) {
            autoStore.isDuplicateTxnId(fingerprint, now)
        } else {
            autoStore.isDuplicate(fingerprint, now)
        }
        if (duplicate) {
            return Outcome.Ignored(
                if (payId != null) {
                    "交易号 ${payId.value}（${payId.kind.label}）已经记过，丢弃"
                } else {
                    "指纹 $fingerprint 在去重窗口里，丢弃"
                },
            )
        }

        // 3. 支付门
        (PaymentGate.verdict(text) as? PaymentGate.Verdict.NotPayment)?.let {
            return Outcome.Ignored(it.why)
        }

        // 4. 解析
        val parsed = LocalTextParser.parse(input.rawText, now)
            ?: return Outcome.Ignored("没解析出金额")
        val best = parsed.best ?: return Outcome.Ignored("没有金额候选")

        val ledgerId = repository.defaultLedgerId()
        val direction = parsed.direction.name

        // 4.5 自转账——在合并**之前**分出去
        //
        // 位置是刻意的：放在合并前面，是因为同额同向同窗口的判据分不出
        // 「自己的钱在挪」和「真的花掉了」。账户余额→余额宝 12100 和
        // 别的 12100 支出在那一层长得一模一样，并成一条就是静默丢账。
        //
        // 放在分流前面，是因为它压根不该进"收支"：两端都是自己的钱包，
        // 用户什么都没买（支付宝自己也把这一笔的「计入收支」关着）。
        // 2026-10-01 realme 上它被记成了 `12100 EXPENSE / 商户=None`。
        val own = OwnAccount.judge(text.lines())
        if (own is OwnAccount.Verdict.SelfTransfer) {
            return postSelfTransfer(own, best.amountMinor, parsed.occurredAt, ledgerId, text)
        }

        // 5. 跨来源合并
        //
        // 查的窗口必须用 **parsed.occurredAt**，不能用传进来的 event 时间。
        // 两者可能差很远：通知里写"昨天"，而 [LocalTextParser] 在完全读不出
        // 日期时会归到**今天中午**（guessTime 的兜底）。拿 event 时间去查，
        // 存着"今天中午"的候选就落在窗口外，合并永远不成立——同一笔支付
        // 通知栏记一次、读屏再记一次，账凭空翻倍。
        //
        // 一句话：两边比的都必须是**真正存进草稿的那个时刻**。
        val existing = findMergeTarget(
            amountMinor = best.amountMinor,
            direction = direction,
            at = parsed.occurredAt,
            channel = channel,
            merchant = best.merchant,
            rawText = text,
        )
        if (existing != null) {
            val merged = existing.copy(
                sources = mergeSources(existing, channel),
                evidenceCount = existing.evidenceCount + 1,
                rawText = existing.rawText + RAW_SEPARATOR + text.take(MAX_RAW),
                // 首读可能是半屏（字段还没渲染完），备注合成不出来；后续某次
                // 读到了完整屏就把空备注补上。已有备注绝不被覆盖。
                note = existing.note ?: NoteComposer.compose(text),
            )
            db.autoDraftDao().upsert(merged)
            // 日志要说清是哪种合并。同源合并是"这一屏被读到两次"，
            // 跨源合并是"两条通路都报了同一笔"——排查时看的方向完全不同。
            val kind = if (AutoBook.CaptureChannel.from(existing.source) == channel) {
                "同源近重复"
            } else {
                "跨来源"
            }
            Log.i(TAG, "$kind 合并：并入 ${merged.id}，来源 ${merged.sources}，证据 ${merged.evidenceCount} 条")
            return Outcome.Merged(merged.id, merged.sources)
        }

        val draft = AutoDraftEntity(
            id = UUID.randomUUID().toString(),
            ledgerId = ledgerId,
            amountMinor = best.amountMinor,
            direction = direction,
            occurredAt = parsed.occurredAt,
            merchant = best.merchant,
            note = NoteComposer.compose(text),
            accountId = guessAccountId(pkg),
            source = channel.name,
            confidence = parsed.confidence,
            fingerprint = fingerprint,
            createdAt = System.currentTimeMillis(),
            originPackage = pkg,
            rawText = text.take(MAX_RAW),
            sources = channel.name,
            evidenceCount = 1,
        )

        // 6. 分流
        val routed = AutoBook.route(
            parser = AutoBook.ParserKind.RULE,
            confidence = parsed.confidence,
            autoPostEnabled = autoStore.autoPostHighConfidence,
            threshold = autoStore.autoPostThreshold,
        )
        if (routed is AutoBook.AutoOutcome.Post) {
            val txn = editor.createTransaction(
                amountMinor = draft.amountMinor,
                direction = draft.direction,
                accountId = draft.accountId ?: DEFAULT_ACCOUNT_ID,
                occurredAt = draft.occurredAt,
                ledgerId = ledgerId,
                deviceId = deviceId(),
                merchant = draft.merchant,
                categoryId = AutoCategory.resolve(
                    db = db, ledgerId = ledgerId,
                    merchant = draft.merchant, rawText = text, direction = direction,
                ),
                source = LocalEditWriter.SOURCE_LOCAL_FALLBACK,
                confidence = parsed.confidence,
            )
            Log.i(TAG, "已入账 ${txn.id}：${routed.reason}")
            return Outcome.Posted(txn.id, routed.reason)
        }

        val why = (routed as AutoBook.AutoOutcome.Draft).reason
        val withDupCheck = draft.copy(
            likelyDuplicateOf = findPostedTwin(draft.amountMinor, direction, draft.occurredAt)
        )
        db.autoDraftDao().upsert(withDupCheck)
        Log.i(TAG, "新草稿 ${withDupCheck.id}：${withDupCheck.amountMinor} $direction，$why")
        return Outcome.Drafted(withDupCheck.id, why)
    }

    /**
     * 定期清理。
     *
     * 只清**过窗口**的：窗口内的草稿还在去重窗口里，删掉会让同一笔被记两次。
     * 一次性的清理放在这里由服务按天触发，不要挂在写入路径上——每次捕获都去
     * `DELETE` 一遍是白白的 IO。
     */
    suspend fun purgeExpired(now: Long = System.currentTimeMillis()) {
        val before = now - AutoBook.effectiveWindowMs(autoStore.dedupeWindowMs)
        val removed = db.autoDraftDao().count()
        db.autoDraftDao().purgeOlderThan(before)
        val left = db.autoDraftDao().count()
        if (left != removed) Log.i(TAG, "清理过期草稿 $removed → $left")
    }

    /**
     * 记一笔**自己口袋之间的挪动**。
     *
     * ## 为什么对不上账户就不入账
     *
     * 账本里的 `account` 表只有 4 个种子（现金 / 银行卡 / 支付宝 / 微信），
     * `余额宝`、`账户余额`、`花呗` 这些**子钱包还不是行**。所以
     * `账户余额 → 余额宝` 两端都会落到「支付宝」身上，记出来是
     * `支付宝 → 支付宝`，余额一动不动，只是把一条消费换成了一条空转账。
     *
     * 这时候再退一步、只写日志，是因为用户要的就是"不记收支"：
     * 写一条 `categoryId = null` 的支出照样会出现在收支里，而
     * `toAccountId` 填不出真实账户时它也不是一笔真正的转账。
     * **宁可账本里没有这一条，也不要有一条方向错、分类空的记录。**
     *
     * 子钱包进 `account` 表是另一件事（要做账户管理），在那之前
     * 这一类只在日志里留痕。
     *
     * ## 为什么要把原文带进日志
     *
     * 2026-10-02 真机首战命中「信用卡还款」判据，但 `from`/`to` 都是 null——
     * 屏上明明有 `付款方式 / 余额宝`。没有原文就没法判断是「值在标签前」的
     * 取值方向在这类页上不成立，还是相邻重复节点把上一行占了（[OwnAccount.judge]
     * 的 KDoc 要求先折叠重复行，这里传的是原始行）。而「原始行序」那行日志
     * 只在形状闸**挡下**时才打，自转账是放行路径，永远轮不到它。
     * 带上原文，下次真机一出现就能定位，不用再猜。
     */
    private suspend fun postSelfTransfer(
        own: OwnAccount.Verdict.SelfTransfer,
        amountMinor: Long,
        at: Long,
        ledgerId: String,
        rawText: String?,
    ): Outcome {
        val fromId = own.from?.let { accountIdOfWallet(it) }
        val toId = own.to?.let { accountIdOfWallet(it) }
        val why = "自转账：${own.why}"
        if (fromId == null || toId == null || fromId == toId) {
            Log.i(
                TAG,
                "$why，两端对应不到账本里的不同账户（from=${own.from}→「$fromId」, " +
                    "to=${own.to}→「$toId」），只记日志不入账 $amountMinor" +
                    "｜原文前 120 字=${rawText?.take(120)?.replace('\n', '|') ?: "(无文字)"}",
            )
            return Outcome.Transfer(null, why + "，未入账（子钱包还不是账户）")
        }
        val txn = editor.createTransaction(
            amountMinor = amountMinor,
            // 转账的 direction 只影响它在账本里怎么显示，余额是由
            // accountId → toAccountId 表达的。记 EXPENSE 是因为
            // 「钱从 A 出去了」；收支统计会把 toAccountId 非空的行排除。
            direction = "EXPENSE",
            accountId = fromId,
            toAccountId = toId,
            occurredAt = at,
            ledgerId = ledgerId,
            deviceId = deviceId(),
            merchant = own.to,
            categoryId = null,
            source = LocalEditWriter.SOURCE_LOCAL_FALLBACK,
            confidence = 1.0,
        )
        Log.i(TAG, "自转账已入账 ${txn.id}：$fromId → $toId，$amountMinor")
        return Outcome.Transfer(txn.id, why)
    }

    /** 支付宝自己的子钱包 → 账本里的支付宝账户。 */
    private val ALIPAY_WALLETS = listOf("余额宝", "账户余额", "花呗")

    /** 微信自己的子钱包 → 账本里的微信账户。 */
    private val WECHAT_WALLETS = listOf("零钱", "零钱通", "存钱罐")

    /**
     * 一个钱包名对应到账本里的哪个账户。
     *
     * 只能对到**平台**层面：余额宝和账户余额都属于支付宝，零钱属于微信。
     * 对不上就返回 null，让调用方走"不入账"那条路。
     */
    private fun accountIdOfWallet(name: String): String? {
        val n = name.trim()
        return when {
            ALIPAY_WALLETS.any { n.contains(it) } -> "A3" // 支付宝
            WECHAT_WALLETS.any { n.contains(it) } -> "A4" // 微信
            else -> null
        }
    }

    // ------------------------------------------------------------ 内部

    /**
     * 这个来源的开关是不是开着。
     *
     * `NOTIFY` 在 2026-10-01 随通知栏服务一起删掉了，运行时不会再有它进来。
     * 这里**显式**返回 false 而不是把分支删掉，是为了让"非读屏来源落到不跑"
     * 成为一个有意识的决定：将来加第二个来源时，这一行就是它的开关该在的位置，
     * 而不必重新把整条 `when` 找回来。
     */
    private fun channelOn(channel: AutoBook.CaptureChannel): Boolean =
        when (channel) {
            AutoBook.CaptureChannel.A11Y -> autoStore.accessibilityEnabled
            AutoBook.CaptureChannel.NOTIFY -> false
        }

    /**
     * 找可以合并进同一条草稿的既有记录。
     *
     * 两段判据，从弱到强依次尝试：
     *
     * 1. 跨源（[AutoBook.isSamePaymentEvent]）——另一条通路也报了同一笔。
     *    2026-10-01 通知栏通路整条删掉后，运行时只剩读屏一条，
     *    所以这一支现在恒不成立；留着是因为它定义的是"合并"与"丢弃"的分界。
     * 2. 同源近重复（[AutoBook.isSameChannelNearMiss] **且** [AutoBook.sameTxnIdentity]）——
     *    同一屏被读到两次的变体。金额同向同窗口只是**必要条件**，
     *    还必须有交易号或商户名兜底，否则两笔撞了金额的独立交易会被并成一条。
     *
     * 第 2 条为什么必须加身份判据，见 [AutoBook.sameTxnIdentity] 的注释。
     */
    private suspend fun findMergeTarget(
        amountMinor: Long,
        direction: String,
        at: Long,
        channel: AutoBook.CaptureChannel,
        merchant: String?,
        rawText: String?,
    ): AutoDraftEntity? {
        val candidates = db.autoDraftDao().nearMisses(
            amountMinor, direction,
            at - AutoBook.CROSS_SOURCE_WINDOW_MS,
            at + AutoBook.CROSS_SOURCE_WINDOW_MS,
        )
        // 先找跨源的（另一条通路的证据），再找同源的近重复。
        // 顺序不能反：跨源合并保留了两条通路的证据，可信度更高，
        // 而且它已经是设计好的行为。
        return candidates.firstOrNull { prev ->
            AutoBook.isSamePaymentEvent(
                prev.amountMinor, prev.direction, prev.occurredAt,
                AutoBook.CaptureChannel.from(prev.source),
                amountMinor, direction, at,
                channel,
            )
        } ?: candidates.firstOrNull { prev ->
            AutoBook.isSameChannelNearMiss(
                prev.amountMinor, prev.direction, prev.occurredAt,
                amountMinor, direction, at,
            ) && AutoBook.sameTxnIdentity(
                prev.merchant, prev.rawText, merchant, rawText,
            )
        }
    }

    private fun mergeSources(existing: AutoDraftEntity, channel: AutoBook.CaptureChannel): String {
        val parts = existing.sources.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toMutableSet()
        parts += channel.name
        return parts.sorted().joinToString(",")
    }

    /**
     * 账本里有没有一笔很像的。
     *
     * 只在这一条上做，而且是**标注**不是丢弃：大模型高置信度先入账了，另一条
     * 通路随后把同一笔收成草稿，用户照点"记一笔"就会重复。标出来，用户自己决定。
     *
     * 忽略 direction 之外的一切字段：同一分钟里同额的另一笔**也**是候选，硬要更准
     * 就得比较商户名，而商户名恰好是最不可靠的那个字段——猜错一次就少标一次，
     * 标错一次就误报一次，两头都不划算。
     */
    private suspend fun findPostedTwin(amountMinor: Long, direction: String, at: Long): String? {
        val span = AutoBook.CROSS_SOURCE_WINDOW_MS
        return db.txnDao()
            .sameAmountNear(amountMinor, direction, at - span, at + span + 1)
            .firstOrNull()
            ?.id
    }

    /**
     * 按来源 App 猜账户。
     *
     * 猜错的可能性不小，所以只是给确认页一个**预选值**。账户错了比金额错了更难
     * 发现——金额看着就不对，账户要到对账那天才暴露。
     */
    private fun guessAccountId(pkg: String): String = when (pkg) {
        "com.tencent.mm" -> "A4"                    // 微信钱包
        "com.eg.android.AlipayGphone",
        "com.alipay.mobile.androidclient",
        "com.unionpay" -> "A3"                      // 支付宝 / 云闪付
        else -> "A2"                                // 其余多半是银行 App
    }

    /**
     * 旧实现留档（已无调用点）。
     *
     * 原来是拿商户名去**精确匹配**分类名：`db.categoryDao().byName(ledgerId, direction, 商户名)`。
     * 实际上永远匹配不上——商户叫「星巴克」，没有哪个分类叫「星巴克」。所以它等于
     * 没有自动分类，每一笔进账都是「未分类」。
     *
     * 真正的实现在 [AutoCategory]，规则表在 `core` 的 `CategoryRules`（那里才有单测钉着）。
     * 直接删掉是因为留着一个语义完全相反的同名函数，下一个人会以为它还在生效。
     */
    private suspend fun guessCategoryIdExactNameOnly(
        ledgerId: String, merchant: String?, direction: String,
    ): String? {
        val name = merchant?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return db.categoryDao().byName(ledgerId, direction, name)?.id
    }

    companion object {
        private const val TAG = "AutoCapture"
        private const val DEFAULT_ACCOUNT_ID = "A1"
        private const val MAX_RAW = 2000
        private const val RAW_SEPARATOR = "\n———\n"
    }
}
