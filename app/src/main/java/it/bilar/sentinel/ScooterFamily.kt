package it.bilar.sentinel

enum class ScooterFamily(val title: String) {
    SENTINEL("2xe Sentinel"), YOUFS_TUYA("YouFs-A / Tuya");

    fun matchesAdvertisement(name: String, services: Set<String>, tuyaManufacturer: Boolean = false): Boolean = when (this) {
        SENTINEL -> name.startsWith("2xe", ignoreCase = true) || services.any { it.equals(ScooterProtocol.SERVICE, true) }
        YOUFS_TUYA -> services.any { it.equals(YouFsTuya.SERVICE, true) } || tuyaManufacturer ||
            name.startsWith("YouFs", ignoreCase = true) || name.startsWith("YFS", ignoreCase = true)
    }
}

object YouFsTuya {
    const val SERVICE = "0000fd50-0000-1000-8000-00805f9b34fb"
    const val TX = "00000001-0000-1001-8001-00805f9b07d0"
    const val RX = "00000002-0000-1001-8001-00805f9b07d0"
    const val MANUFACTURER = 0x07D0

}

data class BleCharacteristicInfo(val uuid: String, val properties: Int)
data class BleServiceInfo(val uuid: String, val characteristics: List<BleCharacteristicInfo>)
data class YouFsInspection(val deviceName: String, val address: String, val advertisementHex: String,
    val capturedAt: Long, val services: List<BleServiceInfo>) {
    val hasTuyaService get() = services.any { it.uuid.equals(YouFsTuya.SERVICE, true) }
    val hasTuyaTransport get() = services.any { service ->
        service.uuid.equals(YouFsTuya.SERVICE, true) &&
            service.characteristics.any { it.uuid.equals(YouFsTuya.TX, true) && it.properties and 0x0C != 0 } &&
            service.characteristics.any { it.uuid.equals(YouFsTuya.RX, true) && it.properties and 0x10 != 0 }
    }
    fun text(): String = buildString {
        appendLine(appText(R.string.sentinel_youfs_a_tuya_inspection))
        appendLine(appText(R.string.message_9,deviceName))
        appendLine(appText(R.string.message_10,address))
        appendLine(appText(R.string.message_11,java.time.Instant.ofEpochMilli(capturedAt)))
        appendLine(appText(R.string.message_12,hasTuyaTransport))
        appendLine(appText(R.string.control_commands_sent_none))
        appendLine("Advertising: $advertisementHex")
        services.forEach { service ->
            appendLine(appText(R.string.message_14,service.uuid))
            service.characteristics.forEach { appendLine(appText(R.string.message_15,it.uuid,it.properties.toString(16))) }
        }
    }
}
