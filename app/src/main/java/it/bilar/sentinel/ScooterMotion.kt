package it.bilar.sentinel

import kotlin.math.PI
import kotlin.math.exp

internal class ScooterMotion {
    companion object {
        const val WHEEL_RADIUS_MODEL = .195f
        const val WHEEL_RADIUS_METRES = .108f
        const val ROAD_TILE_LENGTH = .9f
        const val ROAD_TILES = 8
        const val ROAD_LENGTH = ROAD_TILE_LENGTH * ROAD_TILES
        // Same fade interval as the vertex colors baked into create_scooter.py.
        const val ROAD_FADE_START = .95f
        const val ROAD_FADE_END = 1.95f
    }
    var targetSpeedKmh = 0f
    var speedKmh = 0f; private set
    var wheelDegrees = 0f; private set
    var roadOffset = 0f; private set
    var yawDegrees = -12f; private set

    fun advance(seconds: Float) {
        if(!seconds.isFinite() || seconds <= 0f || seconds > .25f) return
        val target = targetSpeedKmh.takeIf { it.isFinite() }?.coerceIn(0f,80f) ?: 0f
        speedKmh += (target-speedKmh)*(1f-exp(-seconds*5f))
        if(target == 0f && speedKmh < .02f) speedKmh = 0f
        val travel = speedKmh/3.6f*seconds*(WHEEL_RADIUS_MODEL/WHEEL_RADIUS_METRES)
        wheelDegrees = (wheelDegrees-travel/WHEEL_RADIUS_MODEL*(180f/PI.toFloat()))%360f
        roadOffset = (roadOffset+travel)%ROAD_LENGTH
    }
    fun roadTileX(index: Int): Float {
        val start = (index-(ROAD_TILES-1)/2f)*ROAD_TILE_LENGTH
        return ((start-roadOffset+ROAD_LENGTH*1.5f)%ROAD_LENGTH)-ROAD_LENGTH/2f
    }
    fun roadOpacity(x: Float): Float {
        val t = ((kotlin.math.abs(x)-ROAD_FADE_START)/(ROAD_FADE_END-ROAD_FADE_START)).coerceIn(0f,1f)
        return 1f-t*t*(3f-2f*t)
    }
    fun drag(horizontalPixels: Float, width: Int) {
        if(width <= 0 || !horizontalPixels.isFinite()) return
        yawDegrees = ((yawDegrees+horizontalPixels/width*180f+540f)%360f)-180f
    }
}
