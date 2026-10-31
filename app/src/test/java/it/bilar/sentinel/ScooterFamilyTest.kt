package it.bilar.sentinel

import org.junit.Assert.*
import org.junit.Test

class ScooterFamilyTest {
    @Test fun jeepAndTuyaAdvertisementsStaySeparate() {
        assertTrue(ScooterFamily.SENTINEL.matchesAdvertisement("2xe Sentinel", emptySet()))
        assertTrue(ScooterFamily.SENTINEL.matchesAdvertisement("", setOf(ScooterProtocol.SERVICE.uppercase())))
        assertFalse(ScooterFamily.SENTINEL.matchesAdvertisement("YouFs", setOf(YouFsTuya.SERVICE), true))
        assertFalse(ScooterFamily.YOUFS_TUYA.matchesAdvertisement("2xe Sentinel", setOf(ScooterProtocol.SERVICE)))
        assertTrue(ScooterFamily.YOUFS_TUYA.matchesAdvertisement("", setOf(YouFsTuya.SERVICE)))
        assertTrue(ScooterFamily.YOUFS_TUYA.matchesAdvertisement("", emptySet(), true))
        assertTrue(ScooterFamily.YOUFS_TUYA.matchesAdvertisement("youfs-123", emptySet()))
        assertFalse(ScooterFamily.YOUFS_TUYA.matchesAdvertisement("Other device", emptySet()))
    }

    @Test fun tuyaProfileNeverEnablesJeepCommandsEvenWithAnAccidentalTelemetryObject() {
        val telemetry = Telemetry(0f, 0f, 0f, 80, 1, 0, 0, 0, 0)
        val ready = ScooterUiState(family = ScooterFamily.YOUFS_TUYA, connected = true, fresh = true, telemetry = telemetry)
        assertFalse(ready.canCommand)
        assertFalse(ready.copy(demo = true).canCommand)
        assertTrue(ready.copy(family = ScooterFamily.SENTINEL).canCommand)
        assertTrue(ready.copy(youfsCanCommand = true).canCommand)
        assertFalse(ready.copy(youfsCanCommand = true, busy = true).canCommand)
        assertFalse(ready.copy(youfsCanCommand = true, fresh = false).canCommand)
        assertFalse(ready.copy(youfsCanCommand = true, demo = true).canCommand)
    }

    @Test fun completeTuyaGattRequiresBothCharacteristicsInTheCorrectService() {
        fun inspection(services: List<BleServiceInfo>) = YouFsInspection("Example", "00:11:22:33:44:55", "", 0, services)
        val tx = BleCharacteristicInfo(YouFsTuya.TX, 4)
        val rx = BleCharacteristicInfo(YouFsTuya.RX, 16)
        assertTrue(inspection(listOf(BleServiceInfo(YouFsTuya.SERVICE, listOf(tx, rx)))).hasTuyaTransport)
        assertFalse(inspection(listOf(BleServiceInfo(YouFsTuya.SERVICE, listOf(tx)))).hasTuyaTransport)
        assertFalse(inspection(listOf(BleServiceInfo(YouFsTuya.SERVICE, listOf(tx.copy(properties = 2), rx)))).hasTuyaTransport)
        assertFalse(inspection(listOf(BleServiceInfo(YouFsTuya.SERVICE, listOf(tx, rx.copy(properties = 2))))).hasTuyaTransport)
        assertFalse(inspection(listOf(BleServiceInfo("other", listOf(tx, rx)))).hasTuyaTransport)
        assertFalse(inspection(listOf(BleServiceInfo(YouFsTuya.SERVICE, listOf(tx)), BleServiceInfo("other", listOf(rx)))).hasTuyaTransport)
    }
}
