package kapi.core.auto

/** Standalone navigation labels are not evidence of a payment counterparty. */
object MerchantName {
    private val chrome = setOf("服务", "会员", "发现", "首页", "主页", "更多", "账单服务",
        "账单管理", "支付管理", "支付设置", "联系商家", "商家电话", "公众号")
    fun isInterfaceLabel(value: String?): Boolean = value?.trim() in chrome

    /** Only recover a platform explicitly retained in a captured payment note. */
    fun platformFromNote(note: String?): String? = Regex(
        "^(美团|拼多多|淘宝|天猫|京东|闲鱼|饿了么|抖音|盒马|飞猪|携程|12306|小红书)平台消费(?:[·…]|$)"
    ).find(note.orEmpty().trim())?.groupValues?.get(1)
}
