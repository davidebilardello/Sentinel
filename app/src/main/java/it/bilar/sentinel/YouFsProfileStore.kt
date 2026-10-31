package it.bilar.sentinel

import android.content.Context
import org.json.JSONObject
import java.io.File

data class YouFsDp(val id: Int, val code: String, val type: String, val writable: Boolean,
    val min: Double, val max: Double, val scale: Int, val step: Double, val options: List<String>, val maxlen: Int, val unit: String)
data class YouFsValue(val type: Int, val value: Any?)

class YouFsProfile private constructor(val json: JSONObject, val productId: String, val schema: List<YouFsDp>) {
    override fun toString() = "YouFsProfile(redacted)"
    companion object {
        const val MAX_BYTES = 262144
        fun parse(bytes: ByteArray): YouFsProfile {
            require(bytes.size <= MAX_BYTES) { appText(R.string.device_file_is_too_large) }
            val source = JSONObject(bytes.toString(Charsets.UTF_8))
            fun field(name: String, vararg aliases: String): String = (listOf(name) + aliases).firstNotNullOfOrNull {
                source.optString(it).takeIf(String::isNotEmpty)
            }.orEmpty()
            val uuid = field("uuid"); val deviceId = field("deviceId", "device_id", "devId")
            val localKey = field("localKey", "local_key"); val secKey = field("secKey", "sec_key", "secretKey")
            require(uuid.matches(Regex("[A-Za-z0-9]{16}")) && deviceId.matches(Regex("[A-Za-z0-9]{1,22}"))) { appText(R.string.invalid_device_identifiers) }
            require(localKey.matches(Regex("[!-~]{16}")) && secKey.matches(Regex("[!-~]{16}"))) { appText(R.string.the_device_localkey_and_seckey_are_required) }
            require(field("securityMode").let { it.isEmpty() || it == "new" }) { appText(R.string.import_a_youfs_profile_using_the_new_security_mode) }
            val product = field("productId")
            require(product.isNotBlank() && product.length <= 64) { appText(R.string.missing_product_id) }
            val array = source.getJSONArray("schema")
            require(array.length() in 1..255) { appText(R.string.missing_device_schema) }
            val seen = mutableSetOf<Int>()
            val definitions = (0 until array.length()).map { i ->
                val d = array.getJSONObject(i); val p = d.getJSONObject("property"); val id = d.getInt("id")
                require(id in 1..255 && seen.add(id)) { appText(R.string.invalid_or_duplicate_dp_id) }
                val type = p.getString("type"); val writable = d.optString("mode") == "rw"
                val scale = p.optInt("scale", 0); val min = p.optDouble("min", 0.0); val max = p.optDouble("max", 0.0); val step = p.optDouble("step", 1.0)
                require(type in setOf("bool", "value", "enum", "string", "raw", "bitmap")) { appText(R.string.unsupported_dp_type) }
                if (type == "value") require(scale in 0..6 && min.isFinite() && max.isFinite() && min <= max &&
                    min >= Int.MIN_VALUE && max <= Int.MAX_VALUE && min % 1.0 == 0.0 && max % 1.0 == 0.0 &&
                    step.isFinite() && step > 0 && step % 1.0 == 0.0) { appText(R.string.invalid_dp_range) }
                val range = p.optJSONArray("range")
                val options = if (range == null) emptyList() else (0 until range.length()).map { range.getString(it) }
                if (type == "enum") require(options.size in 1..256) { appText(R.string.missing_dp_options) }
                val maxlen = p.optInt("maxlen", 0)
                if (type == "string" && writable) require(maxlen in 1..255) { appText(R.string.missing_dp_text_length_limit) }
                YouFsDp(id, d.optString("code", "DP $id"), type, writable, min, max, scale, step, options, maxlen, p.optString("unit"))
            }
            val clean = JSONObject().put("uuid", uuid).put("deviceId", deviceId).put("localKey", localKey).put("secKey", secKey)
                .put("securityMode", "new").put("productId", product).put("schema", array)
            val address = source.optString("address", source.optString("mac"))
            if (address.matches(Regex("(?i)[0-9a-f]{2}(:[0-9a-f]{2}){5}"))) clean.put("address", address.uppercase())
            source.optString("name").takeIf(String::isNotBlank)?.let { clean.put("name", it.take(80)) }
            return YouFsProfile(clean, product, definitions)
        }
    }
}

class YouFsProfileStore(context: Context, slot: String? = null) {
    init { require(slot == null || slot.matches(Regex("[a-f0-9-]{36}"))) }
    private val target = EncryptedJsonFile(context, if (slot == null) "youfs-profile.enc" else "youfs-profile-$slot.enc")
    private val incoming = File(context.filesDir, "youfs-import.json")
    fun save(bytes: ByteArray): YouFsProfile {
        val profile = YouFsProfile.parse(bytes)
        target.write(profile.json.toString().toByteArray(Charsets.UTF_8))
        return profile
    }
    fun load(): YouFsProfile? {
        val clear = target.read() ?: return null
        return try { YouFsProfile.parse(clear) } finally { clear.fill(0) }
    }
    fun consumeTransfer(): YouFsProfile? {
        if (!incoming.exists()) return null
        return try {
            require(incoming.length() <= YouFsProfile.MAX_BYTES)
            val clear = incoming.readBytes()
            try { save(clear) } finally { clear.fill(0) }
        } finally { incoming.delete() }
    }
    fun forget() { target.delete(); incoming.delete() }
}
