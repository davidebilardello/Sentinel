package it.bilar.sentinel

import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class YouFsCloudCodecTest {
    private val requestId = "ec668b4c-3a4d-4d45-a0ac-722712ab9155"
    @Test fun independentPythonVectorsMatchAndTamperedCiphertextFails() {
        // Frozen with Python hashlib/hmac and cryptography.AESGCM, not the Kotlin implementation.
        val key = YouFsCloudCodec.payloadKey(requestId)
        assertEquals("13901993e32f0b9d", String(key))
        assertEquals("ca8c79ed8bbeb8e0", String(YouFsCloudCodec.payloadKey(requestId,"ecode00000000000")))
        val plain = "{\"success\":true,\"result\":{\"ok\":1}}".toByteArray()
        val encoded = "AAECAwQFBgcICQoLHo6I4Zb26k0k21T2MpBR0pRLx09gYfOOLzw90KXQrX9aDAsy7zcB2h7/40Iv00xA/0I="
        assertEquals(encoded, YouFsCloudCodec.encrypt(plain,key,ByteArray(12) { it.toByte() }))
        assertArrayEquals(plain,YouFsCloudCodec.decrypt(encoded,key))
        val bad = Base64.getDecoder().decode(encoded); bad[bad.lastIndex] = (bad.last().toInt() xor 1).toByte()
        try { YouFsCloudCodec.decrypt(Base64.getEncoder().encodeToString(bad),key); fail("Invalid GCM tag accepted") } catch (_: javax.crypto.AEADBadTagException) { }
    }
    @Test fun signatureUsesTheEncryptedPayloadAndOnlyTheSigningFields() {
        val parameters = mapOf("a" to "smartlife.m.device.get", "v" to "1.0", "deviceId" to "fake-install", "requestId" to requestId,
            "time" to "1700000000", "postData" to "YWJj", "chKey" to "3fc2a062", "clientId" to "vrx3vx5nan54x8w9sx44")
        assertEquals("5315943b7d32768c36c743e14bdceee82b9b2d44bab949eb5711128c84f4aa23", YouFsCloudCodec.signature(parameters))
        assertEquals(YouFsCloudCodec.signature(parameters),YouFsCloudCodec.signature(parameters + mapOf("gid" to "123", "cp" to "gzip")))
        assertNotEquals(YouFsCloudCodec.signature(parameters),YouFsCloudCodec.signature(parameters + ("postData" to "other")))
    }
}
