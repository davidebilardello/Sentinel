package it.bilar.sentinel

import kotlin.math.*

data class GpsFix(val latitude: Double, val longitude: Double, val time: Long, val elapsed: Long,
    val accuracy: Float, val speedMps: Float?, val speedAccuracy: Float? = null, val scooterKmh: Float? = null)
/** [speedKmh] is the speed reported by the scooter when the point was recorded, null when unavailable. */
data class TrackPoint(val latitude: Double, val longitude: Double, val time: Long, val accuracy: Float, val segment: Int = 0, val speedKmh: Float? = null)
sealed interface TripEvent {
    data class Started(val points: List<TrackPoint>) : TripEvent
    data class Point(val point: TrackPoint, val meters: Double) : TripEvent
    data class Finished(val endedAt: Long) : TripEvent
}

class AutoTripDetector {
    companion object {
        const val START_SPEED_MPS = 1.4f
        const val START_TIME_MS = 8000L
        const val START_METERS = 15.0
        const val STOP_TIME_MS = 120000L
        const val GAP_MS = 30000L
        const val MAX_ACCURACY_METERS = 20f
        const val MAX_FIX_AGE_MS = 5000L
        fun distance(a: TrackPoint, b: TrackPoint): Double {
            val lat = Math.toRadians(b.latitude - a.latitude)
            val lon = Math.toRadians(b.longitude - a.longitude)
            val h = sin(lat / 2).pow(2) + cos(Math.toRadians(a.latitude)) * cos(Math.toRadians(b.latitude)) * sin(lon / 2).pow(2)
            return 6371000.0 * 2 * asin(sqrt(h.coerceIn(0.0, 1.0)))
        }
    }
    var active = false
        private set
    private var previous: GpsFix? = null
    private val positionWindow = mutableListOf<GpsFix>()
    private val candidate = mutableListOf<TrackPoint>()
    private var candidateSince = 0L
    private var lastMovement = 0L
    private var lastMovingTime = 0L
    private var lastPoint: TrackPoint? = null
    private var segment = 0
    private var signalGap = false

    fun accept(fix: GpsFix, now: Long): List<TripEvent> {
        if (!fix.latitude.isFinite() || !fix.longitude.isFinite() || fix.latitude !in -90.0..90.0 || fix.longitude !in -180.0..180.0 ||
            !fix.accuracy.isFinite() || fix.accuracy !in 0f..MAX_ACCURACY_METERS || now - fix.elapsed !in 0..MAX_FIX_AGE_MS) return emptyList()
        val prev = previous
        if (prev != null && fix.elapsed <= prev.elapsed) return emptyList()
        val point = TrackPoint(fix.latitude, fix.longitude, fix.time, fix.accuracy, segment, fix.scooterKmh)
        val delta = prev?.let { distance(TrackPoint(it.latitude, it.longitude, it.time, it.accuracy), point) } ?: 0.0
        val dt = prev?.let { (fix.elapsed - it.elapsed) / 1000.0 } ?: 0.0
        if (dt > 0 && dt <= 30 && delta / dt > 22 && delta > max(40.0, (fix.accuracy + (prev?.accuracy ?: 0f)).toDouble())) return emptyList()
        if (fix.speedMps != null && (!fix.speedMps.isFinite() || fix.speedMps !in 0f..22f)) return emptyList()
        if (prev?.speedMps != null && fix.speedMps != null && dt in 0.5..5.0 &&
            (prev.speedAccuracy ?: 0f) <= 1f && (fix.speedAccuracy ?: 0f) <= 1f) {
            val expected = (prev.speedMps + fix.speedMps) * .5 * dt
            if (delta > expected * 2 + max(8.0, (prev.accuracy + fix.accuracy) * .75)) return emptyList()
        }
        previous = fix
        if (active && dt > 30) signalGap = true
        if (!active && dt > 30) { candidate.clear(); candidateSince = 0 }
        positionWindow += fix
        while (positionWindow.size > 1 && fix.elapsed - positionWindow.first().elapsed > 10000) positionWindow.removeAt(0)
        val anchor = positionWindow.first()
        val windowSeconds = (fix.elapsed - anchor.elapsed) / 1000.0
        val windowMeters = distance(TrackPoint(anchor.latitude, anchor.longitude, anchor.time, anchor.accuracy), point)
        val reliableSpeed = fix.speedMps != null && (fix.speedAccuracy == null || fix.speedAccuracy.isFinite() && fix.speedAccuracy in 0f..1f)
        val movingThreshold = if (active) .5f + (fix.speedAccuracy ?: 0f).coerceIn(0f, .5f) else START_SPEED_MPS
        val moving = if (reliableSpeed) fix.speedMps!! >= movingThreshold
            else windowSeconds in 4.0..10.0 && windowMeters > max(8.0, (anchor.accuracy + fix.accuracy).toDouble()) && windowMeters / windowSeconds >= START_SPEED_MPS
        if (!moving) {
            if (!active) { candidate.clear(); candidateSince = 0 }
            return tick(now)
        }
        lastMovement = fix.elapsed
        lastMovingTime = fix.time
        if (!active) {
            if (candidate.isEmpty()) {
                candidateSince = fix.elapsed
                val seed = if (reliableSpeed) prev else anchor
                if (seed != null && fix.elapsed - seed.elapsed < 10000) candidate += TrackPoint(seed.latitude, seed.longitude, seed.time, seed.accuracy, speedKmh = seed.scooterKmh)
            }
            if (candidate.isEmpty() || distance(candidate.last(), point) >= 2) candidate += point
            if (fix.elapsed - candidateSince >= START_TIME_MS && distance(candidate.first(), point) >= START_METERS) {
                active = true
                lastPoint = candidate.last()
                val initial = candidate.toList()
                candidate.clear()
                return listOf(TripEvent.Started(initial))
            }
            return emptyList()
        }
        val last = lastPoint ?: return emptyList()
        val gap = signalGap
        if (gap) segment++
        val next = point.copy(segment = segment)
        val meters = if (gap) 0.0 else distance(last, next)
        if (!gap && meters < max(1.5, ((fix.accuracy + last.accuracy) * 0.15).toDouble())) return emptyList()
        lastPoint = next
        signalGap = false
        return listOf(TripEvent.Point(next, meters))
    }

    fun tick(now: Long): List<TripEvent> = if (active && now - lastMovement >= STOP_TIME_MS) finish() else emptyList()

    fun finish(): List<TripEvent> {
        val event = if (active) listOf(TripEvent.Finished(lastMovingTime)) else emptyList()
        active = false; previous = null; positionWindow.clear(); candidate.clear(); candidateSince = 0; lastPoint = null; segment = 0; signalGap = false
        return event
    }
}
