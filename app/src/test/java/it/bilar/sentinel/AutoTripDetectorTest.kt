package it.bilar.sentinel

import org.junit.Assert.*
import org.junit.Test

class AutoTripDetectorTest {
    @Test fun resettingBeforeAStartDiscardsMovementFromThePreviousScooter() {
        val detector = AutoTripDetector()
        for(second in 0L..6L step 2) assertTrue(accept(detector,fix(second,second*3.0)).isEmpty())
        assertTrue(detector.finish().isEmpty())
        for(second in 8L..14L step 2) assertTrue(accept(detector,fix(second,second*3.0)).isEmpty())
        val trip = accept(detector,fix(16,48.0)).single() as TripEvent.Started
        assertTrue(trip.points.all { it.time >= 1700000008000 })
    }
    @Test fun dropsPoorUrbanFixesAndStaleBatches() {
        val detector = started()
        assertTrue(accept(detector, fix(11, 33.0, accuracy = 24f)).isEmpty())
        assertTrue(detector.accept(fix(12, 36.0), 18000).isEmpty())
        val next = accept(detector, fix(13, 39.0)).single() as TripEvent.Point
        assertEquals(39.0, (next.point.latitude - 45.0) * 111195.0, .01)
    }
    @Test fun keepsOneSecondPointsAndSlowerTurnsDuringAnExistingTrip() {
        val detector = started()
        for (second in 11L..14L) {
            val event = accept(detector, fix(second, 30.0 + (second - 10) * 2.0, speed = 1f)).single() as TripEvent.Point
            assertEquals(2.0, event.meters, .02)
        }
        assertTrue(accept(detector, fix(15, 42.0, speed = 0f)).isEmpty())
    }
    @Test fun rejectsUrbanPositionJumpsInconsistentWithMeasuredSpeed() {
        val detector = started()
        assertTrue(accept(detector, fix(11, 65.0, speed = 3f, accuracy = 4f)).isEmpty())
        val valid = accept(detector, fix(12, 36.0)).single() as TripEvent.Point
        assertEquals(6.0, valid.meters, .02)
    }
    private fun fix(seconds: Long, meters: Double, speed: Float = 3f, accuracy: Float = 4f) = GpsFix(
        45.0 + meters / 111195.0, 9.0, 1700000000000 + seconds * 1000, seconds * 1000,
        accuracy, speed, 0.2f)
    private fun accept(detector: AutoTripDetector, fix: GpsFix) = detector.accept(fix, fix.elapsed)
    private fun started(): AutoTripDetector = AutoTripDetector().apply {
        for (second in 0L..10L step 2) accept(this, fix(second, second * 3.0))
        assertTrue(active)
    }

    @Test fun startsAutomaticallyOnlyAfterSustainedMovementAndKeepsTheBeginning() {
        val detector = AutoTripDetector()
        for (seconds in 0L..6L step 2) assertTrue(accept(detector, fix(seconds, seconds * 3.0)).isEmpty())
        val event = accept(detector, fix(8, 24.0)).single() as TripEvent.Started
        assertEquals(1700000000000, event.points.first().time)
        assertTrue(AutoTripDetector.distance(event.points.first(), event.points.last()) >= 23.9)
        assertTrue(detector.active)
    }

    @Test fun ignoresStationaryGpsDriftAndBriefFalseStarts() {
        val detector = AutoTripDetector()
        for (seconds in 0L..300L step 2) assertTrue(accept(detector, fix(seconds, if (seconds % 4 == 0L) 4.0 else -4.0, speed = 0f)).isEmpty())
        assertFalse(detector.active)
        for (seconds in 302L..306L step 2) accept(detector, fix(seconds, (seconds - 300) * 3.0))
        accept(detector, fix(308, 18.0, speed = 0f))
        assertFalse(detector.active)
        assertTrue(detector.finish().isEmpty())
    }

    @Test fun excludesImpreciseStaleAndImpossibleGpsPoints() {
        val detector = AutoTripDetector()
        assertTrue(accept(detector, fix(0, 0.0, accuracy = 80f)).isEmpty())
        assertTrue(accept(detector, fix(2, 6.0).copy(latitude = Double.NaN)).isEmpty())
        assertTrue(accept(detector, fix(4, 12.0).copy(longitude = 200.0)).isEmpty())
        assertTrue(detector.accept(fix(4, 12.0), 25000).isEmpty())
        accept(detector, fix(6, 18.0))
        assertTrue(accept(detector, fix(8, 3000.0)).isEmpty())
        assertTrue(accept(detector, fix(8, 24.0, speed = 90f)).isEmpty())
        assertFalse(detector.active)
    }

    @Test fun trafficLightsDoNotSplitTripsButTwoMinutesStationaryFinishThem() {
        val detector = started()
        assertTrue(accept(detector, fix(12, 30.0, speed = 0f)).isEmpty())
        assertTrue(detector.tick(100000).isEmpty())
        assertTrue(detector.active)
        accept(detector, fix(102, 36.0))
        assertTrue(detector.tick(221999).isEmpty())
        val end = detector.tick(222000).single() as TripEvent.Finished
        assertEquals(1700000102000, end.endedAt)
        assertFalse(detector.active)
        assertTrue(detector.tick(250000).isEmpty())
    }

    @Test fun neverBridgesLongGpsGapsWithInventedKilometers() {
        val detector = started()
        val event = accept(detector, fix(60, 300.0)).single() as TripEvent.Point
        assertEquals(0.0, event.meters, 0.001)
        assertEquals(1, event.point.segment)
        val next = accept(detector, fix(62, 306.0)).single() as TripEvent.Point
        assertEquals(6.0, next.meters, 0.02)
        assertEquals(1, next.point.segment)
    }

    @Test fun movementBeforeALongSignalGapDoesNotInstantlyStartATrip() {
        val detector = AutoTripDetector()
        accept(detector, fix(0, 0.0))
        accept(detector, fix(2, 6.0))
        assertTrue(accept(detector, fix(100, 300.0)).isEmpty())
        assertFalse(detector.active)
    }

    @Test fun neverBridgesGpsGapEvenWhenTheFirstReturningFixIsStationary() {
        val detector = started()
        assertTrue(accept(detector, fix(60, 300.0, speed = 0f)).isEmpty())
        val event = accept(detector, fix(62, 306.0)).single() as TripEvent.Point
        assertEquals(0.0, event.meters, 0.001)
        assertEquals(1, event.point.segment)
    }

    @Test fun finishIsIdempotentAndTheNextTripCanStartAutomatically() {
        val detector = started()
        assertEquals(1, detector.finish().size)
        assertTrue(detector.finish().isEmpty())
        val events = mutableListOf<TripEvent>()
        for (seconds in 20L..30L step 2) events += accept(detector, fix(seconds, seconds * 3.0))
        assertEquals(1, events.count { it is TripEvent.Started })
        assertTrue(detector.active)
    }

    @Test fun noSpeedFieldRequiresPositionMovementLargerThanGpsUncertainty() {
        val detector = AutoTripDetector()
        for (seconds in 0L..100L step 2) accept(detector, fix(seconds, if (seconds % 4 == 0L) 3.0 else 0.0).copy(speedMps = null))
        assertFalse(detector.active)
        for (seconds in 102L..114L step 2) accept(detector, fix(seconds, (seconds - 100) * 5.0).copy(speedMps = null))
        assertTrue(detector.active)
    }

    @Test fun detectsSlowScooterMovementEvenWhenGpsHasNoSpeedField() {
        val detector = AutoTripDetector()
        for (seconds in 0L..20L step 2) accept(detector, fix(seconds, seconds * 1.5, accuracy = 3f).copy(speedMps = null))
        assertTrue(detector.active)
    }
}
