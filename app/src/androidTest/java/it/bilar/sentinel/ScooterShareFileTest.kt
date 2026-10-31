package it.bilar.sentinel

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScooterShareFileTest {
    private fun profile(): YouFsProfile {
        val source = JSONObject(InstrumentationRegistry.getInstrumentation().context.assets.open("youfs-schema.json").bufferedReader().use { it.readText() })
            .put("uuid","1234567890123456").put("deviceId","shareTest123").put("localKey","abcdef0123456789")
            .put("secKey","fake-secret-1234").put("securityMode","new")
        return YouFsProfile.parse(source.toString().toByteArray())
    }
    @Test fun exportsAndImportsDirectlyWithoutSharingAccountOrTrips() {
        val source = profile()
        source.json.put("password","account-password").put("session","account-session").put("trips","private-trips")
        val file = ScooterShareFile.encode(source,"YouFs condiviso","00:11:22:33:44:55")
        val json = JSONObject(file.toString(Charsets.UTF_8))
        assertEquals("shareTest123",json.getString("deviceId")); assertEquals("YouFs condiviso",json.getString("name"))
        assertFalse(json.has("password")); assertFalse(json.has("session")); assertFalse(json.has("trips"))
        val imported = YouFsProfile.parse(ScooterShareFile.decode(file))
        assertEquals(source.schema.size,imported.schema.size); assertEquals("abcdef0123456789",imported.json.getString("localKey"))
    }
    @Test fun rejectsOldProtectedFilesAndInvalidProfiles() {
        assertThrows(IllegalArgumentException::class.java) { ScooterShareFile.decode(byteArrayOf(83,78,84,76,1,1)) }
        val missing = profile().json.apply { remove("secKey") }.toString().toByteArray()
        assertThrows(IllegalArgumentException::class.java) { ScooterShareFile.decode(missing) }
    }
}
