package it.bilar.sentinel

import org.junit.Assert.*
import org.junit.Test

class TripSpeedTest {
    /** A point [meters] north of the origin at [seconds]. */
    private fun point(meters: Double, seconds: Long, kmh: Float? = null, segment: Int = 0) =
        TrackPoint(45.0 + meters / 111195.0, 9.0, 1_000_000 + seconds * 1000, 4f, segment, kmh)

    @Test fun scooterSpeedsAreUsedAsRecorded() {
        val profile = TripSpeed.profile(listOf(point(0.0, 0, 10f), point(5.0, 1, 20f), point(10.0, 2, 30f)))!!
        assertEquals(SpeedSource.SCOOTER, profile.source)
        assertEquals(listOf(10.0, 20.0, 30.0), profile.pointSpeeds)
        assertEquals(30.0, profile.maxKmh, .001)
        assertEquals(20.0, profile.movingAverageKmh!!, .001)
    }

    @Test fun oldTripsFallBackToGpsSpeed() {
        // 5 m every second = 18 km/h
        val profile = TripSpeed.profile((0..5).map { point(it * 5.0, it.toLong()) })!!
        assertEquals(SpeedSource.GPS, profile.source)
        profile.pointSpeeds.forEach { assertEquals(18.0, it!!, .2) }
        assertEquals(18.0, profile.movingAverageKmh!!, .2)
    }

    @Test fun stopsAndSignalGapsDoNotLowerTheMovingAverage() {
        val points = listOf(point(0.0, 0, 20f), point(5.0, 1, 20f), point(6.0, 2, 0f), point(6.0, 20, 0f),
            point(7.0, 21, 20f), point(500.0, 200, 20f), point(505.0, 201, 20f, segment = 1))
        // Counted: 20 km/h (1 s), slowing 20→0 (1 s, 10 km/h), restarting 0→20 (1 s, 10 km/h).
        // Ignored: the 18 s standstill, the 179 s signal gap and the jump to a new segment.
        assertEquals(40.0 / 3, TripSpeed.profile(points)!!.movingAverageKmh!!, .001)
    }

    @Test fun implausibleScooterValuesAreIgnored() {
        val profile = TripSpeed.profile(listOf(point(0.0, 0, 12f), point(5.0, 1, 900f), point(10.0, 2, 14f)))!!
        assertNull(profile.pointSpeeds[1])
        assertEquals(14.0, profile.maxKmh, .001)
    }

    @Test fun colourRampFollowsTheStopsAndClampsAtTheEnds() {
        assertEquals(TripSpeed.stops.first().second, TripSpeed.color(-5.0))
        assertEquals(TripSpeed.stops.first().second, TripSpeed.color(Double.NaN))
        assertEquals(TripSpeed.stops[2].second, TripSpeed.color(TripSpeed.stops[2].first))
        assertEquals(TripSpeed.stops.last().second, TripSpeed.color(80.0))
        assertEquals("#FFE45C", TripSpeed.hex(40.0))
    }

    @Test fun tooFewPointsHaveNoProfile() {
        assertNull(TripSpeed.profile(listOf(point(0.0, 0, 10f))))
    }

    @Test fun demoRouteLooksLikeAScooterRecording() {
        val trip = DemoTripModena.history(java.time.LocalDate.of(2026, 11, 4), java.time.ZoneId.of("Europe/Rome")).first()
        val points = DemoTripModena.pointsFor(trip)
        val profile = TripSpeed.profile(points)!!
        assertEquals(SpeedSource.SCOOTER, profile.source)
        assertEquals(trip.startedAt, points.first().time)
        assertEquals((trip.startedAt + trip.durationMillis).toDouble(), points.last().time.toDouble(), 1000.0)
        assertTrue(points.zipWithNext().all { (a, b) -> TripSpeed.connected(a, b) })
        assertTrue(profile.maxKmh in 5.0..40.0)
    }
}
