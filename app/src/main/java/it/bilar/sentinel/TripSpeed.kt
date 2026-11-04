package it.bilar.sentinel

import kotlin.math.max
import kotlin.math.roundToInt

enum class SpeedSource { SCOOTER, GPS }

/** Speed for every point of a route, aligned with the point list (null = unknown). */
data class TripSpeedProfile(val source: SpeedSource, val pointSpeeds: List<Double?>, val maxKmh: Double, val movingAverageKmh: Double?)

object TripSpeed {
    const val MAX_PLAUSIBLE_KMH = 120.0
    /** Below this speed the scooter is considered stopped (traffic lights, pauses). */
    const val MOVING_KMH = 3.0
    /** Two points further apart than this in time are a signal gap, not a ride. */
    const val GAP_MS = 30_000L

    /**
     * Speed → colour stops shared by the map and the chart. One perceptual ramp,
     * from a calm blue (slow) to a bright yellow (fast): lightness grows with speed,
     * so it stays readable on the dark map and for colour-blind riders.
     */
    val stops: List<Pair<Double, Int>> = listOf(
        0.0 to 0xFF3B6FD8.toInt(),
        8.0 to 0xFF8E5BD9.toInt(),
        14.0 to 0xFFE0508A.toInt(),
        20.0 to 0xFFFF8A3D.toInt(),
        25.0 to 0xFFFFE45C.toInt(),
    )

    fun color(kmh: Double): Int {
        val value = kmh.takeIf { it.isFinite() }?.coerceAtLeast(0.0) ?: 0.0
        if (value >= stops.last().first) return stops.last().second
        val upper = stops.indexOfFirst { it.first > value }.coerceAtLeast(1)
        val (s0, c0) = stops[upper - 1]; val (s1, c1) = stops[upper]
        val t = ((value - s0) / (s1 - s0)).coerceIn(0.0, 1.0)
        fun channel(shift: Int) = (((c0 shr shift) and 255) + (((c1 shr shift) and 255) - ((c0 shr shift) and 255)) * t).roundToInt()
        return (0xFF shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }

    fun hex(kmh: Double) = "#%06X".format(color(kmh) and 0xFFFFFF)

    /** True when two consecutive points belong to the same continuous stretch of road. */
    fun connected(a: TrackPoint, b: TrackPoint) = a.segment == b.segment && b.time - a.time in 0..GAP_MS

    fun profile(points: List<TrackPoint>): TripSpeedProfile? {
        if (points.size < 2) return null
        val measured = points.map { p -> p.speedKmh?.toDouble()?.takeIf { it.isFinite() && it in 0.0..MAX_PLAUSIBLE_KMH } }
        val fromScooter = measured.count { it != null } * 2 >= points.size
        val speeds = if (fromScooter) measured else gpsSpeeds(points)
        if (speeds.all { it == null }) return null
        return TripSpeedProfile(if (fromScooter) SpeedSource.SCOOTER else SpeedSource.GPS, speeds,
            speeds.filterNotNull().max(), movingAverage(points, speeds))
    }

    /** Speed of a route stretch between two points, for colouring the line. */
    fun between(a: Double?, b: Double?): Double? = when {
        a != null && b != null -> (a + b) / 2
        else -> a ?: b
    }

    /** Time-weighted average while actually moving: stops at traffic lights do not lower it. */
    private fun movingAverage(points: List<TrackPoint>, speeds: List<Double?>): Double? {
        var weighted = 0.0; var millis = 0L
        for (i in 1 until points.size) {
            val a = points[i - 1]; val b = points[i]
            if (!connected(a, b)) continue
            val kmh = between(speeds[i - 1], speeds[i]) ?: continue
            if (kmh < MOVING_KMH) continue
            val dt = b.time - a.time
            weighted += kmh * dt; millis += dt
        }
        return if (millis > 0) weighted / millis else null
    }

    /**
     * Fallback for trips recorded without scooter data: distance over time around each point,
     * then a light 3-point smoothing so GPS jitter does not paint false spikes.
     */
    private fun gpsSpeeds(points: List<TrackPoint>): List<Double?> {
        fun stretch(a: Int, b: Int): Double? {
            val dt = points[b].time - points[a].time
            if (dt <= 0) return null
            return (AutoTripDetector.distance(points[a], points[b]) / (dt / 1000.0) * 3.6).takeIf { it.isFinite() && it <= MAX_PLAUSIBLE_KMH }
        }
        val raw = points.indices.map { i ->
            val previous = (i - 1).takeIf { it >= 0 && connected(points[it], points[i]) }
            val next = (i + 1).takeIf { it < points.size && connected(points[i], points[it]) }
            when {
                previous != null && next != null -> {
                    val dt = points[next].time - points[previous].time
                    if (dt <= 0) null
                    else ((AutoTripDetector.distance(points[previous], points[i]) + AutoTripDetector.distance(points[i], points[next])) /
                        (dt / 1000.0) * 3.6).takeIf { it.isFinite() && it <= MAX_PLAUSIBLE_KMH }
                }
                previous != null -> stretch(previous, i)
                next != null -> stretch(i, next)
                else -> null
            }
        }
        return raw.indices.map { i ->
            val window = (max(0, i - 1)..minOf(raw.lastIndex, i + 1))
                .filter { it == i || connected(points[minOf(it, i)], points[max(it, i)]) }
                .mapNotNull { raw[it] }
            if (raw[i] == null || window.isEmpty()) raw[i] else window.average()
        }
    }
}
