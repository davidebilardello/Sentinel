package it.bilar.sentinel

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class TripScooter(val id: String, val name: String)

data class TripSummary(val id: Long, val startedAt: Long, val endedAt: Long?, val lastPointAt: Long,
    val meters: Double, val pointCount: Int, val interrupted: Boolean, val scooter: TripScooter? = null) {
    val durationMillis get() = ((endedAt ?: lastPointAt) - startedAt).coerceAtLeast(0)
}
data class RecordingStatus(val monitoring: Boolean = false, val recording: Boolean = false,
    val message: String = appText(R.string.connect_your_scooter_to_record_trips_automatically), val accuracyMeters: Float? = null)

class TripStore(private val context: Context, databaseName: String = "trips.db") : SQLiteOpenHelper(context, databaseName, null, 3) {
    private val preferences = context.getSharedPreferences("trips", Context.MODE_PRIVATE)
    val enabled = MutableStateFlow(preferences.getBoolean("automatic", true))
    val scooterConnected = MutableStateFlow(false)
    val connectedScooter = MutableStateFlow<TripScooter?>(null)
    fun setConnectedScooter(scooter: SavedScooter?) {
        connectedScooter.value = scooter?.let { TripScooter(it.syncId ?: "local:${it.id}",it.name.filterNot(Char::isISOControl).take(80).ifBlank { it.family.title }) }
        scooterConnected.value = scooter != null
    }
    val status = MutableStateFlow(RecordingStatus())
    private val history = MutableStateFlow<List<TripSummary>>(emptyList())
    val trips = history.asStateFlow()
    var permissionAsked: Boolean
        get() = preferences.getBoolean("permission_asked", false)
        set(value) { preferences.edit().putBoolean("permission_asked", value).apply() }

    fun setAutomatic(value: Boolean) {
        preferences.edit().putBoolean("automatic", value).putLong("modified",System.currentTimeMillis()).apply(); enabled.value = value
        GoogleSync.schedule(context)
    }

    override fun onConfigure(db: SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true) }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE trips (id INTEGER PRIMARY KEY AUTOINCREMENT, started_at INTEGER NOT NULL, ended_at INTEGER, last_point_at INTEGER NOT NULL, meters REAL NOT NULL, point_count INTEGER NOT NULL, interrupted INTEGER NOT NULL DEFAULT 0, sync_id TEXT, modified_at INTEGER NOT NULL DEFAULT 0, scooter_id TEXT, scooter_name TEXT)")
        db.execSQL("CREATE TABLE points (id INTEGER PRIMARY KEY AUTOINCREMENT, trip_id INTEGER NOT NULL REFERENCES trips(id) ON DELETE CASCADE, latitude REAL NOT NULL, longitude REAL NOT NULL, time INTEGER NOT NULL, accuracy REAL NOT NULL, segment INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX points_trip ON points(trip_id, id)")
        createSyncTables(db)
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        check(oldVersion in 1..2 && newVersion == 3)
        if(oldVersion < 2) {
            db.execSQL("ALTER TABLE trips ADD COLUMN sync_id TEXT")
            db.execSQL("ALTER TABLE trips ADD COLUMN modified_at INTEGER NOT NULL DEFAULT 0")
            db.rawQuery("SELECT id FROM trips",null).use { cursor ->
                while(cursor.moveToNext()) db.execSQL("UPDATE trips SET sync_id = ?, modified_at = ? WHERE id = ?", arrayOf(UUID.randomUUID().toString(),System.currentTimeMillis(),cursor.getLong(0)))
            }
            createSyncTables(db)
        }
        db.execSQL("ALTER TABLE trips ADD COLUMN scooter_id TEXT")
        db.execSQL("ALTER TABLE trips ADD COLUMN scooter_name TEXT")
    }
    private fun createSyncTables(db: SQLiteDatabase) {
        db.execSQL("CREATE UNIQUE INDEX trips_sync_id ON trips(sync_id)")
        db.execSQL("CREATE TABLE sync_deleted (sync_id TEXT PRIMARY KEY, modified_at INTEGER NOT NULL)")
    }

    @Synchronized fun refresh() {
        val result = mutableListOf<TripSummary>()
        readableDatabase.rawQuery("SELECT id, started_at, ended_at, last_point_at, meters, point_count, interrupted, scooter_id, scooter_name FROM trips ORDER BY started_at DESC, id DESC", null).use { cursor ->
            while (cursor.moveToNext()) result += TripSummary(cursor.getLong(0), cursor.getLong(1),
                if (cursor.isNull(2)) null else cursor.getLong(2), cursor.getLong(3), cursor.getDouble(4), cursor.getInt(5), cursor.getInt(6) != 0,
                if(cursor.isNull(7)) null else TripScooter(cursor.getString(7),cursor.getString(8)))
        }
        history.value = result
    }

    @Synchronized fun begin(points: List<TrackPoint>, scooter: TripScooter? = connectedScooter.value): Long {
        require(points.size >= 2)
        val db = writableDatabase
        db.beginTransaction()
        val id: Long
        try {
            val meters = points.zipWithNext().sumOf { (a, b) -> if (a.segment == b.segment) AutoTripDetector.distance(a, b) else 0.0 }
            id = db.insertOrThrow("trips", null, ContentValues().apply {
                put("started_at", points.first().time); put("last_point_at", points.last().time)
                put("meters", meters); put("point_count", points.size)
                put("sync_id",UUID.randomUUID().toString()); put("modified_at",System.currentTimeMillis())
                put("scooter_id",scooter?.id); put("scooter_name",scooter?.name)
            })
            points.forEach { insertPoint(db, id, it) }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        refresh()
        return id
    }

    @Synchronized fun append(tripId: Long, point: TrackPoint, meters: Double) {
        require(meters.isFinite() && meters >= 0)
        val db = writableDatabase
        db.beginTransaction()
        try {
            insertPoint(db, tripId, point)
            db.execSQL("UPDATE trips SET last_point_at = ?, meters = meters + ?, point_count = point_count + 1 WHERE id = ? AND ended_at IS NULL", arrayOf<Any>(point.time, meters, tripId))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        refresh()
    }

    private fun insertPoint(db: SQLiteDatabase, tripId: Long, point: TrackPoint) {
        require(point.latitude.isFinite() && point.latitude in -90.0..90.0 && point.longitude.isFinite() && point.longitude in -180.0..180.0)
        db.insertOrThrow("points", null, ContentValues().apply {
            put("trip_id", tripId); put("latitude", point.latitude); put("longitude", point.longitude)
            put("time", point.time); put("accuracy", point.accuracy); put("segment", point.segment)
        })
    }

    @Synchronized fun finish(id: Long, endedAt: Long, interrupted: Boolean = false) {
        writableDatabase.update("trips", ContentValues().apply { put("ended_at", endedAt); put("interrupted", if (interrupted) 1 else 0); put("modified_at",System.currentTimeMillis()) }, "id = ? AND ended_at IS NULL", arrayOf(id.toString()))
        refresh()
        GoogleSync.schedule(context)
    }

    @Synchronized fun recoverInterrupted() {
        writableDatabase.execSQL("UPDATE trips SET ended_at = last_point_at, interrupted = 1, modified_at = ? WHERE ended_at IS NULL",arrayOf(System.currentTimeMillis()))
        refresh()
    }

    @Synchronized fun points(id: Long): List<TrackPoint> {
        val result = mutableListOf<TrackPoint>()
        readableDatabase.rawQuery("SELECT latitude, longitude, time, accuracy, segment FROM points WHERE trip_id = ? ORDER BY id", arrayOf(id.toString())).use { c ->
            while (c.moveToNext()) result += TrackPoint(c.getDouble(0), c.getDouble(1), c.getLong(2), c.getFloat(3), c.getInt(4))
        }
        return result
    }

    @Synchronized fun delete(id: Long) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.rawQuery("SELECT sync_id, ended_at, modified_at FROM trips WHERE id = ?",arrayOf(id.toString())).use { cursor ->
                if (cursor.moveToFirst()) {
                    check(!cursor.isNull(1)) { appText(R.string.you_cannot_delete_an_active_trip) }
                    db.insertWithOnConflict("sync_deleted",null,ContentValues().apply { put("sync_id",cursor.getString(0)); put("modified_at",maxOf(System.currentTimeMillis(),cursor.getLong(2)+1)) },SQLiteDatabase.CONFLICT_REPLACE)
                    db.delete("trips","id = ?",arrayOf(id.toString()))
                }
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        refresh()
        GoogleSync.schedule(context)
    }

    @Synchronized internal fun syncRecords(): List<SyncRecord> {
        val records = mutableListOf<SyncRecord>()
        readableDatabase.rawQuery("SELECT id, sync_id, modified_at, started_at, ended_at, last_point_at, meters, interrupted, scooter_id, scooter_name FROM trips WHERE ended_at IS NOT NULL",null).use { cursor ->
            while(cursor.moveToNext()) {
                val route = JSONArray().apply { points(cursor.getLong(0)).forEach { p -> put(JSONArray().put(p.latitude).put(p.longitude).put(p.time).put(p.accuracy.toDouble()).put(p.segment)) } }
                val data = JSONObject().put("started",cursor.getLong(3)).put("ended",cursor.getLong(4))
                    .put("last",cursor.getLong(5)).put("meters",cursor.getDouble(6)).put("interrupted",cursor.getInt(7) != 0).put("points",route)
                if(!cursor.isNull(8)) data.put("scooter",JSONObject().put("id",cursor.getString(8)).put("name",cursor.getString(9)))
                records += SyncRecord("trip",cursor.getString(1),cursor.getLong(2),false,data)
            }
        }
        readableDatabase.rawQuery("SELECT sync_id, modified_at FROM sync_deleted",null).use { cursor -> while(cursor.moveToNext()) records += SyncRecord("trip",cursor.getString(0),cursor.getLong(1),true) }
        return records
    }
    internal fun validateSync(records: List<SyncRecord>) {
        records.filter { it.kind == "trip" }.forEach { record ->
            require(runCatching { UUID.fromString(record.id).toString() == record.id }.getOrDefault(false))
            if (!record.deleted) {
                val data = record.data; val start = data.getLong("started"); val end = data.getLong("ended")
                require(start > 0 && end >= start && data.getLong("last") in start..end)
                require(data.getDouble("meters").let { it.isFinite() && it in 0.0..100_000_000.0 })
                data.getBoolean("interrupted")
                if(data.has("scooter")) {
                    val scooter = data.getJSONObject("scooter")
                    require(scooter.getString("id").matches(Regex("[A-Za-z0-9:_-]{1,100}")))
                    require(scooter.getString("name").let { it.isNotBlank() && it.length <= 80 && it.none(Char::isISOControl) })
                }
                val array = data.getJSONArray("points"); require(array.length() in 2..200_000)
                var previous = start
                for(i in 0 until array.length()) {
                    val p = array.getJSONArray(i); require(p.length() == 5)
                    require(p.getDouble(0).let { it.isFinite() && it in -90.0..90.0 } && p.getDouble(1).let { it.isFinite() && it in -180.0..180.0 })
                    val time = p.getLong(2); require(time in previous..end); previous = time
                    require(p.getDouble(3).let { it.isFinite() && it in 0.0..10_000.0 } && p.getInt(4) in 0..1_000_000)
                }
                require(previous == data.getLong("last"))
            }
        }
    }
    @Synchronized internal fun mergeSync(records: List<SyncRecord>) {
        validateSync(records)
        val local = SyncRecord.merge(syncRecords()).associateBy { it.id }
        val merged = SyncRecord.merge(local.values+records.filter { it.kind == "trip" })
        val db = writableDatabase; db.beginTransaction()
        try {
            merged.forEach { record ->
                if(record === local[record.id]) return@forEach
                var localId: Long? = null; var modified = 0L; var active = false
                db.rawQuery("SELECT id, modified_at, ended_at FROM trips WHERE sync_id = ?",arrayOf(record.id)).use { c ->
                    if(c.moveToFirst()) { localId = c.getLong(0); modified = c.getLong(1); active = c.isNull(2) }
                }
                var deletedAt = 0L
                db.rawQuery("SELECT modified_at FROM sync_deleted WHERE sync_id = ?",arrayOf(record.id)).use { if(it.moveToFirst()) deletedAt = it.getLong(0) }
                if (active || record.modified < maxOf(modified,deletedAt)) return@forEach
                if (record.deleted) {
                    localId?.let { db.delete("trips","id = ?",arrayOf(it.toString())) }
                    db.insertWithOnConflict("sync_deleted",null,ContentValues().apply { put("sync_id",record.id); put("modified_at",record.modified) },SQLiteDatabase.CONFLICT_REPLACE)
                } else {
                    val d = record.data; val route = d.getJSONArray("points")
                    val values = ContentValues().apply {
                        put("sync_id",record.id); put("modified_at",record.modified); put("started_at",d.getLong("started")); put("ended_at",d.getLong("ended"))
                        put("last_point_at",d.getLong("last")); put("meters",d.getDouble("meters")); put("point_count",route.length()); put("interrupted",if(d.getBoolean("interrupted")) 1 else 0)
                        val scooter = d.optJSONObject("scooter")
                        put("scooter_id",scooter?.getString("id")); put("scooter_name",scooter?.getString("name"))
                    }
                    val id = localId?.also { db.update("trips",values,"id = ?",arrayOf(it.toString())); db.delete("points","trip_id = ?",arrayOf(it.toString())) }
                        ?: db.insertOrThrow("trips",null,values)
                    for(i in 0 until route.length()) { val p = route.getJSONArray(i); insertPoint(db,id,TrackPoint(p.getDouble(0),p.getDouble(1),p.getLong(2),p.getDouble(3).toFloat(),p.getInt(4))) }
                    db.delete("sync_deleted","sync_id = ?",arrayOf(record.id))
                }
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        refresh()
    }

    @Synchronized internal fun restoreBackup(records: List<SyncRecord>): Int {
        check(!status.value.recording) { appText(R.string.end_the_trip_before_restoring_a_backup) }
        readableDatabase.rawQuery("SELECT 1 FROM trips WHERE ended_at IS NULL LIMIT 1",null).use {
            check(!it.moveToFirst()) { appText(R.string.end_the_trip_before_restoring_a_backup) }
        }
        require(records.isNotEmpty() && records.all { it.kind == "trip" && !it.deleted })
        validateSync(records)
        val local = syncRecords().associateBy { it.id }
        val restored = records.filter { local[it.id]?.deleted != false }.map { record ->
            record.copy(modified = maxOf(System.currentTimeMillis(),(local[record.id]?.modified ?: 0)+1))
        }
        mergeSync(restored)
        GoogleSync.schedule(context)
        return restored.size
    }
}
