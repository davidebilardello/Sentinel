package it.bilar.sentinel

import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.zip.GZIPOutputStream

@RunWith(AndroidJUnit4::class)
class YouFsCloudTest {
    @Test fun disconnectRemovesAccountSessionButPreservesTheImportedVehicle() {
        val fixture = Fixture()
        try {
            val account = YouFsAccount(fixture.context,fixture::gateway)
            val device = account.login("first@example.com","fake-password","39","EU").single()
            val profile = account.fetch(device.id)
            YouFsProfileStore(fixture.context).save(profile)
            account.disconnect()
            assertNull(account.remembered())
            assertNull(YouFsAccount(fixture.context,fixture::gateway).remembered())
            assertEquals(device.id,YouFsProfileStore(fixture.context).load()!!.json.getString("deviceId"))
            try { account.fetch(device.id); fail("Disconnected account kept device records") }
            catch(error: YouFsCloudError) { assertEquals("DEVICE_UNSUPPORTED",error.code) }
            val calls = fixture.calls.size
            try { account.login("first@example.com","","39","EU"); fail("Disconnected account reused its session") }
            catch(error: YouFsCloudError) { assertEquals("PASSWORD_REQUIRED",error.code) }
            assertEquals(calls,fixture.calls.size)
        } finally { fixture.close() }
    }
    private class Fixture(val requireMfa: Boolean = false) {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val folder = File(base.cacheDir,"cloud-test-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(base) { override fun getFilesDir() = folder }
        val calls = mutableListOf<String>()
        var codeSent = false
        var wrongDetail = false
        private val session = "fake-valid-session-1234"
        private val ecode = "fake-cloud-ecode"
        private val schema = InstrumentationRegistry.getInstrumentation().context.assets.open("youfs-schema.json").bufferedReader().use { JSONObject(it.readText()).getJSONArray("schema") }
        fun gateway(region: String, install: String) = YouFsCloudGateway(region,install) { url,parameters ->
            assertEquals("https://a1-eu.gdyoufs.com/api.json",url)
            assertEquals(YouFsCloudCodec.signature(parameters.filterKeys { it != "sign" }),parameters["sign"])
            val key = YouFsCloudCodec.payloadKey(parameters.getValue("requestId"),if (parameters["sid"] == session) ecode else null)
            val post = JSONObject(YouFsCloudCodec.decrypt(parameters.getValue("postData"),key).toString(Charsets.UTF_8))
            val api = parameters.getValue("a"); calls += api
            var success = true; var code = ""
            val result: Any = when (api) {
                "smartlife.m.user.username.token.get" -> JSONObject().put("token","fake-login-token")
                "smartlife.m.user.email.password.login" -> {
                    assertEquals(0,post.getInt("ifencrypt")); assertEquals("fake-login-token",post.getString("token"))
                    assertEquals(YouFsCloudCodec.md5("fake-password"),post.getString("passwd")); assertEquals("first@example.com",post.getString("email"))
                    val options = JSONObject(post.getString("options")); assertEquals(1,options.getInt("group"))
                    if (requireMfa && options.optString("mfaCode") != "123456") { success = false; code = "MFA_NEED_SEND_CODE" }
                    JSONObject().put("session",session).put("ecode",ecode)
                }
                "smartlife.m.user.username.mfa.code.get" -> { codeSent = true; assertEquals("null",JSONObject(post.getString("options")).getString("mfaCode")); true }
                "smartlife.m.user.info.get" -> { assertEquals(session,parameters["sid"]); JSONObject().put("ok",true) }
                "m.life.group.location.list" -> JSONArray().put(JSONObject().put("gid",123))
                "m.life.my.group.device.list" -> {
                    assertEquals(123,post.getInt("gid"))
                    JSONObject().put("devices",JSONArray().put(JSONObject().put("devId","fakeDevice1").put("name","Il mio monopattino")
                        .put("uuid","1234567890123456").put("localKey","abcdef0123456789").put("secKey","fake-secret-1234").put("productId","ivybh960").put("mac","001122334455")))
                }
                "smartlife.m.device.get" -> JSONObject().put("devId",if (wrongDetail) "otherDevice" else "fakeDevice1").put("schema",schema.toString())
                else -> throw AssertionError("Unexpected API: $api")
            }
            val clear = JSONObject().put("success",success).put("result",result).put("errorCode",code).put("msg","private-server-text").toString().toByteArray()
            val compressed = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(clear) } }.toByteArray()
            JSONObject().put("sign","wrapper").put("result",YouFsCloudCodec.encrypt(compressed,key))
        }
        fun close() { folder.listFiles()?.forEach { it.delete() }; folder.delete() }
    }
    @Test fun emailLoginFetchesOwnedDevicesAndReusesEncryptedSessionWithoutSavingPassword() {
        val fixture = Fixture()
        try {
            val account = YouFsAccount(fixture.context,fixture::gateway)
            val devices = account.login("first@example.com","fake-password","39","EU")
            assertEquals(listOf("Il mio monopattino"),devices.map { it.name })
            val profile = account.fetch(devices.first().id)
            val parsed = YouFsProfile.parse(profile)
            assertEquals(36,parsed.schema.size); assertEquals("00:11:22:33:44:55",parsed.json.getString("address"))
            val saved = account.remembered()!!.toString()
            assertFalse(saved.contains("fake-password")); assertFalse(saved.contains(YouFsCloudCodec.md5("fake-password")))
            val raw = File(fixture.folder,"youfs-account.enc").readBytes().toString(Charsets.ISO_8859_1)
            assertFalse(raw.contains("first@example.com")); assertFalse(raw.contains("fake-valid-session"))
            val resumed = YouFsAccount(fixture.context,fixture::gateway)
            assertEquals(1,resumed.login("first@example.com","","39","EU").size)
            assertEquals(1,fixture.calls.count { it == "smartlife.m.user.email.password.login" })
            fixture.wrongDetail = true
            try { resumed.fetch("fakeDevice1"); fail("Mismatching device imported") } catch (error: YouFsCloudError) { assertEquals("DEVICE_UNSUPPORTED",error.code) }
            val previous = fixture.calls.size
            try { fixture.gateway("EU","fake").call("thing.m.device.dp.publish"); fail("Cloud write allowed") } catch (_: IllegalArgumentException) { }
            assertEquals(previous,fixture.calls.size)
        } finally { fixture.close() }
    }
    @Test fun emailChallengeUsesFreshTokensAndOnlySendsCodeWhenExplicitlyRequested() {
        val fixture = Fixture(requireMfa = true)
        try {
            val account = YouFsAccount(fixture.context,fixture::gateway)
            try { account.login("first@example.com","fake-password","39","EU"); fail("Missing email challenge") }
            catch (error: YouFsCloudError) { assertTrue(error.requiresCode); assertFalse(error.message!!.contains("private-server-text")) }
            assertFalse(fixture.codeSent)
            account.requestCode("first@example.com","fake-password","39","EU"); assertTrue(fixture.codeSent)
            assertEquals(1,account.login("first@example.com","fake-password","39","EU","123456").size)
            assertEquals(3,fixture.calls.count { it == "smartlife.m.user.username.token.get" })
            assertEquals(2,fixture.calls.count { it == "smartlife.m.user.email.password.login" })
        } finally { fixture.close() }
    }
}
