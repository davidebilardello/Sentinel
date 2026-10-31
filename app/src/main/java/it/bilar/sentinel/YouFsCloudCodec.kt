package it.bilar.sentinel

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

// OEM application identifiers are public protocol constants, not account or vehicle credentials.
internal object YouFsCloudCodec {
    const val APP_KEY = "vrx3vx5nan54x8w9sx44"
    private val signingKey = listOf("com.yongfengshun", "A3:55:F5:F8:2C:AC:E6:07:09:86:2C:66:22:C7:5F:CD:3C:4B:F7:46:60:BF:58:65:E0:44:A0:25:CF:29:E2:D4",
        "yvu7g9mgnjphc5sqv7hc9eh9ryraxmfv", "vtk5vjye4vss4shmwahxxngectfe8uqg").joinToString("_").toByteArray()
    private val signedFields = setOf("a", "v", "lat", "lon", "lang", "deviceId", "appVersion", "ttid", "isH5", "h5Token", "os", "clientId", "postData", "time", "requestId", "et", "n4h5", "sid", "chKey", "sp")
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun hmac(key: ByteArray, bytes: ByteArray) = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(bytes)
    fun md5(value: String) = MessageDigest.getInstance("MD5").digest(value.toByteArray(Charsets.UTF_8)).hex()
    fun identity(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).hex()
    fun payloadKey(requestId: String, ecode: String? = null): ByteArray = hmac(requestId.toByteArray(),
        if (ecode.isNullOrEmpty()) signingKey else signingKey + "_$ecode".toByteArray()).hex().take(16).toByteArray()
    fun signature(parameters: Map<String, String>): String {
        val text = parameters.filter { it.key in signedFields && it.value.isNotEmpty() }.toSortedMap().entries.joinToString("||") { (key, value) ->
            val signedValue = if (key == "postData") md5(value).let { it.substring(8,16) + it.substring(0,8) + it.substring(24,32) + it.substring(16,24) } else value
            "$key=$signedValue"
        }
        return hmac(signingKey, text.toByteArray()).hex()
    }
    fun encrypt(plain: ByteArray, key: ByteArray, nonce: ByteArray = ByteArray(12).also(SecureRandom()::nextBytes)): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce)) }
        return Base64.getEncoder().encodeToString(nonce + cipher.doFinal(plain))
    }
    fun decrypt(encoded: String, key: ByteArray): ByteArray {
        require(encoded.length <= 6_000_000)
        val bytes = Base64.getDecoder().decode(encoded)
        require(bytes.size >= 28)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, bytes.copyOfRange(0,12))) }
        return cipher.doFinal(bytes.copyOfRange(12,bytes.size))
    }
}
