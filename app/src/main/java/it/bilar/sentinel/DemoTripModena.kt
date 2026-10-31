package it.bilar.sentinel

import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

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

    fun pointsFor(trip: TripSummary): List<TrackPoint> = points.take(trip.pointCount).mapIndexed { index, point ->
        point.copy(time = trip.startedAt + trip.durationMillis * index / (trip.pointCount - 1))
    }
}
