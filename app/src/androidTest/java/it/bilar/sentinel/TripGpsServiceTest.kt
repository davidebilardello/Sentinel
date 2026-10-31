package it.bilar.sentinel

import android.Manifest
import android.content.Intent
import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TripGpsServiceTest {
    private var stateDescription: () -> String = { "" }
    private fun shell(command: String) {
        ParcelFileDescriptor.AutoCloseInputStream(InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)).use { it.readBytes() }
    }
    private fun waitUntil(timeout: Long = 10000, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeout
        while (!predicate() && SystemClock.elapsedRealtime() < deadline) Thread.sleep(100)
        assertTrue("Timeout waiting for GPS service state: ${stateDescription()}", predicate())
    }

    @Suppress("DEPRECATION")
    @Test fun automaticGpsTripPersistsWhileTheActivityIsInBackground() {
        // Never replace a physical phone's GPS provider or record its real position in this test.
        assumeTrue(Build.PRODUCT.contains("sdk") || Build.FINGERPRINT.contains("generic"))
        val app = ApplicationProvider.getApplicationContext<SentinelApplication>()
        val statuses = java.util.Collections.synchronizedList(mutableListOf<String>())
        val statusObserver = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined).launch {
            app.trips.status.collect { statuses += it.toString() }
        }
        stateDescription = { "connected=${app.trips.scooterConnected.value}, states=${statuses.toList().takeLast(12)}, trips=${app.trips.trips.value.size}" }
        val manager = app.getSystemService(LocationManager::class.java)
        val previousAutomatic = app.trips.enabled.value
        val permissions = listOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION) +
            if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()
        permissions.forEach { shell("pm grant it.bilar.sentinel $it") }
        shell("appops set it.bilar.sentinel android:mock_location allow")
        manager.addTestProvider(LocationManager.GPS_PROVIDER, false, false, false, false, true, true, true, Criteria.POWER_LOW, Criteria.ACCURACY_FINE)
        manager.setTestProviderEnabled(LocationManager.GPS_PROVIDER, true)
        val before = app.trips.trips.value.map { it.id }.toSet()
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            scenario.onActivity {
                androidx.lifecycle.ViewModelProvider(it)[ScooterViewModel::class.java].startDemo()
                app.trips.setAutomatic(true)
                app.trips.setConnectedScooter(SavedScooter("gps-jeep","GPS test Jeep",ScooterFamily.SENTINEL,syncId = "sentinel:00:11:22:33:44:55"))
                TripTrackingService.start(it)
            }
            waitUntil { app.trips.status.value.monitoring }
            shell("input keyevent KEYCODE_HOME")
            for (index in 0..7) {
                val location = Location(LocationManager.GPS_PROVIDER).apply {
                    latitude = 45.4869 + index * 8.8 / 111195.0
                    longitude = 9.2032
                    time = System.currentTimeMillis()
                    elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                    accuracy = 3f
                    speed = 4f
                    speedAccuracyMetersPerSecond = 0.1f
                }
                manager.setTestProviderLocation(LocationManager.GPS_PROVIDER, location)
                Thread.sleep(2200)
            }
            waitUntil { app.trips.trips.value.any { it.id !in before && it.endedAt == null && it.meters > 30 } }
            val trip = app.trips.trips.value.first { it.id !in before }
            assertTrue(app.trips.points(trip.id).size >= 4)
            assertTrue(app.trips.status.value.recording)
            assertEquals("GPS test Jeep",trip.scooter?.name)
            app.trips.setConnectedScooter(SavedScooter("gps-youfs","GPS test YouFs",ScooterFamily.YOUFS_TUYA,syncId = "youfs:gpsTest"))
            waitUntil { app.trips.trips.value.first { it.id == trip.id }.endedAt != null && !app.trips.status.value.recording }
            assertFalse(app.trips.status.value.recording)
            for(index in 0..7) {
                val location = Location(LocationManager.GPS_PROVIDER).apply {
                    latitude = 45.4879 + index * 8.8 / 111195.0; longitude = 9.2032
                    time = System.currentTimeMillis(); elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                    accuracy = 3f; speed = 4f; speedAccuracyMetersPerSecond = .1f
                }
                manager.setTestProviderLocation(LocationManager.GPS_PROVIDER,location); Thread.sleep(2200)
            }
            waitUntil { app.trips.trips.value.any { it.id !in before && it.id != trip.id && it.endedAt == null } }
            val next = app.trips.trips.value.first { it.id !in before && it.id != trip.id }
            assertEquals("GPS test YouFs",next.scooter?.name)
            app.trips.setConnectedScooter(null)
            app.stopService(Intent(app, TripTrackingService::class.java))
            waitUntil { app.trips.trips.value.first { it.id == next.id }.endedAt != null }
            assertTrue(app.trips.points(trip.id).size >= 4)
            // The synthetic emulator trip remains available for map/UI verification.
        } finally {
            app.trips.setConnectedScooter(null)
            app.stopService(Intent(app, TripTrackingService::class.java))
            app.trips.setAutomatic(previousAutomatic)
            scenario.close()
            manager.removeTestProvider(LocationManager.GPS_PROVIDER)
            shell("appops set it.bilar.sentinel android:mock_location default")
            statusObserver.cancel()
        }
    }
}
