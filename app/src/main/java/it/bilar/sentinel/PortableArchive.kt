package it.bilar.sentinel

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

internal object PortableArchive {
    const val MAX_FILE = 32 * 1024 * 1024
    const val MAX_CLEAR = 64 * 1024 * 1024
    fun compress(clear: ByteArray): ByteArray {
        require(clear.size <= MAX_CLEAR) { appText(R.string.archive_too_large_2) }
        return ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(clear) } }.toByteArray()
            .also { require(it.size <= MAX_FILE) { appText(R.string.archive_too_large_2) } }
    }
    fun unpack(file: ByteArray): ByteArray {
        require(file.size in 1..MAX_FILE) { appText(R.string.invalid_or_oversized_archive) }
        return GZIPInputStream(ByteArrayInputStream(file)).use { readBounded(it,MAX_CLEAR) }
    }
    fun readBounded(input: java.io.InputStream, max: Int): ByteArray {
        val out = ByteArrayOutputStream(); val buffer = ByteArray(8192)
        while (true) { val count = input.read(buffer); if (count < 0) break; require(out.size().toLong()+count <= max) { appText(R.string.archive_too_large_2) }; out.write(buffer,0,count) }
        return out.toByteArray()
    }
}
