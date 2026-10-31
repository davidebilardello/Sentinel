package it.bilar.sentinel

import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class TripStatisticsTest {
    private val rome = ZoneId.of("Europe/Rome")
    private fun trip(id: Long, start: String, meters: Double, duration: Long = 600000) =
        Instant.parse(start).toEpochMilli().let { TripSummary(id, it, it + duration, it + duration, meters, 4, false) }

    @Test fun monthlyTotalsAverageAndDaysUseOnlyTheSelectedMonth() {
        val history = listOf(trip(1, "2026-10-12T08:00:00Z", 2000.0), trip(2, "2026-10-12T17:00:00Z", 1000.0, 300000),
            trip(3, "2026-10-15T08:00:00Z", 3000.0), trip(4, "2026-09-12T08:00:00Z", 9000.0))
        val result = tripStatistics(history, YearMonth.of(2026, 10), rome)
        assertEquals(3, result.trips.size)
        assertEquals(6000.0, result.meters, .001)
        assertEquals(1500000L, result.durationMillis)
        assertEquals(2, result.activeDays)
        assertEquals(2000.0, result.averageMeters, .001)
        assertEquals(14.4, result.averageKmh!!, .001)
        assertEquals(3000.0, result.longestMeters, .001)
    }

    @Test fun monthBoundariesFollowThePhoneTimezoneAndIncludePreviousYear() {
        val history = listOf(trip(1, "2025-12-31T23:30:00Z", 1000.0), trip(2, "2025-12-31T22:30:00Z", 2000.0))
        assertEquals(listOf(1L), tripStatistics(history, YearMonth.of(2026, 1), rome).trips.map { it.id })
        assertEquals(listOf(2L), tripStatistics(history, YearMonth.of(2025, 12), rome).trips.map { it.id })
    }

    @Test fun emptyMonthsAndZeroDurationNeverInventASpeed() {
        val empty = tripStatistics(emptyList(), YearMonth.of(2026, 10), rome)
        assertEquals(0.0, empty.averageMeters, .001)
        assertNull(empty.averageKmh)
        val noTime = tripStatistics(listOf(trip(1, "2026-10-12T08:00:00Z", 2000.0, 0)), YearMonth.of(2026, 10), rome)
        assertNull(noTime.averageKmh)
        assertEquals(2000.0, noTime.meters, .001)
    }

    @Test fun dayGroupsStayInDescendingOrderAcrossTheDaylightSavingChange() {
        val history = listOf(trip(1, "2026-10-25T00:30:00Z", 1000.0), trip(2, "2026-10-25T01:30:00Z", 1000.0), trip(3, "2026-10-26T08:00:00Z", 1000.0))
        val days = tripsByDay(history, rome)
        assertEquals(listOf(LocalDate.of(2026, 10, 26), LocalDate.of(2026, 10, 25)), days.keys.toList())
        assertEquals(2, days[LocalDate.of(2026, 10, 25)]!!.size)
    }

    @Test fun demoCoversSixMonthsWithSeparateIdsAndMatchingTrackDistances() {
        val history = DemoTripModena.history(LocalDate.of(2026, 1, 1), rome)
        assertEquals(18, history.size)
        assertEquals(6, history.map { YearMonth.from(it.localDate(rome)) }.distinct().size)
        assertEquals(history.size, history.map { it.id }.distinct().size)
        assertTrue(history.all { it.id < 0 })
        history.forEach { trip ->
            val points = DemoTripModena.pointsFor(trip)
            assertEquals(trip.startedAt, points.first().time)
            assertEquals(trip.endedAt, points.last().time)
            assertEquals(trip.meters, points.zipWithNext().sumOf { (a, b) -> AutoTripDetector.distance(a, b) }, .001)
        }
    }
}
