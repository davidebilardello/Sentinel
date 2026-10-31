package it.bilar.sentinel

import android.Manifest
import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.LocationRequest
import android.os.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

class TripTrackingService : Service(), LocationListener {
    companion object {
        private const val CHANNEL = "automatic_trips"
        private const val NOTIFICATION_ID = 21
        fun hasPermission(context: Context) = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        fun start(context: Context) { context.startForegroundService(Intent(context, TripTrackingService::class.java)) }
    }
    private val app get() = application as SentinelApplication
    private val store get() = app.trips
    private val manager get() = getSystemService(LocationManager::class.java)
    private lateinit var scope: CoroutineScope
    private val detector = AutoTripDetector()
    private var tripId: Long? = null
    private var monitoredScooterId: String? = null
    private var lastDisconnected = 0L
    private var lastFix = 0L
    private var lastNotification = 0L
    private var lastAccuracy: Float? = null
    private var shuttingDown = false

    override fun onBind(intent: Intent?) = null

    @SuppressLint("MissingPermission")
    override fun onCreate() {
        super.onCreate()
        scope = CoroutineScope(SupervisorJob() + app.tripDispatcher + CoroutineExceptionHandler { _, error ->
            store.status.value = RecordingStatus(message = appText(R.string.message_79,error.message .orEmpty()))
            stopSelf()
        })
        val notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel(CHANNEL, appText(R.string.automatic_trips), NotificationManager.IMPORTANCE_LOW))
        if (!hasPermission(this) || !store.enabled.value || !store.scooterConnected.value) { stopSelf(); return }
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, notification(appText(R.string.automatic_movement_detection)), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            else startForeground(NOTIFICATION_ID, notification(appText(R.string.automatic_movement_detection)))
            if (Build.VERSION.SDK_INT >= 31) {
                val request = LocationRequest.Builder(1000L).setQuality(LocationRequest.QUALITY_HIGH_ACCURACY)
                    .setMinUpdateIntervalMillis(1000L).setMinUpdateDistanceMeters(0f).setMaxUpdateDelayMillis(0L).build()
                manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, request, mainExecutor, this)
            } else manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this, Looper.getMainLooper())
            updateStatus(appText(R.string.waiting_for_an_accurate_gps_signal_your_trip_will_start_automatically))
            scope.launch {
                while (isActive) {
                    delay(1000)
                    if (shuttingDown) break
                    val now = SystemClock.elapsedRealtime()
                    if (!store.enabled.value || !hasPermission(this@TripTrackingService)) { stopSelf(); break }
                    if (!store.scooterConnected.value) {
                        if (lastDisconnected == 0L) lastDisconnected = now
                        updateStatus(appText(R.string.scooter_disconnected_waiting_to_reconnect))
                        if (now - lastDisconnected >= 30000) { stopSelf(); break }
                    } else {
                        lastDisconnected = 0
                        process(detector.tick(now))
                        if (!manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) updateStatus(appText(R.string.gps_is_off_enable_location_on_your_phone))
                        else if (lastFix > 0 && now - lastFix > 15000) updateStatus(appText(R.string.weak_gps_signal_inaccurate_points_are_not_saved))
                    }
                }
            }
            scope.launch { store.enabled.collectLatest { if (!it) stopSelf() } }
            scope.launch { store.connectedScooter.collectLatest { finishIfScooterChanged() } }
        } catch (e: Exception) {
            updateStatus(appText(R.string.message_80,e.message .orEmpty()))
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_NOT_STICKY

    override fun onLocationChanged(location: Location) {
        if (shuttingDown || !store.scooterConnected.value || !store.enabled.value) return
        val fix = GpsFix(location.latitude, location.longitude, location.time, location.elapsedRealtimeNanos / 1000000,
            if (location.hasAccuracy()) location.accuracy else Float.POSITIVE_INFINITY,
            if (location.hasSpeed()) location.speed else null,
            if (location.hasSpeedAccuracy()) location.speedAccuracyMetersPerSecond else null)
        scope.launch {
            if (shuttingDown) return@launch
            try {
                val now = SystemClock.elapsedRealtime()
                finishIfScooterChanged()
                if (!fix.accuracy.isFinite() || fix.accuracy !in 0f..AutoTripDetector.MAX_ACCURACY_METERS || now - fix.elapsed !in 0..AutoTripDetector.MAX_FIX_AGE_MS) {
                    lastAccuracy = null
                    updateStatus(appText(R.string.weak_gps_signal_inaccurate_points_are_not_saved))
                    return@launch
                }
                val vehicle = store.connectedScooter.value
                val events = detector.accept(fix, now)
                val currentVehicle = store.connectedScooter.value
                if(currentVehicle != null && currentVehicle.id != vehicle?.id) {
                    finishIfScooterChanged()
                    return@launch
                }
                process(events,vehicle)
                lastFix = now
                lastAccuracy = fix.accuracy
                if (manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) updateStatus(if (detector.active)
                    appText(R.string.recording_trip_saved_locally_and_automatically)
                    else appText(R.string.gps_ready_your_trip_starts_after_about_15_metres_of_movement))
            } catch (e: Exception) {
                store.status.value = RecordingStatus(true, detector.active, appText(R.string.message_81,e.message .orEmpty()))
                stopSelf()
            }
        }
    }

    private fun finishIfScooterChanged() {
        val current = store.connectedScooter.value
        if(current != null && current.id != monitoredScooterId) {
            process(detector.finish())
            monitoredScooterId = current.id
            lastAccuracy = null
            updateStatus(appText(R.string.waiting_for_an_accurate_gps_signal_your_trip_will_start_automatically))
        }
    }

    private fun process(events: List<TripEvent>, scooter: TripScooter? = store.connectedScooter.value) {
        for (event in events) when (event) {
            is TripEvent.Started -> {
                tripId = store.begin(event.points,scooter)
            }
            is TripEvent.Point -> { tripId?.let { store.append(it, event.point, event.meters) } }
            is TripEvent.Finished -> {
                tripId?.let { store.finish(it, event.endedAt) }
                tripId = null
            }
        }
    }

    private fun updateStatus(message: String) {
        store.status.value = RecordingStatus(true, detector.active, message, lastAccuracy)
        val now = SystemClock.elapsedRealtime()
        if (now - lastNotification > 10000) {
            lastNotification = now
            val active = store.trips.value.firstOrNull { it.id == tripId }
            val text = if (active != null) appText(R.string.recording_trip_2f_km, active.meters / 1000) else message
            if (Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
        }
    }

    private fun notification(text: String): Notification {
        val launch = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_trip_notification).setContentTitle(appText(R.string.sentinel_automatic_trips))
            .setContentText(text).setContentIntent(launch).setOngoing(true).setOnlyAlertOnce(true).setCategory(Notification.CATEGORY_SERVICE).build()
    }

    override fun onProviderDisabled(provider: String) { if (provider == LocationManager.GPS_PROVIDER) updateStatus(appText(R.string.gps_is_off_enable_location_on_your_phone)) }
    override fun onProviderEnabled(provider: String) { if (provider == LocationManager.GPS_PROVIDER) updateStatus(appText(R.string.gps_enabled_waiting_for_an_accurate_position)) }
    @Deprecated("Legacy Android callback")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) { }

    override fun onDestroy() {
        shuttingDown = true
        try { manager.removeUpdates(this) } catch (_: SecurityException) { }
        scope.launch {
            var failure: String? = null
            try { process(detector.finish()) }
            catch (e: Exception) { failure = appText(R.string.message_82,e.message .orEmpty()) }
            finally {
                store.status.value = RecordingStatus(message = failure ?: if (store.enabled.value) appText(R.string.connect_your_scooter_to_record_trips_automatically) else appText(R.string.automatic_recording_disabled))
                scope.cancel()
            }
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
