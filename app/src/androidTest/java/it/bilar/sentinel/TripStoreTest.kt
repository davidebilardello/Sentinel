package it.bilar.sentinel

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TripStoreTest {
    @Test fun vehicleIsCapturedAtTripStartAndSurvivesRestart() {
        val first = SavedScooter("first","Sentinel",ScooterFamily.SENTINEL,syncId = "sentinel:00:11:22:33:44:55")
        val second = SavedScooter("second","YouFs",ScooterFamily.YOUFS_TUYA,syncId = "youfs:testVehicle")
        store.setConnectedScooter(first)
        val id = store.begin(listOf(point(0,1000),point(20,2000)))
        store.setConnectedScooter(second); store.finish(id,3000)
        store.close(); store = TripStore(context,databaseName); store.refresh()
        assertEquals(TripScooter(first.syncId!!,first.name),store.trips.value.single().scooter)
        assertEquals(first.syncId,store.syncRecords().single().data.getJSONObject("scooter").getString("id"))
    }

    @Test fun migratesVersionTwoHistoryWithoutInventingAVehicle() {
        store.close()
        val db = android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(databaseName),null)
        db.execSQL("CREATE TABLE trips (id INTEGER PRIMARY KEY AUTOINCREMENT, started_at INTEGER NOT NULL, ended_at INTEGER, last_point_at INTEGER NOT NULL, meters REAL NOT NULL, point_count INTEGER NOT NULL, interrupted INTEGER NOT NULL DEFAULT 0, sync_id TEXT, modified_at INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE TABLE points (id INTEGER PRIMARY KEY AUTOINCREMENT, trip_id INTEGER NOT NULL REFERENCES trips(id) ON DELETE CASCADE, latitude REAL NOT NULL, longitude REAL NOT NULL, time INTEGER NOT NULL, accuracy REAL NOT NULL, segment INTEGER NOT NULL)")
        db.execSQL("CREATE UNIQUE INDEX trips_sync_id ON trips(sync_id)")
        db.execSQL("CREATE TABLE sync_deleted (sync_id TEXT PRIMARY KEY, modified_at INTEGER NOT NULL)")
        val key = java.util.UUID.randomUUID().toString()
        db.execSQL("INSERT INTO trips VALUES(7,1000,3000,2000,20,2,0,?,1000)",arrayOf(key))
        db.execSQL("INSERT INTO points VALUES(1,7,45,9,1000,4,0),(2,7,45.001,9,2000,4,0)")
        db.version = 2; db.close()
        store = TripStore(context,databaseName); store.refresh()
        assertEquals(7L,store.trips.value.single().id); assertNull(store.trips.value.single().scooter)
        assertEquals(key,store.syncRecords().single().id); assertEquals(2,store.points(7).size)
    }
    private lateinit var context: Context
    private lateinit var store: TripStore
    private lateinit var databaseName: String
    private fun point(meters: Int, time: Long) = TrackPoint(45.0 + meters / 111195.0, 9.0, time, 4f)
    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        databaseName = "trip-test-${System.nanoTime()}.db"
        store = TripStore(context, databaseName)
    }
    @After fun cleanup() { store.close(); context.deleteDatabase(databaseName) }

    @Test fun persistsPointsDistanceAndHistoryAcrossReopening() {
        val id = store.begin(listOf(point(0, 1000), point(20, 5000)))
        store.append(id, point(40, 9000), 20.0)
        store.finish(id, 10000)
        store.close()
        store = TripStore(context, databaseName)
        store.refresh()
        val trip = store.trips.value.single()
        assertEquals(id, trip.id)
        assertEquals(40.0, trip.meters, 0.02)
        assertEquals(3, trip.pointCount)
        assertEquals(10000L, trip.endedAt)
        assertEquals(listOf(1000L, 5000L, 9000L), store.points(id).map { it.time })
    }

    @Test fun recoversInterruptedTripWithoutLosingTheRoute() {
        val id = store.begin(listOf(point(0, 1000), point(20, 5000)))
        store.close()
        store = TripStore(context, databaseName)
        store.recoverInterrupted()
        val trip = store.trips.value.single()
        assertTrue(trip.interrupted)
        assertEquals(5000L, trip.endedAt)
        assertEquals(2, store.points(id).size)
    }

    @Test fun failedWritesRollBackAndCannotDeleteAnActiveTrip() {
        assertThrows(IllegalArgumentException::class.java) { store.begin(listOf(point(0, 1000), point(20, 5000).copy(latitude = 200.0))) }
        store.refresh()
        assertTrue(store.trips.value.isEmpty())
        val id = store.begin(listOf(point(0, 1000), point(20, 5000)))
        assertThrows(IllegalArgumentException::class.java) { store.append(id, point(40, 9000).copy(longitude = Double.NaN), 20.0) }
        assertEquals(2, store.points(id).size)
        assertThrows(IllegalStateException::class.java) { store.delete(id) }
        store.finish(id, 6000)
        store.delete(id)
        assertTrue(store.trips.value.isEmpty())
        assertTrue(store.points(id).isEmpty())
    }

    @Test fun migratesVersionOneHistoryWithoutChangingIdsOrRoutes() {
        store.close()
        val db = android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(databaseName),null)
        db.execSQL("CREATE TABLE trips (id INTEGER PRIMARY KEY AUTOINCREMENT, started_at INTEGER NOT NULL, ended_at INTEGER, last_point_at INTEGER NOT NULL, meters REAL NOT NULL, point_count INTEGER NOT NULL, interrupted INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE TABLE points (id INTEGER PRIMARY KEY AUTOINCREMENT, trip_id INTEGER NOT NULL REFERENCES trips(id) ON DELETE CASCADE, latitude REAL NOT NULL, longitude REAL NOT NULL, time INTEGER NOT NULL, accuracy REAL NOT NULL, segment INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX points_trip ON points(trip_id,id)")
        db.execSQL("INSERT INTO trips VALUES(7,1000,3000,2000,20,2,0)")
        db.execSQL("INSERT INTO points VALUES(1,7,45,9,1000,4,0),(2,7,45.001,9,2000,4,0)")
        db.version = 1; db.close()
        store = TripStore(context,databaseName); store.refresh()
        assertEquals(7L,store.trips.value.single().id); assertEquals(2,store.points(7).size)
        val key = store.syncRecords().single().id
        store.close(); store = TripStore(context,databaseName)
        assertEquals(key,store.syncRecords().single().id)
    }
    @Test fun rejectsMalformedRemoteRoutesWithoutChangingHistory() {
        val id = store.begin(listOf(point(0,1000),point(20,2000))); store.finish(id,3000)
        val record = store.syncRecords().single()
        val invalid = record.copy(id = java.util.UUID.randomUUID().toString(),modified = record.modified+1,
            data = org.json.JSONObject(record.data.toString()).put("points",org.json.JSONArray().put(org.json.JSONArray().put(200.0).put(9.0).put(1000).put(4).put(0))))
        assertThrows(IllegalArgumentException::class.java) { store.mergeSync(listOf(invalid)) }
        assertEquals(1,store.trips.value.size); assertEquals(2,store.points(id).size)
    }
    @Test fun deletingARemoteTripCreatesANewerTombstoneDespiteClockSkew() {
        val id = store.begin(listOf(point(0,1000),point(20,2000))); store.finish(id,3000)
        val future = store.syncRecords().single().let { it.copy(modified = it.modified+1000) }
        store.mergeSync(listOf(future)); store.delete(id)
        val removed = store.syncRecords().single()
        assertTrue(removed.deleted); assertTrue(removed.modified > future.modified)
        store.mergeSync(listOf(future))
        assertTrue(store.trips.value.isEmpty())
    }
}
