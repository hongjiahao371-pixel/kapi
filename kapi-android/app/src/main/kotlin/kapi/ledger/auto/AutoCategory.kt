package kapi.ledger.auto

import kapi.core.auto.CategoryRules
import kapi.ledger.data.KapiDatabase

/**
 * 把 [CategoryRules] 判出来的路径落到库里的具体一行。
 *
 * ## 为什么不直接在规则表里存 id
 *
 * 分类是**用户可见的数据**——他可以改名、删除、另建。把 id 写进规则表，就等于
 * 规则表和用户的数据长在了一起：他删掉「餐饮/早餐」，自动分类会开始往一个
 * 不存在的 id 上指；他改个名字，规则表里的 id 就对不上了。
 *
 * 所以规则表只认**名字**，查库这一步留在这里。查不到就退回顶级分类，
 * 再查不到就返回 null（界面上是「未分类」）。
 */
object AutoCategory {

    /**
     * 判一笔账该进哪一栏。
     *
     * @param rawText 账单详情页原文。商户名不认识时，商品标题在里面——
     *                支付宝那笔 `微雪**店 / ESP32-P4开发板` 就是靠它判成数码的。
     * @return 分类 id，判不出来时为 null。
     */
    suspend fun resolve(
        db: KapiDatabase,
        ledgerId: String,
        merchant: String?,
        rawText: String?,
        direction: String,
    ): String? {
        val kind = if (direction == "INCOME") "INCOME" else "EXPENSE"
        val path = CategoryRules.classify(merchant, rawText, direction == "INCOME")
            ?: return null

        // 细分类优先。找不到就退回顶级——"餐饮"总比"未分类"有用。
        val minor = path.minor?.let { db.categoryDao().byName(ledgerId, kind, it) }
        if (minor != null) return minor.id

        return db.categoryDao().byName(ledgerId, kind, path.major)?.id
    }
}
