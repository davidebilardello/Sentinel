package it.bilar.sentinel

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

internal data class SyncRecord(val kind: String, val id: String, val modified: Long, val deleted: Boolean, val data: JSONObject = JSONObject()) {
    val key get() = "$kind:$id"
    fun json() = JSONObject().put("kind",kind).put("id",id).put("modified",modified).put("deleted",deleted).put("data",data)
    override fun toString() = "SyncRecord(redacted)"
    companion object {
        fun parse(value: JSONObject): SyncRecord {
            val record = SyncRecord(value.getString("kind"),value.getString("id"),value.getLong("modified"),value.getBoolean("deleted"),value.getJSONObject("data"))
            require(record.kind in setOf("trip","scooter","preferences") && record.id.length in 1..100 && record.modified in 1..(System.currentTimeMillis()+86_400_000))
            return record
        }
        fun encode(records: Collection<SyncRecord>): ByteArray = JSONObject().put("version",2)
            .put("records",JSONArray().apply { records.sortedBy { it.key }.forEach { put(it.json()) } }).toString().toByteArray(Charsets.UTF_8)
        fun decode(bytes: ByteArray): List<SyncRecord> {
            val json = JSONObject(bytes.toString(Charsets.UTF_8)); require(json.getInt("version") in 1..2)
            val array = json.getJSONArray("records"); require(array.length() <= 20_000)
            return (0 until array.length()).map { parse(array.getJSONObject(it)) }.also { require(it.map(SyncRecord::key).distinct().size == it.size) }
        }
        fun merge(records: Iterable<SyncRecord>): List<SyncRecord> = records.groupBy { it.key }.values.map { versions ->
            versions.maxWith(compareBy<SyncRecord> { it.modified }.thenBy { it.deleted }.thenBy { it.data.has("speeds") }.thenBy { fingerprint(it.data) })
        }
        private fun fingerprint(value: Any?): String {
            fun canonical(node: Any?): String = when(node) {
                is JSONObject -> node.keys().asSequence().toList().sorted().joinToString(prefix="{",postfix="}") { JSONObject.quote(it)+":"+canonical(node.get(it)) }
                is JSONArray -> (0 until node.length()).joinToString(prefix="[",postfix="]") { canonical(node.get(it)) }
                is String -> JSONObject.quote(node)
                null, JSONObject.NULL -> "null"
                else -> node.toString()
            }
            return MessageDigest.getInstance("SHA-256").digest(canonical(value).toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
        }
    }
}
