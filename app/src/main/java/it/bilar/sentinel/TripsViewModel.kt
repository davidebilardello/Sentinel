package it.bilar.sentinel

import android.Manifest
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class TripsViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as SentinelApplication
    private val store = app.trips
    val trips = store.trips
    val status = store.status.asStateFlow()
    val automatic = store.enabled.asStateFlow()
    val permission = MutableStateFlow(TripTrackingService.hasPermission(app))
    val selectedId = MutableStateFlow<Long?>(null)
    val route = MutableStateFlow<List<TrackPoint>>(emptyList())
    val error = MutableStateFlow<String?>(null)

    init {
        viewModelScope.launch(app.tripDispatcher) { try { store.refresh() } catch (e: Exception) { error.value = e.message } }
    }
    fun permissions(): Array<String> = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION) +
        if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.POST_NOTIFICATIONS) else emptyArray()
    fun shouldAskPermission() = automatic.value && !permission.value && !store.permissionAsked
    fun markPermissionAsked() { store.permissionAsked = true }
    fun refreshPermission() { permission.value = TripTrackingService.hasPermission(app) }

    fun synchronize() {
        refreshPermission()
        if (!automatic.value || !permission.value) {
            app.stopService(Intent(app, TripTrackingService::class.java))
            return
        }
        if (store.scooterConnected.value) {
            try { TripTrackingService.start(app) }
            catch (e: Exception) { error.value = appText(R.string.message_77,e.message .orEmpty()) }
        }
    }
    fun setAutomatic(value: Boolean) { store.setAutomatic(value); synchronize() }
    fun select(id: Long) {
        if (selectedId.value != id) route.value = emptyList()
        selectedId.value = id
        loadRoute()
    }
    private fun loadRoute() {
        val id = selectedId.value
        viewModelScope.launch(app.tripDispatcher) {
            try {
                val points = if (id != null) store.points(id) else emptyList()
                if (selectedId.value == id) route.value = points
            } catch (e: Exception) { error.value = appText(R.string.message_78,e.message .orEmpty()) }
        }
    }
    fun delete(id: Long) {
        viewModelScope.launch(app.tripDispatcher) {
            try { store.delete(id) } catch (e: Exception) { error.value = e.message }
        }
    }
}
