package it.bilar.sentinel

import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import kotlin.math.roundToLong

object DemoTripModena {
    // Street geometry: © OpenStreetMap contributors, ODbL. Times are simulated.
    val points = listOf(
        44.6520641 to 10.9322852,
        44.6520337 to 10.9322408,
        44.6519659 to 10.9323520,
        44.6508194 to 10.9310639,
        44.6503545 to 10.9306207,
        44.6500047 to 10.9303258,
        44.6493770 to 10.9298475,
        44.6485330 to 10.9322858,
        44.6469291 to 10.9306005,
        44.6469543 to 10.9305287,
        44.6461903 to 10.9298238,
        44.6464622 to 10.9292883,
        44.6468495 to 10.9296044,
        44.6468694 to 10.9295659,
        44.6477929 to 10.9303268,
        44.6479080 to 10.9299924,
        44.6479451 to 10.9298147,
    ).mapIndexed { index, (latitude, longitude) ->
        TrackPoint(latitude, longitude, index * 30000L, 3f)
    }

    fun history(today: LocalDate, zone: ZoneId): List<TripSummary> {
        val dates = listOf(today, today.minusDays(1), today.minusDays(1)) + (1L..5L).flatMap { offset ->
            val month = YearMonth.from(today).minusMonths(offset)
            listOf(month.atDay(8), month.atDay(8), month.atDay(19))
        }
        return dates.mapIndexed { index, date ->
            val count = points.size - index % 4
            val start = date.atTime(8 + index % 3, 20).atZone(zone).toInstant().toEpochMilli()
            val end = start + (6 + index % 5) * 60000L
            TripSummary(-(index + 1L), start, end, end,
                points.take(count).zipWithNext().sumOf { (a, b) -> AutoTripDetector.distance(a, b) }, count, false)
        }.sortedByDescending { it.startedAt }
    }

    /** Simulated cruising speed for each street stretch (km/h), as if reported by the scooter. */
    private val stretchKmh = listOf(6.0, 9.0, 17.0, 19.0, 15.0, 21.0, 24.0, 12.0, 8.0, 16.0, 20.0, 13.0, 18.0, 23.0, 14.0, 10.0)

    /** Demo route sampled about every 2 s like a real recording, with time spread so each stretch is ridden at its simulated speed. */
    fun pointsFor(trip: TripSummary): List<TrackPoint> {
        val route = points.take(trip.pointCount)
        if (route.size < 2) return route.map { it.copy(time = trip.startedAt) }
        val stretches = route.zipWithNext().mapIndexed { i, (a, b) -> Triple(a, b, stretchKmh[i % stretchKmh.size]) }
        val idealSeconds = stretches.sumOf { (a, b, kmh) -> AutoTripDetector.distance(a, b) / (kmh / 3.6) }
        val scale = trip.durationMillis / 1000.0 / idealSeconds
        val result = mutableListOf<TrackPoint>()
        var elapsed = 0.0
        stretches.forEachIndexed { index, (a, b, kmh) ->
            val seconds = AutoTripDetector.distance(a, b) / (kmh / 3.6) * scale
            val steps = (seconds / 2).toInt().coerceAtLeast(1)
            val previousKmh = stretches.getOrNull(index - 1)?.third ?: 0.0
            for (step in (if (index == 0) 0 else 1)..steps) {
                val f = step.toDouble() / steps
                val ramp = (f * 3).coerceAtMost(1.0)
                val speed = (previousKmh + (kmh - previousKmh) * ramp) / scale
                result += TrackPoint(a.latitude + (b.latitude - a.latitude) * f, a.longitude + (b.longitude - a.longitude) * f,
                    trip.startedAt + ((elapsed + seconds * f) * 1000).roundToLong().coerceAtMost(trip.durationMillis), 3f, speedKmh = speed.toFloat())
            }
            elapsed += seconds
        }
        return result
    }
}
