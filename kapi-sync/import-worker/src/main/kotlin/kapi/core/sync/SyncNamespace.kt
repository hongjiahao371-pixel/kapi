package kapi.core.sync

import kotlinx.serialization.json.*

/**
 * A shared NAS space has its own IDs and cursor. Prefix every local identity so
 * L1/A1 in a family cannot overwrite L1/A1 in the phone's personal book.
 * References are converted together with identities; money and version stamps
 * are never decoded as floating point or rewritten.
 */
class SyncNamespace(val spaceId: String, private val aliases: Map<String,Set<String>> = emptyMap()) {
    init { require(spaceId.matches(Regex("[a-f0-9]{24}"))) }
    val prefix = "$FAMILY_PREFIX$spaceId:"
    val ledgerId: String get() = localId("L1")
    fun localId(id: String, entity: String = ""): String =
        if (id.isEmpty() || id in aliases[entity].orEmpty()) id else prefix + id
    fun remoteId(id: String, entity: String = ""): String {
        if (id.isEmpty()) return id
        if (!id.startsWith(prefix) && id in aliases[entity].orEmpty()) return id
        require(id.startsWith(prefix)) { "家庭账本引用了其他空间的数据，请核对账户和分类" }
        return id.removePrefix(prefix).also { require(it.isNotEmpty()) }
    }
    fun owns(id: String) = id.startsWith(prefix)

    fun localSnapshot(remote: Snapshot): Snapshot {
        require(remote.entity != "investment_quote") { "行情使用手机公开行情缓存，不同步到家庭账本" }
        val fields = remote.values.mapValues { (field, value) -> value(field, value, true, remote.entity) }.toMutableMap()
        if (remote.entity in setOf("txn", "category", "account") && "ledger_id" !in fields)
            fields["ledger_id"] = JsonPrimitive(ledgerId).toString()
        return remote.copy(entityId=localId(remote.entityId,remote.entity), values=fields)
    }
    fun remoteChange(local: OutboxChange): OutboxChange {
        require(local.entity !in setOf("recurring", "investment_quote")) { "个人提醒和行情缓存不发送到家庭账本" }
        return local.copy(entityId=remoteId(local.entityId,local.entity),
            data=local.data.mapValues { (field, raw) -> value(field, raw, false, local.entity) })
    }
    private fun id(value: JsonElement, incoming: Boolean, entity: String = ""): JsonElement {
        if (value == JsonNull) return value
        require(value is JsonPrimitive && value.isString) { "账本引用格式不正确" }
        return JsonPrimitive(if(incoming) localId(value.content,entity) else remoteId(value.content,entity))
    }
    private fun value(field: String, raw: String, incoming: Boolean, entity: String): String = when(field) {
        in REFERENCES -> {
            val parsed=runCatching {Json.parseToJsonElement(raw)}.getOrNull()
            val reference=when {
                !incoming -> requireNotNull(parsed)
                parsed==JsonNull -> JsonNull
                parsed is JsonPrimitive && parsed.isString -> parsed
                parsed==null || parsed is JsonPrimitive -> JsonPrimitive(raw)
                else -> error("账本引用格式不正确")
            }
            id(reference,incoming,REFERENCES.getValue(field)).toString()
        }
        "ledger_scope_json" -> {
            val encoded=Json.parseToJsonElement(raw)
            val list=(if(encoded is JsonPrimitive && encoded.isString) Json.parseToJsonElement(encoded.content) else encoded) as? JsonArray
                ?: error("账户账本范围不正确")
            JsonPrimitive(JsonArray(list.map {id(it,incoming,"ledger")}).toString()).toString()
        }
        "payload" -> {
            val encoded=Json.parseToJsonElement(raw)
            val payload=(if(encoded is JsonPrimitive && encoded.isString) Json.parseToJsonElement(encoded.content) else encoded) as? JsonObject
                ?: error("账本配置格式不正确")
            JsonPrimitive(JsonObject(payload.mapValues { (key,v) ->
                when {
                    key=="id" -> id(v,incoming,entity)
                    key in PAYLOAD_REFERENCES -> id(v,incoming,PAYLOAD_REFERENCES.getValue(key))
                    key=="pattern" && entity=="category_rule" && payload["kind"]?.jsonPrimitive?.content=="ACCOUNT" -> id(v,incoming,"account")
                    key=="pattern" && entity=="category_rule" && payload["kind"]?.jsonPrimitive?.content=="COMPOSITE" ->
                        composite(v,incoming)
                    else -> v
                }
            }).toString()).toString()
        }
        else -> raw
    }
    private fun composite(encoded: JsonElement, incoming: Boolean): JsonElement {
        val rule=Json.parseToJsonElement(encoded.jsonPrimitive.content).jsonObject
        val conditions=rule["conditions"] as? JsonArray ?: error("组合分类规则格式不正确")
        return JsonPrimitive(JsonObject(rule+("conditions" to JsonArray(conditions.map { value ->
            val c=value.jsonObject
            if(c["field"]?.jsonPrimitive?.content=="ACCOUNT") JsonObject(c+("value" to id(c.getValue("value"),incoming,"account"))) else c
        }))).toString())
    }
    companion object {
        const val FAMILY_PREFIX="family:"
        fun isFamilyId(id: String)=id.startsWith(FAMILY_PREFIX)
        private val REFERENCES=mapOf("ledger_id" to "ledger","account_id" to "account","to_account_id" to "account",
            "category_id" to "category","parent_id" to "category","refund_of_id" to "txn")
        private val PAYLOAD_REFERENCES=mapOf("ledgerId" to "ledger","accountId" to "account","toAccountId" to "account",
            "categoryId" to "category","parentId" to "category","refundOfId" to "txn","sourceTxnId" to "txn")
    }
}
