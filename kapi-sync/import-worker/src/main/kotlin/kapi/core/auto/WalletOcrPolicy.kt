package kapi.core.auto

import kapi.core.llm.LocalTextParser
import kapi.core.llm.ScreenshotBillParser

/** Screenshot fallback needs independent page evidence; a wallet package alone is insufficient. */
object WalletOcrPolicy {
    // Internal profile, never an Activity guessed from package name. The service
    // supplies it only for an active, empty, invisible native accessibility root.
    const val OPAQUE_NATIVE_RECOVERY = "guizhang:opaque-wechat-native"
    const val OPAQUE_ALIPAY_RECOVERY = "guizhang:opaque-alipay-native"
    fun recoveredActivity(pkg: String, activity: String, opaqueRoot: Boolean): String =
        if (pkg == WechatPaymentResult.PACKAGE && opaqueRoot &&
            (activity.isBlank() || activity.substringAfterLast('.') == "LauncherUI"))
            OPAQUE_NATIVE_RECOVERY
        else if(pkg==AlipayPaymentResult.PACKAGE && opaqueRoot && activity.isBlank())
            OPAQUE_ALIPAY_RECOVERY else activity
    fun sameNativePage(previous: String?, pkg: String, visit: String?): Boolean = visit != null &&
        previous?.startsWith("$pkg/") == true && previous.endsWith("/native/$visit")
    val packages = setOf(WechatPaymentResult.PACKAGE, AlipayPaymentResult.PACKAGE)
    private val titles = setOf("账单详情", "交易详情", "转账详情", "退款详情", "红包详情", "收款详情", "全部账单")
    private val excluded = listOf("ImageGallery", "ImagePreview", "Chatting", "LauncherUI", "SelectConversation", "Photo", "Album")
    // The real 8.0.78 merchant receipt also runs in this new native container with
    // an empty accessibility root. It may get a bounded pixel preflight; only the
    // complete receipt structure may pass accepted(), never the container by itself.
    private val nativeWechat = setOf("WalletPayUI", "WalletPayResultUI", "WalletOrderInfoUI", "WalletOrderInfoNewUI", "WalletTransferUI", "RemittanceDetailUI", "RemittanceDetailUIFragment", "RemittanceResultNewUI", "LuckyMoneyDetailUI", "LuckyMoneyBusiDetailUI", "LuckyMoneyReceiveUI")
    private val fields = listOf("当前状态", "交易状态", "支付时间", "付款时间", "转账时间", "创建时间", "收款时间", "领取时间", "退款时间", "退款成功时间", "支付方式", "付款方式", "收款方式", "退款方式", "交易单号", "转账单号", "交易号", "收款方", "付款方", "对方账户", "转账备注", "红包说明")
    private val nonPayment = listOf("按住说话", "更多功能按钮", "选择聊天", "选择联系人", "添加到通讯录", "搜索聊天记录", "发送给朋友", "账单列表", "筛选", "月支出", "总资产", "可用余额")

    // Some WeChat builds expose no receipt nodes in this shared container.
    // Permit a bounded local pixel preflight, then require a complete identified
    // detail in acceptedForWindow; this never authorizes a prompt on its own.
    private fun opaqueWechatWebView(pkg: String, activity: String, text: String): Boolean =
        pkg == WechatPaymentResult.PACKAGE &&
            activity == "com.tencent.mm.plugin.webview.ui.tools.MMWebViewUI" && text.isBlank()
    private fun opaqueAlipayWebView(pkg:String,activity:String,text:String):Boolean =
        pkg==AlipayPaymentResult.PACKAGE && text.isBlank() && activity in setOf(
            "com.alipay.mobile.nebulax.xriver.activity.XRiverActivity",
            "com.alipay.mobile.nebulacore.ui.H5Activity",
            "com.alipay.mobile.nebulacore.ui.H5TransActivity")

    fun candidate(pkg: String, activity: String, text: String, preferScreenshot: Boolean = false): Boolean {
        if (pkg !in packages || excluded.any { activity.contains(it) } || nonPayment.any(text::contains)) return false
        if (!preferScreenshot && readable(pkg, text)) return false
        val lines = text.lines().map(String::trim).filter(String::isNotBlank)
        val peer=pkg==WechatPaymentResult.PACKAGE && PeerPaymentReceipt.read(text)!=null
        val structural = peer || lines.take(8).any { it in titles } || fields.count(text::contains) >= 2 ||
            (lines.take(8).any { it in setOf("支付成功", "付款成功", "支付完成", "转账成功") } && lines.any { it == "完成" })
        val nativeContainer = activity.substringAfterLast('.')
        return structural || opaqueWechatWebView(pkg, activity, text) || opaqueAlipayWebView(pkg,activity,text) ||
            (pkg == WechatPaymentResult.PACKAGE && activity == OPAQUE_NATIVE_RECOVERY && text.isBlank()) ||
            (pkg == AlipayPaymentResult.PACKAGE && activity == OPAQUE_ALIPAY_RECOVERY && text.isBlank()) ||
            (pkg == WechatPaymentResult.PACKAGE && (nativeContainer in nativeWechat ||
            (nativeContainer == "UIPageFragmentActivity" && lines.isEmpty()))) ||
            (pkg == AlipayPaymentResult.PACKAGE && activity == "com.alipay.android.msp.ui.views.MspContainerActivity" && lines.isEmpty())
    }

    fun readable(pkg: String, text: String): Boolean {
        if(WalletPaymentCompletion.read(pkg,text)!=null)return true
        if(pkg==WechatPaymentResult.PACKAGE && PeerPaymentReceipt.read(text)!=null)return true
        val shape = PaymentShape.inspect(text.lines().map { PaymentShape.Node(it) }) as? PaymentShape.Verdict.OneTransaction ?: return false
        if (PaymentGate.screenVerdict(shape.text, pkg) !is PaymentGate.Verdict.LooksLikePayment) return false
        val parsed = LocalTextParser.parse(shape.text) ?: return false
        // The order's pre-discount amount is still present in raw fields. A single
        // signed amount above the success status anchors the actual paid value.
        val headerAmount = shape.text.lines().takeWhile {
            !PaymentGate.isSettledStatusLine(it)
        }.map(String::trim).filter { Regex("^[+-]\\s*\\d+(?:,\\d{3})*\\.\\d{2}$").matches(it) }
            .mapNotNull { kapi.core.llm.Money.parse(it.removePrefix("+").removePrefix("-"))?.minor }
        val anchored = headerAmount.size == 1 && headerAmount.single() == parsed.best?.amountMinor &&
            fields.count(shape.text::contains) >= 3 && shape.text.lines().any { it.trim() in titles }
        return (parsed.amountsMinor.size == 1 || anchored) && !parsed.merchant.isNullOrBlank()
    }

    /** Complete bill evidence can be reviewed even when its counterparty is unreadable.
     * A price, short success caption or quoted chat is never sufficient. */
    fun reviewableDetail(pkg: String, text: String): Boolean {
        if (pkg !in packages || nonPayment.any(text::contains) || PaymentId.authoritative(text) == null) return false
        val shape = PaymentShape.inspect(text.lines().map { PaymentShape.Node(it) }) as? PaymentShape.Verdict.OneTransaction ?: return false
        if (PaymentGate.screenVerdict(shape.text,pkg) !is PaymentGate.Verdict.LooksLikePayment) return false
        val parsed = LocalTextParser.parse(shape.text) ?: return false
        val time = TransactionTime.resolve(text,0,parsed.direction.name)
        // A sender's debit already exists while the other party has not collected it.
        // PeerPaymentReceipt requires the signed direction, transfer fields, date and
        // an explicit outgoing status; a pending incoming collection is still rejected.
        val financialEvent = PaymentGate.hasStrongVerb(text) || PeerPaymentReceipt.read(text) != null
        return parsed.amountsMinor.size == 1 && financialEvent &&
            fields.count(text::contains) >= 3 && !time.requiresReview &&
            (time.paidAt != null || time.refundAt != null)
    }

    /** OCR is stricter than notification text. Never promote a price or a success caption alone. */
    fun accepted(pkg: String, text: String): String? {
        if (pkg !in packages || text.length > 8_000 || nonPayment.any(text::contains)) return null
        val prepared = text.lines().joinToString("\n") { line ->
            when {
                line.trim().startsWith("收款方金称 ") -> line.replaceFirst("收款方金称", "收款方全称")
                line.trim() == "交易详惰" -> "交易详情"
                else -> line
            }
        }
        val clean = ScreenshotBillParser.normalize(prepared).let { value ->
            if (Regex("上海哈[\\p{IsHan}]普惠科技有限公司").containsMatchIn(value)) value.replace("助カ车", "助力车") else value
        }
        val lines = clean.lines().map(String::trim).filter(String::isNotBlank)
        val native = if (pkg == WechatPaymentResult.PACKAGE)
            PeerPaymentReceipt.read(clean)?.text ?: WalletPaymentCompletion.read(pkg,clean,allowOcrRepair=true)?.canonicalText
        else WalletPaymentCompletion.read(pkg,clean,allowOcrRepair=true)?.canonicalText ?: alipayOcrReceipt(lines)
        // OCR row grouping joins Alipay's left title and right navigation label.
        // Accept only this exact header, retaining the complete field and payment gates.
        val header = lines.firstOrNull { it !in setOf("返回", "关闭") }
        val detail = (header in titles || header?.matches(Regex("账单详情\\s+全部账单")) == true ||
            PaymentId.authoritative(clean) != null) && fields.count(clean::contains) >= 3
        if (native == null && !detail) return null
        // Share the screenshot heading parser rather than prefer a legal company over the
        // visible store. All source fields, including IDs and timestamps, remain intact.
        val merchant = if (native == null) ScreenshotBillParser.parse(clean)?.merchant else null
        val normalized = native ?: if (!merchant.isNullOrBlank())
            lines.take(1).joinToString("\n") + "\n商户全称：$merchant\n" + lines.drop(1).joinToString("\n") else clean
        return normalized.takeIf { readable(pkg, it) }
    }

    fun acceptedForWindow(pkg: String, activity: String, nodeText: String, ocrText: String, opaqueNativeRoot:Boolean=false): String? {
        if(excluded.any {activity.contains(it)})return null
        val receipt = accepted(pkg, ocrText) ?: return null
        val completion=WalletPaymentCompletion.read(pkg,receipt)
        val recovery=activity in setOf(OPAQUE_NATIVE_RECOVERY,OPAQUE_ALIPAY_RECOVERY)
        val web=opaqueWechatWebView(pkg,activity,nodeText) || opaqueAlipayWebView(pkg,activity,nodeText)
        if(recovery) {
            val expected=if(activity==OPAQUE_NATIVE_RECOVERY)WechatPaymentResult.PACKAGE else AlipayPaymentResult.PACKAGE
            if(pkg!=expected || nodeText.isNotBlank() || (!reviewableDetail(pkg,receipt) && completion==null))return null
        }
        // A shared web container alone cannot legitimize a quoted success image.
        // Short results also need the service's verified empty native-root profile.
        if(web && !reviewableDetail(pkg,receipt) && !(opaqueNativeRoot && completion!=null))return null
        return receipt
    }

    /** One sharper local pass only for a plausible detail whose first read is incomplete.
     * Ordinary pages and explicitly rejected peer states must not double the OCR cost. */
    fun needsSharperRead(text:String):Boolean {
        val clean=ScreenshotBillParser.normalize(text)
        if(nonPayment.any(clean::contains) || PeerPaymentReceipt.rejectionReason(clean)!=null || !PaymentGate.hasAmount(clean))return false
        val lines=ScreenFields.collapse(clean.lines())
        val detail=lines.take(8).any {it in titles} || PaymentId.authoritative(clean)!=null
        if(!detail || fields.count(clean::contains)<2)return false
        return packages.none {pkg->accepted(pkg,clean)!=null} ||
            ScreenshotBillParser.needsRefinement(LocalTextParser.parse(clean,0),clean)
    }

    /** Never replace an existing readable amount/direction or conflicting payment identity. */
    fun clearerRead(first:String,sharper:String):String {
        val a=ScreenshotBillParser.normalize(first);val b=ScreenshotBillParser.normalize(sharper)
        if(packages.none {pkg->accepted(pkg,b)!=null})return first
        val low=LocalTextParser.parse(a,0);val high=LocalTextParser.parse(b,0) ?: return first
        if(ScreenshotBillParser.needsRefinement(high,b))return first
        if(low?.best!=null && (low.best!!.amountMinor!=high.best?.amountMinor || low.direction!=high.direction))return first
        if(listOfNotNull(PaymentId.importIdentity(a),PaymentId.importIdentity(b)).distinct().size>1)return first
        return if(ScreenshotBillParser.quality(high,b)>=ScreenshotBillParser.quality(low,a)) sharper else first
    }

    /** Alipay places reward, insurance and loan amounts below its five-field header.
     * Only a top native header plus the real bottom completion control can isolate it. */
    private fun alipayOcrReceipt(lines: List<String>): String? {
        if (lines.firstOrNull() !in setOf("回首页", "返回商家") || lines.lastOrNull() != "完成") return null
        val status = Regex("^[O〇◯○0]?\\s*(支付成功|付款成功|转账成功|交易成功)$")
        if (lines.take(3).none { status.matches(it) }) return null
        val method = lines.take(9).indexOfFirst { it.startsWith("付款方式") || it.startsWith("支付方式") }
        if (method < 0) return null
        val separate = lines[method].trim() in setOf("付款方式", "支付方式")
        val end = method + if (separate) 2 else 1
        if (end > lines.size) return null
        val header = lines.take(end).map { status.matchEntire(it)?.groupValues?.get(1) ?: it }
        return AlipayPaymentResult.canonicalText((header + "完成").joinToString("\n"))
    }
}

/** Bounded per-window/page attempts plus a global rate cap; no text or image is retained. */
class WalletOcrBudget {
    private val attempts = linkedMapOf<String, Int>()
    private val recent = ArrayDeque<Long>()
    private var last = Long.MIN_VALUE
    private fun prune(now:Long) {
        while (recent.isNotEmpty() && now - recent.first() >= 60_000) recent.removeFirst()
    }
    /** Null means this page is finished. Otherwise wait for a slot without polling pixels. */
    fun retryAfter(key:String, now:Long):Long? {
        prune(now)
        if ((attempts[key] ?: 0) >= 2) return null
        val interval=if(last==Long.MIN_VALUE) 0 else (1_000-(now-last)).coerceAtLeast(0)
        val quota=if(recent.size>=6) (60_000-(now-recent.first())).coerceAtLeast(0) else 0
        return maxOf(interval,quota)
    }
    /** A failed capture or cancelled preflight did not execute OCR. Keep its per-page
     * attempt and spacing limits, but don't starve later receipts of the OCR quota. */
    fun releaseUnused(reservedAt:Long) { recent.remove(reservedAt) }
    fun acquire(key: String, now: Long): Boolean {
        if (retryAfter(key,now) != 0L) return false
        attempts[key] = (attempts[key] ?: 0) + 1
        while (attempts.size > 24) attempts.remove(attempts.keys.first())
        recent.addLast(now); last = now
        return true
    }
    fun stop(key: String) { attempts[key] = 2 }
}
