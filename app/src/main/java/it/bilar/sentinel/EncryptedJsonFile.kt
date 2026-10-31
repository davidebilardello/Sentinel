package it.bilar.sentinel

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal class EncryptedJsonFile(context: Context, name: String) {
    private val target = AtomicFile(File(context.filesDir, name))
    private fun key(): SecretKey {
        val alias = "sentinel-youfs-profile-v1"
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun write(clear: ByteArray) {
        require(clear.size <= 1_000_000)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
            val encrypted = cipher.doFinal(clear)
            val stream = target.startWrite()
            try { stream.write(cipher.iv + encrypted); target.finishWrite(stream) }
            catch (e: Exception) { target.failWrite(stream); throw e }
        } finally { clear.fill(0) }
    }
    fun read(): ByteArray? {
        if (!target.baseFile.exists()) return null
        require(target.baseFile.length() in 29..1_000_064)
        val bytes = target.readFully()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12))) }
        return cipher.doFinal(bytes.copyOfRange(12, bytes.size))
    }
    fun delete() = target.delete()
}
