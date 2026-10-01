package kapi.core.auto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 自学习的判定逻辑。
 *
 * 这条链路上最贵的代价不是判错，是**学错**——用户已经改好的分类被自动改回去，
 * 而界面上不会告诉他为什么。所以判定条件刻意保守，宁可晚学。
 */
class MerchantLearningTest {

    private fun decide(vararg hist: Pair<String, Int>) = MerchantLearning.decide(hist.toList())

    // ------------------------------------------------------------ 用户说的那个场景

    /**
     * 用户在同一个商户手动归了五次「餐饮」，第六笔就该自动进餐饮。
     */
    @Test
    fun `同一商户五次同一档就学会`() {
        assertEquals("cat-0" to 5, decide("cat-0" to 5))
    }

    @Test
    fun `刚好三次也学`() {
        assertEquals("cat-0" to 3, decide("cat-0" to 3))
    }

    // ------------------------------------------------------------ 不到三次不学

    @Test
    fun `只有两次不学`() {
        assertNull(decide("cat-0" to 2))
    }

    @Test
    fun `只有一次不学`() {
        assertNull(decide("cat-0" to 1))
    }

    /**
     * **自学习自己产的记录不能反过来喂自己。**
     *
     * 这一条最要紧：自动归类的账也带 `categoryId`，会出现在直方图里。
     * 如果第 1 笔就被自动归了「咖啡茶饮」、用户随后把它改成「餐饮」，
     * 直方图就成了 咖啡茶饮:1 / 餐饮:1 —— 压倒性多数永远不成立，这条规则永远学不会。
     *
     * 解法不在这里，在 [AutoCategory]：学到的分类在**写入前**就已经是这个值了，
     * 用户的修改走的是另一条路径，账本里 `categoryId` 被覆盖而不是新增一行，
     * 所以直方图反映的是"这个商户现在的账都被归到哪"，而不是"曾经猜过什么"。
     * 这里钉住的是：直方图里出现并列时**不学**，宁可等用户再点两下。
     */
    @Test
    fun `两档次数相同时不学`() {
        assertNull(decide("cat-0" to 3, "cat-2" to 3))
    }

    /**
     * 压倒性多数：3:2 不够，得 6:2 或 4:1。
     *
     * 用户自己的行为矛盾时说明他也犹豫，这时候学出来的结论有一半是错的。
     */
    @Test
    fun `不够压倒性时不学`() {
        assertNull(decide("cat-0" to 3, "cat-2" to 2))   // 3 < 2*2
        assertEquals("cat-0" to 4, decide("cat-0" to 4, "cat-2" to 2))  // 4 >= 2*2
        assertEquals("cat-0" to 6, decide("cat-0" to 6, "cat-2" to 2))
    }

    @Test
    fun `压倒性是跟第二名比，不是跟总数比`() {
        // 3:1:1  总数 5，3 占了六成以上，但跟第二名 1:1 比是压倒性的
        assertEquals("cat-0" to 3, decide("cat-0" to 3, "cat-2" to 1, "cat-5" to 1))
    }

    // ------------------------------------------------------------ 脏数据

    @Test
    fun `空直方图和全零都不学`() {
        assertNull(MerchantLearning.decide(emptyList()))
        assertNull(decide("cat-0" to 0))
    }

    /** 次数为 0 的行是脏数据，不能让它排在前面。 */
    @Test
    fun `零次数的行被忽略`() {
        assertEquals("cat-0" to 3, decide("cat-0" to 3, "cat-9" to 0))
    }

    // ------------------------------------------------------------ 商户名归一化

    @Test
    fun `归一化只做保守的空白处理`() {
        assertEquals("星巴克", MerchantLearning.normalize("  星巴克  "))
        assertEquals("星巴克 国贸店", MerchantLearning.normalize("星巴克   国贸店"))
        assertNull(MerchantLearning.normalize("   "))
        assertNull(MerchantLearning.normalize(null))
    }

    /**
     * 不做去星号、不截断。
     *
     * `微雪**店` 和 `微雪数码` 是两家店，合并成一条会学到"微雪 = 数码"，
     * 然后把「微雪食品」也拖进去。宁可少学：少学的代价是多手动点几次，
     * 学错的代价是整条商户线被拖错档。
     */
    @Test
    fun `不同店名不合并`() {
        assertEquals("微雪**店", MerchantLearning.normalize("微雪**店"))
        assertEquals("微雪数码", MerchantLearning.normalize("微雪数码"))
    }
}
