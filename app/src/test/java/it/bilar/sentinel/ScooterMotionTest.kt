package it.bilar.sentinel

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI

class ScooterMotionTest {
    @Test fun staysStillAtRestAndStopsAfterTelemetryReturnsToZero() {
        val motion = ScooterMotion()
        repeat(120) { motion.advance(1f/60) }
        assertEquals(0f,motion.roadOffset,0f); assertEquals(0f,motion.wheelDegrees,0f)
        motion.targetSpeedKmh = 18f
        repeat(120) { motion.advance(1f/60) }
        assertTrue(motion.speedKmh > 17.9f)
        motion.targetSpeedKmh = 0f
        repeat(180) { motion.advance(1f/60) }
        val stopped = motion.roadOffset
        repeat(60) { motion.advance(1f/60) }
        assertEquals(0f,motion.speedKmh,0f); assertEquals(stopped,motion.roadOffset,0f)
    }
    @Test fun wheelRotationAndRoadTravelAgreeWithSpeed() {
        val motion = ScooterMotion().apply { targetSpeedKmh = 18f }
        repeat(240) { motion.advance(1f/60) }
        val road = motion.roadOffset; val wheel = motion.wheelDegrees
        motion.advance(.02f)
        val moved = (motion.roadOffset-road+ScooterMotion.ROAD_LENGTH)%ScooterMotion.ROAD_LENGTH
        val degrees = (wheel-motion.wheelDegrees+360f)%360f
        assertEquals(5f*.02f*.195f/.108f,moved,.0001f)
        assertEquals((5f*.02f/.108f*180f/PI).toFloat(),degrees,.001f)
        assertTrue((0 until 8).all { motion.roadTileX(it) in -3.6f..3.6f })
    }
    @Test fun ignoresLongPausedFramesAndInvalidSpeed() {
        val motion = ScooterMotion().apply { targetSpeedKmh = Float.NaN }
        repeat(60) { motion.advance(1f/60) }
        assertEquals(0f,motion.speedKmh,0f)
        motion.targetSpeedKmh = 25f; motion.advance(60f); motion.advance(Float.NaN)
        assertEquals(0f,motion.roadOffset,0f)
    }
    @Test fun horizontalSwipesChangeOnlyYawAndWrapAround() {
        val motion = ScooterMotion()
        motion.drag(100f,1000); assertEquals(6f,motion.yawDegrees,.001f)
        motion.drag(2000f,1000); assertEquals(6f,motion.yawDegrees,.001f)
        assertEquals(0f,motion.wheelDegrees,0f); assertEquals(0f,motion.roadOffset,0f)
    }
    @Test fun markingsFadeInRoadCoordinatesBeforeTheyWrapAround() {
        val motion = ScooterMotion()
        assertEquals(1f,motion.roadOpacity(0f),0f)
        assertEquals(1f,motion.roadOpacity(.95f),0f)
        assertEquals(.5f,motion.roadOpacity(1.45f),.0001f)
        assertEquals(motion.roadOpacity(1.45f),motion.roadOpacity(-1.45f),0f)
        assertEquals(0f,motion.roadOpacity(1.95f),0f)
        assertEquals(0f,motion.roadOpacity(3.6f),0f)
    }
}
