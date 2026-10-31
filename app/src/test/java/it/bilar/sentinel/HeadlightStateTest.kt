package it.bilar.sentinel

import org.junit.Assert.*
import org.junit.Test

class HeadlightStateTest {
    private val light = YouFsDp(8, "headlight_switch", "bool", true, 0.0, 0.0, 0, 1.0, emptyList(), 0, "")
    @Test fun youfsLightDoesNotWaitForSpeedOrBatteryTelemetry() {
        val state = ScooterUiState(family = ScooterFamily.YOUFS_TUYA, youfsSchema = listOf(light),
            youfsValues = mapOf(8 to YouFsValue(1, true)))
        assertNull(state.telemetry)
        assertTrue(state.headlightOn)
        assertFalse(state.copy(youfsValues = mapOf(8 to YouFsValue(1, false))).headlightOn)
        assertFalse(state.copy(youfsValues = emptyMap()).headlightOn)
        assertFalse(state.copy(youfsValues = mapOf(8 to YouFsValue(4, 1))).headlightOn)
        assertFalse(state.copy(youfsSchema = emptyList()).headlightOn)
    }
    @Test fun jeepLightUsesTheConfirmedFlags() {
        val telemetry = Telemetry(0f, 0f, 0f, 80, 1, 0, 0, 0, 0)
        assertFalse(ScooterUiState(telemetry = telemetry).headlightOn)
        assertTrue(ScooterUiState(telemetry = telemetry.copy(flags = 0x11)).headlightOn)
    }
}
