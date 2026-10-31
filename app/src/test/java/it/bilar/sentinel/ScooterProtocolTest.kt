package it.bilar.sentinel

import org.junit.Assert.*
import org.junit.Test

class ScooterProtocolTest {
    private fun frame(flags: Int = 1, speed: Int = 0) = byteArrayOf(
        0x5A, 0xB9.toByte(), speed.toByte(), (speed shr 8).toByte(), 0xD2.toByte(), 4,
        0x7B, 0, 76, flags.toByte(), 0, 0, 0x34, 0x12, 0, 0
    )
    private fun state(flags: Int = 1, speed: Int = 0) = ScooterProtocol.parse(frame(flags, speed), 1234)!!

    @Test fun parsesLittleEndianTelemetryAndUnits() {
        val t = state(0xFF, 999)
        assertEquals(99.9f, t.speedKmh, 0.001f)
        assertEquals(123.4f, t.totalKm, 0.001f)
        assertEquals(12.3f, t.sessionKm, 0.001f)
        assertEquals(80, t.battery)
        assertEquals(4660, t.rideTimeRaw)
        assertEquals(1234L, t.receivedAt)
        assertEquals("SPORT", t.gearName)
        assertTrue(t.lights && t.rearLight && t.locked && t.cruise && t.zeroStart && t.mph)
        assertEquals(62.075f, t.displaySpeed, 0.001f)
        assertEquals("mph", t.speedUnit)
        assertEquals("mi", t.distanceUnit)
        val smallBattery = frame().apply { this[8] = 19 }
        assertEquals(19, ScooterProtocol.parse(smallBattery, 0)!!.battery)
        val errors = frame().apply { this[10] = 0xA5.toByte(); this[11] = 0x3F }
        val diagnostic = ScooterProtocol.parse(errors, 0)!!
        assertEquals(listOf("Hall sensor", "Brake", "Communication", "Zero failure"), diagnostic.failures)
        assertEquals(6, diagnostic.controllerStates.size)
    }

    @Test fun rejectsMalformedNotifications() {
        for (length in listOf(0, 1, 15, 17, 50)) assertNull(ScooterProtocol.parse(ByteArray(length), 0))
        assertNull(ScooterProtocol.parse(frame().apply { this[0] = 0 }, 0))
        assertNull(ScooterProtocol.parse(frame().apply { this[1] = 0 }, 0))
        assertNull(ScooterProtocol.parse(frame().apply { this[8] = 101 }, 0))
        assertNull(ScooterProtocol.parse(frame().apply { this[8] = 0xFF.toByte() }, 0))
    }

    @Test fun matchesRecoveredApkGoldenPackets() {
        val fixtures = listOf(
            Triple(Setting.LIGHTS, 1, "CA AC BD 00 18 A5 62 6A"),
            Triple(Setting.LIGHTS, 0, "CA AC BD 00 00 BD 7A 6A"),
            Triple(Setting.GEAR, 2, "CA AC BD 00 01 BC 79 6A"),
            Triple(Setting.GEAR, 3, "CA AC BD 04 01 B8 79 6A"),
            Triple(Setting.CRUISE, 1, "CA AC BD 00 02 BF 7C 6A"),
            Triple(Setting.ZERO_START, 1, "CA AC BD 00 04 B9 76 6A"),
            Triple(Setting.UNIT, 1, "CA AC BD 00 20 9D 5A 6A"),
            Triple(Setting.LOCK, 1, "CA AC BD 00 40 FD BA 6A")
        )
        for ((setting, value, expected) in fixtures) assertEquals(expected, ScooterProtocol.command(state(), setting, value).hex())
        assertEquals("CA AC BD 04 7F C6 87 6A", ScooterProtocol.command(state(0xCF), Setting.LIGHTS, 1).hex())
        assertEquals("CA AC BD 04 67 DE 9F 6A", ScooterProtocol.command(state(0xCF), Setting.LIGHTS, 0).hex())
    }

    @Test fun preservesOtherSettingsInAll2496Combinations() {
        var checked = 0
        for (flags in 0..255) {
            if (flags and 3 == 0) continue
            for (setting in Setting.entries) {
                val values = if (setting == Setting.GEAR) 1..3 else 0..1
                for (value in values) {
                    val packet = ScooterProtocol.command(state(flags), setting, value)
                    val actual = ScooterProtocol.expectedFlags(packet)
                    val mask = when (setting) {
                        Setting.GEAR -> 3; Setting.LIGHTS -> 0x30; Setting.CRUISE -> 4
                        Setting.ZERO_START -> 8; Setting.UNIT -> 0x40; Setting.LOCK -> 0x80
                    }
                    assertEquals(flags and mask.inv(), actual and mask.inv())
                    assertEquals(if (setting == Setting.GEAR) value else if (value == 1) mask else 0, actual and mask)
                    fun u(i: Int) = packet[i].toInt() and 255
                    assertEquals(u(2) xor u(3) xor u(4), u(5))
                    assertEquals((u(2) + u(3) + u(5)) and 255, u(6))
                    assertEquals(8, packet.size)
                    checked++
                }
            }
        }
        assertEquals(2496, checked)
    }

    @Test fun onlyEnablesControlsWithFreshConnectedOrDemoState() {
        val ready = ScooterUiState(connected = true, fresh = true, telemetry = state())
        assertTrue(ready.canCommand)
        assertFalse(ready.copy(connected = false).canCommand)
        assertFalse(ready.copy(fresh = false).canCommand)
        assertFalse(ready.copy(busy = true).canCommand)
        assertFalse(ready.copy(telemetry = null).canCommand)
        assertFalse(ready.copy(telemetry = state(0)).canCommand)
        assertTrue(ready.copy(connected = false, demo = true).canCommand)
    }

    @Test fun refusesLockAndUnlockEvenAtMinimumNonzeroSpeed() {
        for (speed in listOf(1, 100, 999)) {
            for (value in 0..1) assertThrows(IllegalArgumentException::class.java) {
                ScooterProtocol.command(state(speed = speed), Setting.LOCK, value)
            }
        }
        for (setting in Setting.entries) assertThrows(IllegalArgumentException::class.java) {
            ScooterProtocol.command(state(0), setting, 1)
        }
        for (value in listOf(-1, 0, 4)) assertThrows(IllegalArgumentException::class.java) {
            ScooterProtocol.command(state(), Setting.GEAR, value)
        }
        assertThrows(IllegalArgumentException::class.java) { ScooterProtocol.command(state(), Setting.CRUISE, 2) }
    }
}
