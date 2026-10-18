package kapi.core.auto

/**
 * 从用户的历史行为里学「这个商户属于哪一档」。
 *
 * ## 为什么学的是账本本身，不另建一张表
 *
 * 用户手动把某一笔归到「餐饮」——那笔账**已经在账本里**了，它的 `merchant` 和
 * `categoryId` 就是一条现成的训练样本。同一商户归了三次，账本里就有三行。
 *
 * 另建一张 `merchant_category` 表要写两处：归类时记一笔、账目被改/删时还得同步撤销。
 * 而它承载的信息**本来就完整地存在于 txn 表里**——两处一抄就会不一致，
 * 而不一致的表现是"它记得我喜欢把星巴克归餐饮，可我已经改过了，它还在按老的来"。
 *
 * 代价是每次分类多一条聚合查询。账本是本机数据、条数是几千级，SQLite 上
 * 一条 `GROUP BY` 是毫秒级——比维护一张会漂移的影子表便宜得多。
 *
 * ## 什么时候才敢学
 *
 * 同商户至少三次人工分类，并且首选分类占有效样本的 80% 以上。
 * 综合支付平台不按商户学习，明确的用户规则仍然可以覆盖。
 *
 * 宁可晚学。晚学的代价是用户多手动点几次；早学的代价是他已经改掉的分类
 * 被自动改回去，而他不会知道为什么。
 */
object MerchantLearning {

    /** 学到一条规则至少要这么多次。 */
    const val MIN_OCCURRENCES = 3

    data class Sample(val merchant: String, val categoryId: String, val n: Int)

    /** 两个入口共用同一组归一化、样本合并和商户过滤规则。 */
    fun histograms(samples: List<Sample>): Map<String, List<Pair<String, Int>>> =
        samples.filter { it.n > 0 }.mapNotNull { s -> key(s.merchant)?.let { it to s } }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, rows) ->
                rows.groupBy { it.categoryId }.map { (id, group) -> id to group.sumOf { it.n } }
            }

    /** 综合平台提供多种业务，不能把在京东买三次衣服学成「京东 = 服饰」。 */
    fun key(merchant: String?): String? {
        val m = normalize(merchant) ?: return null
        if (m in listOf("加载中", "正在加载", "加载失败", "未知商户", "没读到商户", "（没读到商户）",
                "退款", "收款", "付款", "支付成功", "交易成功") ||
            m.startsWith("转账") || m.startsWith("等待对方")) return null
        if (m.lowercase() in MULTI_PURPOSE_MERCHANTS) return null
        return m
    }

    private val MULTI_PURPOSE_MERCHANTS = setOf(
        "淘宝", "天猫", "京东", "拼多多", "美团", "饿了么", "小红书", "闲鱼",
        "支付宝", "微信支付", "腾讯", "apple", "apple.com/bill",
        "山姆", "山姆会员商店", "沃尔玛", "永辉", "盒马", "大润发", "华润万家",
        "苏果", "全家", "罗森", "美宜佳", "7-11", "711",
        "杭州淘宝通信技术有限公司", "深圳市腾讯计算机系统有限公司", "财付通支付科技有限公司",
        // Old OCR/node-order failures are page labels, not a stable merchant identity.
        "主页", "首页", "主页 交易详倩", "服务", "会员", "账单详情", "交易详情", "全部账单",
    )

    /**
     * 商户名归一化。
     *
     * 只做**保守**的合并：去掉首尾空白和把连续空白压成一个空格。
     * 不做去星号、不截断——`微雪**店` 和 `微雪数码` 是两家店，
     * 而把它们合并成一条会学到"微雪 = 数码"，然后把「微雪食品」也拖进去。
     *
     * 宁可学得少。少学的代价是多手动点几次，学错的代价是把整条商户线拖错档。
     */
    fun normalize(merchant: String?): String? {
        val m = merchant?.trim()?.replace(WHITESPACE_RUN, " ") ?: return null
        return m.ifEmpty { null }
    }

    private val WHITESPACE_RUN = Regex("\\s+")

    /**
     * 从「商户 → 各分类次数」的直方图里得出结论。
     *
     * @return 胜出的 (categoryId, 次数)；还不该学的时候返回 null。
     */
    fun decide(histogram: List<Pair<String, Int>>): Pair<String, Int>? {
        val sorted = histogram
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
        val top = sorted.firstOrNull() ?: return null
        if (top.second < MIN_OCCURRENCES) return null

        // 必须占全部有效样本至少 80%，3:1:1 只有 60%，不能当稳定习惯。
        val total = sorted.sumOf { it.second.toLong() }
        if (top.second.toLong() * 5 < total * 4) return null

        return top
    }
}
