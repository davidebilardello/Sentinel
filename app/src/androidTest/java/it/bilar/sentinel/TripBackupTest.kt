package it.bilar.sentinel

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.util.UUID
import javax.xml.parsers.DocumentBuilderFactory

@RunWith(AndroidJUnit4::class)
class TripBackupTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private fun completed(store: TripStore): Long {
        val points = listOf(TrackPoint(45.0,9.0,1000,4f),TrackPoint(45.001,9.0,2000,4f),TrackPoint(45.002,9.0,3000,4f,1))
        val id = store.begin(points,TripScooter("youfs:backupTest","YouFs <Test> & Scooter"))
        store.finish(id,4000)
        return id
    }
    @Test fun backupRoundTripPreservesVehicleAndSegmentsWithoutCredentialsOrDuplicates() {
        val firstName = "backup-one-${UUID.randomUUID()}.db"; val secondName = "backup-two-${UUID.randomUUID()}.db"
        val first = TripStore(context,firstName); val second = TripStore(context,secondName)
        try {
            val id = completed(first)
            val bytes = TripBackup.encode(first)
            val text = PortableArchive.unpack(bytes).toString(Charsets.UTF_8)
            assertFalse(text.contains("localKey")); assertFalse(text.contains("secKey")); assertFalse(text.contains("session"))
            val records = TripBackup.decode(bytes)
            assertEquals(1,second.restoreBackup(records)); assertEquals(0,second.restoreBackup(records))
            val restored = second.trips.value.single()
            assertEquals(first.trips.value.single().scooter,restored.scooter)
            assertEquals(first.points(id),second.points(restored.id))
            assertEquals(first.syncRecords().single().id,second.syncRecords().single().id)
            val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            val xml = factory.newDocumentBuilder().parse(ByteArrayInputStream(TripBackup.gpx(second)))
            assertEquals("http://www.topografix.com/GPX/1/1",xml.documentElement.namespaceURI)
            assertEquals(2,xml.getElementsByTagNameNS("*","trkseg").length)
            assertEquals(3,xml.getElementsByTagNameNS("*","trkpt").length)
            assertTrue(xml.getElementsByTagNameNS("*","name").item(0).textContent.contains("YouFs <Test> & Scooter"))
        } finally { first.close(); second.close(); context.deleteDatabase(firstName); context.deleteDatabase(secondName) }
    }
    @Test fun restoreRecoversDeletedTripsAndRejectsInvalidOrActiveImportsWithoutPartialWrites() {
        val name = "backup-restore-${UUID.randomUUID()}.db"; val store = TripStore(context,name)
        try {
            val id = completed(store); val records = TripBackup.decode(TripBackup.encode(store))
            store.delete(id); assertEquals(1,store.restoreBackup(records)); assertEquals(0,store.restoreBackup(records))
            val before = store.syncRecords().single()
            val invalid = records.single().copy(id = UUID.randomUUID().toString(),data = JSONObject(records.single().data.toString())
                .put("points",JSONArray().put(JSONArray().put(200).put(9).put(1000).put(4).put(0))))
            assertThrows(IllegalArgumentException::class.java) { store.restoreBackup(listOf(records.single(),invalid)) }
            assertEquals(before.id,store.syncRecords().single().id)
            val active = store.begin(listOf(TrackPoint(45.0,9.0,5000,4f),TrackPoint(45.001,9.0,6000,4f)))
            assertThrows(IllegalStateException::class.java) { store.restoreBackup(records) }
            assertEquals(1,TripBackup.decode(TripBackup.encode(store)).size)
            store.finish(active,7000)
            assertThrows(java.io.IOException::class.java) { TripBackup.decode(byteArrayOf(1,2,3)) }
        } finally { store.close(); context.deleteDatabase(name) }
    }
}
