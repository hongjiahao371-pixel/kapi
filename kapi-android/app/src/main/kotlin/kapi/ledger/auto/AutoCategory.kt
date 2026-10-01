package kapi.ledger.auto

import android.util.Log
import kapi.core.auto.CategoryRules
import kapi.core.auto.MerchantLearning
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
     * ## 优先级：**用户教过的 > 规则猜的**
     *
     * ```
     * 1. 自学习   ← 用户在这个商户手动归过 N 次同一档
     * 2. 规则表   ← 商户名 → 账单原文里的商品标题
     * 3. null     ← 未分类
     * ```
     *
     * 学习排在规则前面，理由是**证据强度不同**：规则是我们的猜测，而"他在星巴克
     * 连着五笔都手动选了餐饮"是他自己反复做的决定。用户教的比我们猜的准，
     * 哪怕他教的那一档在我们看来不如规则精确。
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
        // 1. 自学习。只看**同方向**的历史——支出教不出收入那一档。
        val key = MerchantLearning.normalize(merchant)
        if (key != null) {
            val hist = db.txnDao().categoryHistogramFor(key)
            val learned = MerchantLearning.decide(hist.map { it.categoryId to it.n })
            if (learned != null) {
                Log.i(
                    TAG,
                    "自学习：$key → ${learned.second} 次同一档，用它（先于规则）",
                )
                // 查一下那行还在不在。用户可能已经把那个分类删了。
                val row = db.categoryDao().byId(learned.first)
                if (row != null && !row.deleted) return row.id
                Log.w(TAG, "自学习指向的分类 ${learned.first} 已不存在，回退到规则")
            }
        }

        // 2. 规则表。
        val kind = if (direction == "INCOME") "INCOME" else "EXPENSE"
        val path = CategoryRules.classify(merchant, rawText, direction == "INCOME")
            ?: return null

        // 细分类优先。找不到就退回顶级——"餐饮"总比"未分类"有用。
        val minor = path.minor?.let { db.categoryDao().byName(ledgerId, kind, it) }
        if (minor != null) return minor.id

        val major = db.categoryDao().byName(ledgerId, kind, path.major)?.id
        if (major != null && key != null) {
            Log.i(TAG, "规则：$key → $major/${path.minor ?: "顶级"}")
        }
        return major
    }

    private const val TAG = "AutoCategory"
}
