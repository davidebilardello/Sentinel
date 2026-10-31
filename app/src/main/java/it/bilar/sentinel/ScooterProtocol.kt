package it.bilar.sentinel

import java.util.Locale

enum class Setting { LIGHTS, GEAR, CRUISE, ZERO_START, UNIT, LOCK }

data class Telemetry(
    val speedKmh: Float,
    val totalKm: Float,
    val sessionKm: Float,
    val battery: Int,
    val flags: Int,
    val errors: Int,
    val controllerFlags: Int,
    val rideTimeRaw: Int,
    val receivedAt: Long
) {
    val gear get() = flags and 3
    val lights get() = flags and 0x10 != 0
    val rearLight get() = flags and 0x20 != 0
    val cruise get() = flags and 4 != 0
    val zeroStart get() = flags and 8 != 0
    val mph get() = flags and 0x40 != 0
    val locked get() = flags and 0x80 != 0
    val displaySpeed get() = speedKmh * if (mph) 0.621371f else 1f
    val distanceFactor get() = if (mph) 0.621371f else 1f
    val speedUnit get() = if (mph) "mph" else "km/h"
    val distanceUnit get() = if (mph) "mi" else "km"
    val gearName get() = listOf("—", "ECO", "DRIVE", "SPORT")[gear]
    val failures get() = ScooterProtocol.failureNames.filterIndexed { bit, _ -> errors and (1 shl bit) != 0 }
    val controllerStates get() = ScooterProtocol.controllerNames.filterIndexed { bit, _ -> controllerFlags and (1 shl bit) != 0 }
}

object ScooterProtocol {
    const val SERVICE = "0000fff0-0000-1000-8000-00805f9b34fb"
    const val TX = "0000fff6-0000-1000-8000-00805f9b34fb"
    const val RX = "0000fff7-0000-1000-8000-00805f9b34fb"
    const val FRESH_MS = 3000L
    val failureNames get() = listOf("Hall sensor", "MOS", "Brake", "Throttle", "Undervoltage", "Communication", "Overvoltage", "Zero failure")
    val controllerNames get() = listOf("Motor locked", "Cruise active", "Anti-theft push detected", "Riding", "Automatic power off", "Three-gear interface")

    // shortcut: solo frame interi 5A B9 da 16 byte; riassemblare se un firmware invia frammenti.
    fun parse(bytes: ByteArray, receivedAt: Long): Telemetry? {
        fun u(i: Int) = bytes[i].toInt() and 255
        fun word(i: Int) = u(i) or (u(i + 1) shl 8)
        if (bytes.size != 16 || u(0) != 0x5A || u(1) != 0xB9 || u(8) > 100) return null
        val battery = u(8).let { if (it < 20) it else ((it + 4) / 5) * 5 }
        return Telemetry(word(2) / 10f, word(4) / 10f, word(6) / 10f, battery,
            u(9), u(10), u(11), word(12), receivedAt)
    }

    fun command(state: Telemetry, setting: Setting, value: Int): ByteArray {
        require(state.gear in 1..3) { "Wait for ECO, DRIVE or SPORT mode." }
        var data1 = if (state.gear == 3) 4 else 0
        var data2 = (if (state.gear >= 2) 1 else 0) or ((state.flags and 0xFC) shr 1)
        if (setting == Setting.GEAR) {
            require(value in 1..3) { "Invalid mode." }
            data1 = if (value == 3) 4 else 0
            data2 = if (value >= 2) data2 or 1 else data2 and 1.inv()
        } else {
            require(value in 0..1) { "Invalid setting." }
            require(setting != Setting.LOCK || state.speedKmh == 0f) { "Lock or unlock only at zero speed." }
            val mask = when (setting) {
                Setting.LIGHTS -> 0x18
                Setting.CRUISE -> 2
                Setting.ZERO_START -> 4
                Setting.UNIT -> 0x20
                Setting.LOCK -> 0x40
                else -> error("Invalid setting.")
            }
            data2 = if (value == 1) data2 or mask else data2 and mask.inv()
        }
        // APK mtd.jeep.app 1.13.4, modulo 3322 Kcq: stesso frame e stessi checksum.
        val xor = 0xBD xor data1 xor data2
        return byteArrayOf(0xCA.toByte(), 0xAC.toByte(), 0xBD.toByte(), data1.toByte(), data2.toByte(), xor.toByte(), (data1 + 0xBD + xor).toByte(), 0x6A)
    }

    fun expectedFlags(packet: ByteArray): Int {
        val gear = if (packet[3].toInt() and 4 != 0) 3 else if (packet[4].toInt() and 1 != 0) 2 else 1
        return ((packet[4].toInt() and 0x7E) shl 1) or gear
    }
}

fun ByteArray.hex(): String = joinToString(" ") { String.format(Locale.ROOT, "%02X", it.toInt() and 255) }
