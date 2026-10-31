package it.bilar.sentinel

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

@RunWith(AndroidJUnit4::class)
class YouFsConnectionTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun profile(): ByteArray {
        val json = JSONObject(instrumentation.context.assets.open("youfs-schema.json").bufferedReader().use { it.readText() })
        return json.put("uuid", "1234567890123456").put("deviceId", "0123456789012345678901")
            .put("localKey", "abcdef0123456789").put("secKey", "fake-secret-1234").put("securityMode", "new").toString().toByteArray()
    }

    @Test fun importedProfileIsEncryptedAndValidated() {
        val store = YouFsProfileStore(context)
        store.forget()
        val parsed = store.save(profile())
        assertEquals(36, parsed.schema.size)
        assertEquals(21, parsed.schema.count { it.writable })
        assertEquals("ivybh960", store.load()!!.productId)
        val encrypted = java.io.File(context.filesDir, "youfs-profile.enc").readBytes().toString(Charsets.ISO_8859_1)
        assertFalse(encrypted.contains("fake-secret-1234")); assertFalse(encrypted.contains("abcdef0123456789"))
        assertFalse(parsed.toString().contains("fake-secret"))
        val bad = JSONObject(profile().toString(Charsets.UTF_8)).put("localKey", "short").toString().toByteArray()
        try { store.save(bad); fail("Invalid credentials must not replace the saved profile") } catch (_: IllegalArgumentException) { }
        assertEquals("ivybh960", store.load()!!.productId)
        java.io.File(context.filesDir, "youfs-import.json").writeBytes(profile())
        assertNotNull(store.consumeTransfer()); assertFalse(java.io.File(context.filesDir, "youfs-import.json").exists())
        store.forget(); assertNull(store.load())
    }

    @Test fun nativeOfflineBridgeAuthenticatesQueriesAndConfirmsLightOnOff() {
        val events = LinkedBlockingQueue<JSONObject>()
        val failure = AtomicReference<Throwable?>()
        lateinit var engine: YouFsEngine
        val peer = Peer { bytes -> engine.receive(bytes) }
        fun waitFor(predicate: (JSONObject) -> Boolean): JSONObject {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
            while (System.nanoTime() < deadline) {
                failure.get()?.let { throw AssertionError("Fake BLE peer failed", it) }
                val event = events.poll(100, TimeUnit.MILLISECONDS) ?: continue
                assertNotEquals(event.optString("message"), "error", event.optString("type"))
                if (predicate(event)) return event
            }
            throw AssertionError("Native YouFs engine timed out")
        }
        instrumentation.runOnMainSync {
            engine = YouFsEngine(context, { id, bytes ->
                try { engine.completeWrite(id); peer.receive(bytes) } catch (e: Throwable) { failure.set(e) }
            }, { events.offer(it) })
            engine.start(YouFsProfile.parse(profile()))
        }
        try {
            waitFor { it.optBoolean("authenticated") && it.optBoolean("canCommand") }
            assertTrue(peer.paired)
            waitFor { it.optString("type") == "state" && peer.queries > 0 }
            for (value in listOf(true, false)) {
                instrumentation.runOnMainSync { engine.command(8, value) }
                val result = waitFor { it.optString("type") == "result" }
                assertEquals(8, result.getInt("dp")); assertTrue(result.getBoolean("confirmed"))
                assertEquals(value, peer.light)
            }
            assertEquals(2, peer.writes)
            val queriesBefore = peer.queries
            // No spontaneous reports: the real timer must request fresh state again.
            waitFor { it.optString("type") == "state" && peer.queries > queriesBefore }
            assertEquals(2, peer.writes)
            instrumentation.runOnMainSync { engine.query() }
            waitFor { it.optString("type") == "state" && peer.queries > queriesBefore + 1 }
        } finally { instrumentation.runOnMainSync { engine.close() } }
    }

    @Test fun wrongVehicleIdentityNeverReceivesPairing() {
        val events = LinkedBlockingQueue<JSONObject>()
        lateinit var engine: YouFsEngine
        val peer = Peer(wrongId = true) { engine.receive(it) }
        instrumentation.runOnMainSync {
            engine = YouFsEngine(context, { id, bytes -> engine.completeWrite(id); peer.receive(bytes) }, { events.offer(it) })
            engine.start(YouFsProfile.parse(profile()))
        }
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
            var error: JSONObject? = null
            while (error == null && System.nanoTime() < deadline) {
                val event = events.poll(100, TimeUnit.MILLISECONDS)
                if (event?.optString("type") == "error") error = event
            }
            assertNotNull(error); assertFalse(peer.paired); assertEquals(0, peer.writes)
        } finally { instrumentation.runOnMainSync { engine.close() } }
    }

    // Device-side simulator, independent JCE crypto; no Bluetooth hardware or real credentials.
    private class Peer(val wrongId: Boolean = false, val deliver: (ByteArray) -> Unit) {
        private val login = md5("abcdef0123456789fake-secret-1234".toByteArray())
        private val key = md5("abcdef0123456789fake-secret-1234srand!".toByteArray())
        private var incoming = ByteArray(0); private var size = 0; private var sequence = 0; private var reportSn = 0
        @Volatile var paired = false
        @Volatile var queries = 0
        @Volatile var writes = 0
        @Volatile var light = false
        fun receive(packet: ByteArray) {
            var at = 0
            fun varint(): Int { var n = 0; var shift = 0; do { val b = packet[at++].toInt() and 255; n = n or ((b and 127) shl shift); shift += 7 } while (b and 128 != 0); return n }
            val index = varint()
            if (index == 0) { size = varint(); assertEquals(2, packet[at++].toInt() ushr 4); incoming = ByteArray(0) }
            incoming += packet.copyOfRange(at, packet.size)
            if (incoming.size != size) return
            val encrypted = incoming; incoming = ByteArray(0)
            val flag = encrypted[0].toInt()
            val cipher = Cipher.getInstance("AES/CBC/NoPadding").apply { init(Cipher.DECRYPT_MODE, SecretKeySpec(if (flag == 14) login else key, "AES"), IvParameterSpec(encrypted.copyOfRange(1, 17))) }
            val plain = cipher.doFinal(encrypted.copyOfRange(17, encrypted.size)); val b = ByteBuffer.wrap(plain)
            val sn = b.int; b.int; val code = b.short.toInt() and 65535; val length = b.short.toInt() and 65535
            val data = plain.copyOfRange(12, 12 + length)
            assertEquals(crc(plain.copyOfRange(0, 12 + length)), ByteBuffer.wrap(plain, 12 + length, 2).short.toInt() and 65535)
            when (code) {
                0 -> {
                    assertEquals("ddd4c0b0a1e1da40c293c605dc7268d5", login.joinToString("") { "%02x".format(it.toInt() and 255) })
                    assertEquals(14, flag); assertArrayEquals(byteArrayOf(0, 20), data)
                    val info = ByteArray(96); info[2] = 4; info[3] = 7; info[4] = 5; info[5] = 1; info[54] = 6
                    "srand!".toByteArray().copyInto(info, 6)
                    (if (wrongId) "different-device" else "0123456789012345678901").toByteArray().copyInto(info, 55)
                    reply(0, sn, info, login, 14)
                }
                1 -> { assertEquals(82, data.size); assertEquals(15, flag); paired = true; reply(1, sn, byteArrayOf(2)); report(byteArrayOf(2,2,0,4,0,0,0,0,3,2,0,4,0,0,0,100,8,1,0,1,0)) }
                3 -> { queries++; reply(3, sn, byteArrayOf(0)); report(byteArrayOf(2,2,0,4,0,0,0,0), 1) }
                39 -> {
                    assertEquals(8, data[5].toInt()); assertEquals(1, data[6].toInt()); assertEquals(1, data[8].toInt())
                    light = data[9].toInt() == 1; writes++
                    reply(39, sn, data.copyOfRange(0, 5) + byteArrayOf(0))
                    reportSn += 17; report(data.copyOfRange(5, data.size), responseTo = sn)
                }
                0x8006 -> { assertEquals(8, data.size); assertEquals(0, data.last().toInt()) }
                else -> throw AssertionError("Unexpected command: $code")
            }
        }
        private fun report(body: ByteArray, type: Int = 128, responseTo: Int = 0) {
            val header = ByteBuffer.allocate(7).put(0).putInt(++reportSn).put(type.toByte()).put(0).array()
            reply(0x8006, responseTo, header + body)
        }
        private fun reply(code: Int, responseTo: Int, data: ByteArray, encryptionKey: ByteArray = key, flag: Int = 15) {
            val plain = ByteArray((14 + data.size + 15) / 16 * 16)
            ByteBuffer.wrap(plain).putInt(++sequence).putInt(responseTo).putShort(code.toShort()).putShort(data.size.toShort()).put(data)
            ByteBuffer.wrap(plain).putShort(12 + data.size, crc(plain.copyOfRange(0, 12 + data.size)).toShort())
            val iv = ByteArray(16) { it.toByte() }
            val cipher = Cipher.getInstance("AES/CBC/NoPadding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(encryptionKey, "AES"), IvParameterSpec(iv)) }
            val payload = byteArrayOf(flag.toByte()) + iv + cipher.doFinal(plain)
            var offset = 0; var index = 0
            fun varint(value: Int): ByteArray {
                var rest = value; val bytes = mutableListOf<Byte>()
                do { val n = rest and 127; rest = rest ushr 7; bytes.add((n or if (rest > 0) 128 else 0).toByte()) } while (rest > 0)
                return bytes.toByteArray()
            }
            while (offset < payload.size) {
                val header = if (index == 0) byteArrayOf(0) + varint(payload.size) + byteArrayOf(0x40) else varint(index)
                val end = minOf(payload.size, offset + 20 - header.size)
                deliver(header + payload.copyOfRange(offset, end)); offset = end; index++
            }
        }
        companion object {
            fun md5(data: ByteArray) = MessageDigest.getInstance("MD5").digest(data)
            fun crc(data: ByteArray): Int { var crc = 65535; for (byte in data) { crc = crc xor (byte.toInt() and 255); repeat(8) { crc = if (crc and 1 != 0) (crc ushr 1) xor 0xa001 else crc ushr 1 } }; return crc }
        }
    }
}
