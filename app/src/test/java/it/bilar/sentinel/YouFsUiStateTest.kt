package it.bilar.sentinel

import org.junit.Assert.*
import org.junit.Test

class YouFsUiStateTest {
    private val mode = YouFsDp(12,"mode","enum",true,0.0,0.0,0,1.0,listOf("sport","eco","normal"),0,"")
    @Test fun modeSelectionUsesTheDeviceSchemaOrder() {
        val state = ScooterUiState(youfsValues = mapOf(12 to YouFsValue(4,1)))
        assertEquals(1,mode.selectedOption(state))
        assertEquals("eco",mode.options[mode.selectedOption(state)!!])
    }
    @Test fun unknownOrWronglyTypedModeDoesNotSelectAnOption() {
        for(value in listOf(YouFsValue(2,1),YouFsValue(4,8),YouFsValue(4,-1),YouFsValue(4,"eco"))) {
            assertNull(mode.selectedOption(ScooterUiState(youfsValues = mapOf(12 to value))))
        }
        assertNull(mode.selectedOption(ScooterUiState()))
    }
    @Test fun anotherScooterUsesItsOwnReportedMode() {
        val state = ScooterUiState(activeScooterId = "first",youfsValues = mapOf(12 to YouFsValue(4,1)))
        val switched = state.copy(activeScooterId = "second",youfsValues = mapOf(12 to YouFsValue(4,0)))
        assertEquals("sport",mode.options[mode.selectedOption(switched)!!])
    }
}
