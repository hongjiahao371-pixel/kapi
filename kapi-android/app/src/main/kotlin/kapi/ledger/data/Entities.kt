package kapi.ledger.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 一笔账单。
 *
 * 三条硬规矩，全部由类型和测试强制，不靠 code review 自觉：
 *
 * 1. **金额恒为 [amountMinor]，单位是「分」，类型是 Long。**
 *    这一层不允许出现 Float/Double。账单少记一分是误差，浮点累积出的分位误差
 *    则是"这个软件算出来的数不能信"，而账本用户一旦不信这个数，整个产品就废了。
 *    `occurredAt` 之类的毫秒时间戳同理。
 *
 * 2. **方向不靠符号表达。** 正收入负支出看着简洁，但一个「模型把 -23.50 当成
 *    支出」和一个「当成收入」在数据上无法区分，界面上就会把支出显示成收入。
 *    拆成量+方向之后，这类错误在结构上不可能发生。
 *
 * 3. **本地库是缓存，不是权威。** 服务端才是。所以同步元数据（[serverFvJson]、
 *    [pendingJson]）和业务字段放在同一张表，由 SyncEngine 独占读写——
 *    分成两张表只会换来一次跨表事务，收益为零，复杂度翻倍。
 */
@Entity(
    tableName = "txn",
    indices = [
        // 列表页永远是「按时间倒序」，这是最高频的查询，必须有索引。
        Index(value = ["occurredAt"]),
        // 待推送扫描：只查有未推送变更的行。
        Index(value = ["hasPending"]),
        Index(value = ["ledgerId", "occurredAt"]),
    ],
)
data class TxnEntity(
    @PrimaryKey
    val id: String,

    val ledgerId: String,

    // ---- 金额与方向 ----
    /** 以「分」为单位的金额绝对值。永远 >= 0。 */
    val amountMinor: Long,
    /** 见 kapi.core.llm.Direction 的编码：EXPENSE / INCOME / UNKNOWN。 */
    val direction: String,

    // ---- 归属 ----
    val accountId: String,
    /** 转账的转入账户，普通消费为 null。 */
    val toAccountId: String? = null,
    val categoryId: String? = null,

    // ---- 业务字段 ----
    /** 业务发生时间，epoch millis。**用户可编辑**，这正是不能用它做合并排序的原因。 */
    val occurredAt: Long,
    val note: String? = null,
    val merchant: String? = null,
    /** JSON array 文本。 */
    val tagsJson: String = "[]",
    /** 冗余计数，避免列表查询去 join 附件表。 */
    val attachmentCount: Int = 0,

    // ---- 同步元数据（SyncEngine 独占） ----
    /** 服务端已知的字段戳，JSON：`{"amount_minor":{"l":10,"d":"phone"}, ...}` */
    val serverFvJson: String = "{}",
    /** 本地已改未推送的字段，JSON：`{"note":{"value":"午餐","fv":{"l":12,"d":"phone"}}}` */
    val pendingJson: String = "{}",
    /**
     * [pendingJson] 是否非空。单独一个布尔列而不是每次解析 JSON，
     * 是因为「有哪些行需要推送」这个查询在每次同步都会被扫很多遍。
     */
    val hasPending: Boolean = false,

    val delFvJson: String = """{"l":0,"d":""}""",
    val deleted: Boolean = false,
    val lamport: Long = 0,

    /** 识别来源：LLM / LOCAL_FALLBACK / MANUAL / SYNC。UI 要能告诉用户这一笔怎么来的。 */
    val source: String = "MANUAL",
    /** 识别置信度 0..1。低置信度的行应当始终要求用户确认。 */
    val confidence: Double = 0.0,
)

@Entity(tableName = "category", indices = [Index(value = ["ledgerId", "parentId"])])
data class CategoryEntity(
    @PrimaryKey val id: String,
    val ledgerId: String,
    val parentId: String? = null,
    val name: String,
    val icon: String? = null,
    val kind: String = "EXPENSE",
    val sortOrder: Int = 0,
    val serverFvJson: String = "{}",
    val pendingJson: String = "{}",
    val hasPending: Boolean = false,
    val delFvJson: String = """{"l":0,"d":""}""",
    val deleted: Boolean = false,
    val lamport: Long = 0,
)

@Entity(tableName = "account", indices = [Index(value = ["ledgerId"])])
data class AccountEntity(
    @PrimaryKey val id: String,
    val ledgerId: String,
    val name: String,
    /** CASH / DEBIT / CREDIT / INVESTMENT / LOAN */
    val kind: String,
    /** 余额，同样是「分」。 */
    val balanceMinor: Long = 0,
    val currency: String = "CNY",
    val icon: String? = null,
    val sortOrder: Int = 0,
    val serverFvJson: String = "{}",
    val pendingJson: String = "{}",
    val hasPending: Boolean = false,
    val delFvJson: String = """{"l":0,"d":""}""",
    val deleted: Boolean = false,
    val lamport: Long = 0,
)

@Entity(tableName = "ledger")
data class LedgerEntity(
    @PrimaryKey val id: String,
    val name: String,
    /** PERSONAL / SHARED。共享账本 v1 不做。 */
    val kind: String = "PERSONAL",
    val serverFvJson: String = "{}",
    val pendingJson: String = "{}",
    val hasPending: Boolean = false,
    val delFvJson: String = """{"l":0,"d":""}""",
    val deleted: Boolean = false,
    val lamport: Long = 0,
)

/** 单行同步状态。全库只有一行，所以用固定 id。 */
@Entity(tableName = "sync_state")
data class SyncStateEntity(
    @PrimaryKey val id: Int = SINGLETON,
    val baseUrl: String = "",
    val deviceId: String = "",
    /** 已确认拉取到的位置。增量拉取的游标。 */
    val cursor: Long = 0,
    val lamportCounter: Long = 0,
    val lastSyncAt: Long = 0,
    val lastError: String? = null,
) {
    companion object { const val SINGLETON = 1 }
}

/**
 * 自动识别出来、**还没确认**的一笔。
 *
 * 为什么要有这张表，而不是识别完直接入账：
 *
 * 自动记账的输入是**别家 App 屏幕上的文字**，而 LocalTextParser 的置信度
 * 上限被刻意压在 0.55——规则匹配经常差一点点：把订单号当金额、把优惠券面额
 * 当实付、把退款当成支出。这些错直接进账本，用户是不会去逐笔核对的，
 * 他只会在某个月底发现总额对不上，然后**再也不信这个 App**。
 *
 * 所以默认走"识别 → 存草稿 → 用户一眼扫过 → 确认"。这不是给自动记账打折，
 * 这是承认它的可靠度。设置里可以开"高置信度直接入账"，但默认必须是确认。
 *
 * **这张表不同步。** 它是**这台设备**的观察结果，不是账单数据。
 * 把草稿推到 NAS 会在另一台手机上冒出一笔没头没尾的记录。
 */
@Entity(
    tableName = "auto_draft",
    indices = [
        Index(value = ["fingerprint"]),
        Index(value = ["createdAt"]),
        // 跨来源合并要按「同额同时段」捞候选，缺这个索引就是全表扫。
        // 自动记账每捕获一次就跑一次，账单攒到几千条时差别很明显。
        Index(value = ["amountMinor", "direction", "occurredAt"]),
    ],
)
data class AutoDraftEntity(
    @PrimaryKey val id: String,
    val ledgerId: String,
    val amountMinor: Long,
    val direction: String,
    val occurredAt: Long,
    val merchant: String? = null,
    val note: String? = null,
    /**
     * 猜的账户。
     *
     * 按来源 App 猜（微信通知 → 微信钱包，银行通知 → 银行卡），猜错的可能性不小，
     * 所以确认页要把它做成**已经选中的**状态，用户改一下就行，而不是空着让他从头选。
     * 账户错了比金额错了更难被发现——金额会立刻看着不对，账户错要到对账那天才暴露。
     */
    val accountId: String? = null,
    /** 首次捕获的采集渠道：A11Y（无障碍读屏）/ NOTIFY（通知栏）。 */
    val source: String,
    val confidence: Double = 0.0,
    /**
     * 去重指纹 = `渠道|归一化文本` 的 64 位散列，见 [kapi.core.auto.AutoBook]。
     *
     * 同一笔支付在无障碍里会被触发**几十次**：支付页每重绘一次就是一个
     * TYPE_WINDOW_CONTENT_CHANGED，返回列表页又是一串。没有它，一笔 23.50
     * 会在账本里变成 23.50 × 50。
     *
     * 指纹里带渠道是故意的：通知栏和无障碍看到的是两段不同的文本，让它们
     * 撞在一起会导致用户在微信里多滑几下、账本凭空多出好几笔。跨来源的
     * 重复由 [AutoDraftDao.nearMisses] 在草稿层处理——那里有用户在场。
     */
    val fingerprint: String,
    val createdAt: Long,
    /** 命中的来源 App 包名，设置页要让用户知道「这笔是从哪读来的」。 */
    val originPackage: String? = null,
    /** 识别时的原始文本，界面上要能展开给用户核对。 */
    val rawText: String? = null,
    /**
     * 合并进来的其他来源，逗号分隔，例如 `"NOTIFY,A11Y"`。
     *
     * 同一笔支付会先后被通知栏和无障碍各读一次。两条都记是**重复记账**，
     * 用户月底一看总额翻倍，直接不会再信这个 App。但自动丢弃又不行——
     * 两次真实的同额消费可能就隔了两分钟，那是**丢账**，而且用户看不见。
     *
     * 所以并成一条草稿、在界面上明说"两条来源都提到了这笔"，让用户一键合并
     * 或一键拆开。**用户在场就不算丢失。**
     */
    val sources: String = "",
    /** 独立证据条数。界面上要显示"捕获了 N 次"，重复次数是可信度信号。 */
    val evidenceCount: Int = 1,
    /**
     * 账本里已经存在的、很像这一笔的 id。
     *
     * 大模型高置信度直接入账时，另一条通路可能同时把同一笔收成草稿。这时候
     * 草稿不能悄悄躺在队列里等用户点"记一笔"——照点就会重复记账。界面上
     * 把它标成"账本里已经有一笔很像的"，把决定权交给用户。
     */
    val likelyDuplicateOf: String? = null,
)

/**
 * 月度预算行（2026-10-03，咔皮对齐 P1）。
 *
 * `categoryId = null` 是当月**总预算**；非空是**该分类（顶级）的预算**。
 * 对标商汤咔皮记账的「预算管理」：月预算 + 二级分类额度 + 进度条。
 *
 * ## 刻意不同步 NAS
 *
 * 预算是"这台设备上给自己定的纪律"，不是账本数据——账本的多设备一致性由
 * FV/Lamport 那套机器保证，把预算塞进去就要给 LocalMerge 加字段、给服务端
 * 加表，换来的只是"两台手机看到同一条预算"。等真的出现这个需求再接，
 * 那时 id 方案也要重看（现在是确定性的 `b-<month>-total` / `b-<month>-<categoryId>`，
 * 同月同分类重复设置时 REPLACE 覆盖，天然不重不漏——这套 id 换成
 * 多设备 Lamport 行就得全部重来）。
 */
@Entity(
    tableName = "budget",
    indices = [Index(value = ["ledgerId", "month"])],
)
data class BudgetEntity(
    @PrimaryKey val id: String,
    val ledgerId: String,
    /** 归属月份，`2026-10` 格式。 */
    val month: String,
    /** null = 月总预算；非空 = 该分类（顶级）的预算。 */
    val categoryId: String?,
    val amountMinor: Long,
)
