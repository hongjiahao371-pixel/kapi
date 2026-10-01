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
 * 三次是下限，而且要求**压倒性多数**。
 *
 * 只看次数不够：同一个商户下有 3 笔餐饮、2 笔购物，那说明这个人自己也犹豫，
 * 学出来的结论有一半是错的。要求多数至少 2 倍，是在说"**当用户自己的行为
 * 一致到不需要犹豫时，才当成一条规则**"。
 *
 * 宁可晚学。晚学的代价是用户多手动点几次；早学的代价是他已经改掉的分类
 * 被自动改回去，而他不会知道为什么。
 */
object MerchantLearning {

    /** 学到一条规则至少要这么多次。 */
    const val MIN_OCCURRENCES = 3

    /**
     * 商户名归一化。
     *
     * 只做**保守**的合并：去掉首尾空白和把连续空白压成一个空格。
     * 不做去星号、不截断——`微雪**店` 和 `微雪数码` 是两家店，
     * 而把它们合并成一条会学到"微雪 = 数码"，然后把「微雪食品」也拖进去。
     *
     * 宁可学得少。少学的代价是多手动点几���，学错的代价是把整条商户线拖错档。
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

        // 第二名不为 0 时要求压倒性多数，否则用户自己的行为就是矛盾的。
        val second = sorted.getOrNull(1)?.second ?: 0
        if (second > 0 && top.second < second * 2) return null

        return top
    }
}
