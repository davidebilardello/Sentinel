package it.bilar.sentinel

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppLanguageTest {
    @Test fun switchesBothLanguagesAndLocalizesProtocolMessagesAndPlurals() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val previous = AppLanguage.code(context)
        try {
            AppLanguage.set(context,"en")
            assertEquals("Settings",appText(R.string.settings))
            assertEquals("1 trip",AppLanguage.quantity(R.plurals.trip_count,1))
            assertEquals("2 trips",AppLanguage.quantity(R.plurals.trip_count,2))
            assertEquals("Bluetooth session is not authenticated.",AppLanguage.translate("Sessione Bluetooth non autenticata."))
            assertEquals("Unrecognized DP ACK (command 39, 6 bytes).",AppLanguage.translate("Formato ACK DP non riconosciuto (comando 39, 6 byte)."))
            assertEquals("1.5",String.format(AppLanguage.locale,"%.1f",1.5))
            assertTrue(appText(R.string.recording_trip_2f_km).contains("%.2f"))
            assertEquals("Recording trip · 1.25 km",appText(R.string.recording_trip_2f_km,1.25))
            AppLanguage.set(context,"it")
            assertEquals("Impostazioni",appText(R.string.settings))
            assertEquals("1 corsa",AppLanguage.quantity(R.plurals.trip_count,1))
            assertEquals("2 corse",AppLanguage.quantity(R.plurals.trip_count,2))
            assertEquals("Sessione Bluetooth non autenticata.",AppLanguage.translate("Bluetooth session is not authenticated."))
            assertEquals("Sensore Hall",AppLanguage.translate("Hall sensor"))
            assertEquals("Collega il monopattino per rilevare automaticamente i viaggi.",AppLanguage.translate("Connect your scooter to record trips automatically."))
            assertEquals("1,5",String.format(AppLanguage.locale,"%.1f",1.5))
            assertEquals("Corsa in corso · 1,25 km",appText(R.string.recording_trip_2f_km,1.25))
        } finally { AppLanguage.set(context,previous) }
    }
}
