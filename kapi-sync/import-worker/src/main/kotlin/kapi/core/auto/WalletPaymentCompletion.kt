package kapi.core.auto

/** Complete outgoing result templates shared by native nodes, OCR and durable capture.
 * No title/amount-only fallback: counterparty and completion controls remain mandatory. */
object WalletPaymentCompletion {
    enum class Kind { MERCHANT_OR_SCAN, TRANSFER, REDPACKET }
    data class Receipt(val kind:Kind,val canonicalText:String)
    fun isWechatPacketHeading(value:String):Boolean =
        value in setOf("微信红包","普通红包","拼手气红包") || value.startsWith("微信红包-发给")

    fun read(pkg:String?,text:String,allowOcrRepair:Boolean=false):Receipt? {
        // OCR sometimes renders the yuan glyph as Y. Repair only an entire money
        // row; the same complete native-template checks still have to pass.
        val prepared=if(allowOcrRepair) text.lines().joinToString("\n") {line->
            Regex("^[YyＹ]\\s*(\\d{1,8}\\.\\d{2})$").matchEntire(line.trim())
                ?.let {"￥"+it.groupValues[1]} ?: line
        } else text
        if(pkg==WechatPaymentResult.PACKAGE) {
            val clean=prepared.lines().joinToString("\n") { if(allowOcrRepair)WechatCompletionFields.normalizeOcr(it) else WechatCompletionFields.normalize(it) }
            fun parsed(value:String):Receipt? {
                WechatPaymentResult.read(value)?.let {return Receipt(Kind.TRANSFER,it.canonicalText())}
                WechatMerchantPaymentResult.read(value)?.let {
                    val packet=isWechatPacketHeading(it.merchant)
                    return Receipt(if(packet)Kind.REDPACKET else Kind.MERCHANT_OR_SCAN,it.canonicalText())
                }
                return null
            }
            parsed(clean)?.let {return it}
            if(allowOcrRepair) {
                val fields=clean.lines().map(String::trim).filter(String::isNotBlank).distinct().filterNot(WechatCompletionFields::isOcrOffer)
                if(fields.size==4 && fields.first()=="支付成功" && fields.last()=="完成") {
                    val amount=fields.singleOrNull {Regex("^[￥¥]?\\d{1,8}\\.\\d{2}$").matches(it)} ?: return null
                    return parsed(fields.joinToString("\n"){if(it==amount)"￥"+it.removePrefix("￥").removePrefix("¥") else it})
                }
            }
        } else if(pkg==AlipayPaymentResult.PACKAGE) {
            val canonical=AlipayPaymentResult.canonicalText(prepared) ?: return null
            val merchant=kapi.core.llm.LocalTextParser.parse(canonical)?.merchant
            val kind=when {
                merchant in setOf("支付宝红包","发普通红包","普通红包","拼手气红包")->Kind.REDPACKET
                canonical.lines().any {it.startsWith("转账成功")}->Kind.TRANSFER
                else->Kind.MERCHANT_OR_SCAN
            }
            return Receipt(kind,canonical)
        }
        return null
    }
}
