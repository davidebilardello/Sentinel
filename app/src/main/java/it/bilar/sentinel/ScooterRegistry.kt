package it.bilar.sentinel

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class SavedScooter(val id: String, val name: String, val family: ScooterFamily, val address: String? = null, val profileSlot: String? = null,
    val syncId: String? = null, val modified: Long = 0)

class ScooterRegistry(private val context: Context) {
    companion object { private val lock = Any() }
    private val preferences = context.getSharedPreferences("scooter", Context.MODE_PRIVATE)
    fun all(): List<SavedScooter> = try {
        val saved = preferences.getString("vehicles", null)
        if (saved == null) defaults() else JSONArray(saved).let { array ->
            (0 until array.length()).map { i ->
                val item = array.getJSONObject(i)
                SavedScooter(item.getString("id"), item.getString("name"), ScooterFamily.valueOf(item.getString("family")),
                    item.optString("address").takeIf(String::isNotBlank), item.optString("slot").takeIf(String::isNotBlank),
                    item.optString("syncId").takeIf(String::isNotBlank),item.optLong("modified"))
            }.ifEmpty { defaults() }
        }
    } catch (_: Exception) { defaults() }
    private fun defaults() = listOf(SavedScooter("sentinel", "Jeep Sentinel", ScooterFamily.SENTINEL), SavedScooter("youfs", "YouFs-A", ScooterFamily.YOUFS_TUYA))
    fun selected(): SavedScooter {
        val entries = all()
        return entries.find { it.id == preferences.getString("active_vehicle", null) }
            ?: entries.find { it.family.name == preferences.getString("family", null) } ?: entries.first()
    }
    fun save(scooter: SavedScooter, profileChanged: Boolean = false) = synchronized(lock) {
        val old = all().find { it.id == scooter.id }
        val key = scooter.syncId ?: identity(scooter)
        val updated = scooter.copy(syncId = key, modified = old?.modified ?: 0)
        if (updated == old && !profileChanged) return@synchronized
        val deleted = JSONObject(preferences.getString("sync_deleted","{}")!!)
        val removedAt = key?.let { deleted.optLong(it) } ?: 0
        write(all().filterNot { it.id == scooter.id } + updated.copy(modified = maxOf(System.currentTimeMillis(),(old?.modified ?: 0)+1,removedAt+1)))
        key?.let { deleted.remove(it); preferences.edit().putString("sync_deleted",deleted.toString()).apply() }
        GoogleSync.schedule(context)
    }
    fun saveProfile(scooter: SavedScooter, bytes: ByteArray) = synchronized(lock) {
        YouFsProfileStore(context,scooter.profileSlot).save(bytes)
        save(scooter,profileChanged = true)
    }
    private fun identity(scooter: SavedScooter): String? = if (scooter.family == ScooterFamily.SENTINEL) scooter.address?.let { "sentinel:${it.uppercase()}" }
        else runCatching { YouFsProfileStore(context,scooter.profileSlot).load()?.json?.getString("deviceId")?.let { "youfs:$it" } }.getOrNull()
    private fun write(entries: List<SavedScooter>) {
        val array = JSONArray()
        entries.forEach { array.put(JSONObject().put("id", it.id).put("name", it.name).put("family", it.family.name)
            .put("address", it.address ?: "").put("slot", it.profileSlot ?: "").put("syncId",it.syncId ?: "").put("modified",it.modified)) }
        preferences.edit().putString("vehicles", array.toString()).apply()
    }
    fun select(id: String) {
        val entry = all().first { it.id == id }
        preferences.edit().putString("active_vehicle", id).putString("family", entry.family.name).apply()
    }
    fun remove(id: String) = synchronized(lock) {
        all().find { it.id == id }?.let { entry ->
            val key = entry.syncId ?: identity(entry)
            if (key != null) {
                val deleted = JSONObject(preferences.getString("sync_deleted","{}")!!)
                deleted.put(key,maxOf(System.currentTimeMillis(),entry.modified+1))
                preferences.edit().putString("sync_deleted",deleted.toString()).apply()
            }
        }
        val remaining = all().filterNot { it.id == id }
        val entries = remaining.ifEmpty { listOf(SavedScooter("sentinel", "Jeep Sentinel", ScooterFamily.SENTINEL)) }
        write(entries)
        if (selected().id == id || preferences.getString("active_vehicle", null) == id) select(entries.first().id)
        GoogleSync.schedule(context)
    }
    internal fun syncRecords(): List<SyncRecord> = synchronized(lock) {
        val records = mutableListOf<SyncRecord>()
        val entries = all().map { entry -> if (entry.syncId == null || entry.modified == 0L) identity(entry)?.let { entry.copy(syncId = it,modified = System.currentTimeMillis()) } ?: entry else entry }
        if (entries != all()) write(entries)
        entries.forEach { entry ->
            val key = entry.syncId ?: return@forEach
            val data = JSONObject().put("name",entry.name).put("family",entry.family.name).put("address",entry.address ?: "")
            if (entry.family == ScooterFamily.YOUFS_TUYA) {
                val profile = YouFsProfileStore(context,entry.profileSlot).load() ?: return@forEach
                data.put("profile",profile.json)
            }
            records += SyncRecord("scooter",key,entry.modified,false,data)
        }
        val deleted = JSONObject(preferences.getString("sync_deleted","{}")!!)
        deleted.keys().forEach { records += SyncRecord("scooter",it,deleted.getLong(it),true) }
        records
    }
    internal fun validateSync(records: List<SyncRecord>) {
        records.filter { it.kind == "scooter" }.forEach { record ->
            require(record.id.matches(Regex("youfs:[A-Za-z0-9]{1,22}|sentinel:[0-9A-F]{2}(:[0-9A-F]{2}){5}")))
            if (!record.deleted) {
                val d = record.data; require(d.getString("name").let { it.isNotBlank() && it.length <= 80 })
                val family = ScooterFamily.valueOf(d.getString("family")); val address = d.getString("address")
                require(address.isEmpty() || address.matches(Regex("[0-9A-F]{2}(:[0-9A-F]{2}){5}")))
                if (family == ScooterFamily.SENTINEL) require(record.id == "sentinel:$address")
                else require(record.id == "youfs:${YouFsProfile.parse(d.getJSONObject("profile").toString().toByteArray()).json.getString("deviceId")}")
            }
        }
    }
    internal fun mergeSync(records: List<SyncRecord>) = synchronized(lock) {
        validateSync(records)
        val local = SyncRecord.merge(syncRecords()).associateBy { it.id }
        val deleted = JSONObject(preferences.getString("sync_deleted","{}")!!)
        var entries = all()
        SyncRecord.merge(local.values+records.filter { it.kind == "scooter" }).forEach { record ->
            val old = local[record.id]
            if (record === old) return@forEach
            val existing = entries.find { it.syncId == record.id }
            if (record.deleted) {
                existing?.let { if(it.family == ScooterFamily.YOUFS_TUYA) YouFsProfileStore(context,it.profileSlot).forget() }
                entries = entries.filterNot { it.syncId == record.id }
                deleted.put(record.id,record.modified)
            } else {
                val d = record.data; val family = ScooterFamily.valueOf(d.getString("family"))
                val vacant = entries.find { it.family == family && it.syncId == null }
                val id = existing?.id ?: vacant?.id ?: java.util.UUID.randomUUID().toString()
                val slot = existing?.profileSlot ?: vacant?.profileSlot ?: if(family == ScooterFamily.YOUFS_TUYA && id != "youfs") id else null
                if (family == ScooterFamily.YOUFS_TUYA) YouFsProfileStore(context,slot).save(d.getJSONObject("profile").toString().toByteArray())
                val entry = SavedScooter(id,d.getString("name"),family,d.getString("address").takeIf(String::isNotEmpty),slot,record.id,record.modified)
                entries = entries.filterNot { it.id == id } + entry
                deleted.remove(record.id)
            }
        }
        write(entries.ifEmpty { defaults() })
        preferences.edit().putString("sync_deleted",deleted.toString()).apply()
    }
}
