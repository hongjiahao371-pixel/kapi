package kapi.core.sync

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 字段级版本戳。
 *
 * [l] 是 Lamport 计数（设备内单调），[d] 是设备 id，仅用于打破 [l] 相等时的平局，
 * 使合并结果与到达顺序无关。
 *
 * 为什么不用设备墙上时间：`occurred_at`（账单发生时间）是用户可编辑的，手机时间
 * 还能被手动改、系统时钟会漂。拿它做「最后写入者胜」，合并结果会静默错乱且不报
 * 任何错。参见服务端 `internal/merge` 的说明。
 */
@Serializable
data class FieldVersion(
    @SerialName("l") val lamport: Long = 0,
    @SerialName("d") val device: String = "",
) : Comparable<FieldVersion> {
    override fun compareTo(other: FieldVersion): Int {
        if (lamport != other.lamport) return lamport.compareTo(other.lamport)
        return device.compareTo(other.device)
    }
}

/** 字段版本表：字段名 → 版本戳。 */
typealias FieldVersions = Map<String, FieldVersion>

/**
 * 服务端下发的合并后快照。
 *
 * [values] 里的值一律是**原始 JSON 字符串**，不是解析后的对象。
 * 这不是偷懒：解成 `Map<String, Any>` 会把数字变成 Double，于是金额重启前后
 * 类型不一致，而且 Double 是账本最不该出现的类型。金额在服务端按「分」存
 * 整数，这里就必须按字节原样透传。
 */
@Serializable
data class Snapshot(
    val entity: String,
    @SerialName("entity_id") val entityId: String,
    val values: Map<String, String> = emptyMap(),
    val fv: FieldVersions = emptyMap(),
    @SerialName("del_fv") val delFv: FieldVersion = FieldVersion(),
    val deleted: Boolean = false,
    val lamport: Long = 0,
) {
    /** 行内最新的内容戳。删除态判定要用它，见 [kapi.core.sync.LocalMerge]。 */
    fun newestContentStamp(): FieldVersion =
        fv.values.maxOrNull() ?: FieldVersion()

    /**
     * 服务端不变式：行处于删除态 ⟺ 删除戳严格压过所有内容戳的最大值。
     *
     * 这里重算而不是直接读 [deleted]，是为了在客户端发现服务端逻辑变了时能立刻
     * 暴露出来，而不是悄悄按旧规则继续跑。
     */
    fun isDeleted(): Boolean = delFv > newestContentStamp()
}

/** 一条待推送的本地变更。 */
@Serializable
data class OutboxChange(
    @SerialName("change_id") val changeId: String,
    val entity: String,
    @SerialName("entity_id") val entityId: String,
    val op: String,
    val data: Map<String, String> = emptyMap(),
    val fv: FieldVersions = emptyMap(),
    val lamport: Long,
    val origin: String,
)
