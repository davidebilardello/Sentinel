package it.bilar.sentinel

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ScooterRegistryTest {
    @Test fun migratesSelectionRemembersAddressesAndKeepsSeparateEncryptedProfiles() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val folder = File(base.cacheDir, "registry-test").apply { mkdirs() }
        val context = object : ContextWrapper(base) {
            override fun getSharedPreferences(name: String, mode: Int) = base.getSharedPreferences("registry-test-$name", mode)
            override fun getFilesDir() = folder
        }
        val preferences = context.getSharedPreferences("scooter", Context.MODE_PRIVATE)
        preferences.edit().clear().putString("family", ScooterFamily.YOUFS_TUYA.name).commit()
        val registry = ScooterRegistry(context)
        assertEquals("youfs", registry.selected().id)
        val firstId = UUID.randomUUID().toString(); val secondId = UUID.randomUUID().toString()
        val first = SavedScooter(firstId, "YouFs uno", ScooterFamily.YOUFS_TUYA, "00:11:22:33:44:55", firstId)
        val second = SavedScooter(secondId, "YouFs due", ScooterFamily.YOUFS_TUYA, "00:11:22:33:44:66", secondId)
        fun profile(deviceId: String): ByteArray {
            val json = JSONObject(InstrumentationRegistry.getInstrumentation().context.assets.open("youfs-schema.json").bufferedReader().use { it.readText() })
            return json.put("uuid", "1234567890123456").put("deviceId", deviceId).put("localKey", "abcdef0123456789")
                .put("secKey", "fake-secret-1234").put("securityMode", "new").toString().toByteArray()
        }
        val firstStore = YouFsProfileStore(context, firstId); val secondStore = YouFsProfileStore(context, secondId)
        try {
            firstStore.save(profile("first123")); secondStore.save(profile("second123"))
            registry.save(first); registry.save(second); registry.select(secondId)
            val restored = ScooterRegistry(context)
            assertEquals(second.id, restored.selected().id); assertEquals(second.address,restored.selected().address); assertEquals(4, restored.all().size)
            assertEquals("first123", firstStore.load()!!.json.getString("deviceId"))
            assertEquals("second123", secondStore.load()!!.json.getString("deviceId"))
            val before = restored.selected().modified
            secondStore.save(JSONObject(secondStore.load()!!.json.toString()).put("localKey","0123456789abcdef").toString().toByteArray())
            restored.save(restored.selected(),profileChanged = true)
            assertTrue(restored.selected().modified > before)
            assertEquals(4,restored.all().size)
            restored.remove(secondId); secondStore.forget()
            assertNotEquals(secondId, restored.selected().id); assertNotNull(firstStore.load()); assertNull(secondStore.load())
            try { YouFsProfileStore(context, "../escape"); fail("Unsafe profile slot accepted") } catch (_: IllegalArgumentException) { }
        } finally { firstStore.forget(); secondStore.forget(); preferences.edit().clear().commit() }
    }
}
