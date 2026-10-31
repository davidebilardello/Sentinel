package it.bilar.sentinel

import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

fun TripSummary.localDate(zone: ZoneId): LocalDate = Instant.ofEpochMilli(startedAt).atZone(zone).toLocalDate()

data class TripStatistics(val trips: List<TripSummary>, val meters: Double, val durationMillis: Long,
    val activeDays: Int, val averageKmh: Double?, val longestMeters: Double) {
    val averageMeters get() = if (trips.isEmpty()) 0.0 else meters / trips.size
}

fun tripStatistics(history: List<TripSummary>, month: YearMonth, zone: ZoneId): TripStatistics {
    val trips = history.filter { YearMonth.from(it.localDate(zone)) == month }.sortedByDescending { it.startedAt }
    val timed = trips.filter { it.durationMillis > 0 }
    val duration = timed.sumOf { it.durationMillis }
    return TripStatistics(trips, trips.sumOf { it.meters }, duration,
        trips.map { it.localDate(zone) }.distinct().size,
        if (duration > 0) timed.sumOf { it.meters } * 3600.0 / duration else null,
        trips.maxOfOrNull { it.meters } ?: 0.0)
}

fun tripsByDay(trips: List<TripSummary>, zone: ZoneId): Map<LocalDate, List<TripSummary>> =
    trips.groupBy { it.localDate(zone) }.toSortedMap(compareByDescending { it })
