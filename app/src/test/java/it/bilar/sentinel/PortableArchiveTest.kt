package it.bilar.sentinel

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.zip.GZIPInputStream

class PortableArchiveTest {
    @Test fun compressesAndReadsWithoutPasswordOrEncryption() {
        val clear = "{\"archive\":{\"records\":[]}}".toByteArray()
        val file = PortableArchive.compress(clear)
        assertEquals(0x1f,file[0].toInt() and 255); assertEquals(0x8b,file[1].toInt() and 255)
        assertArrayEquals(clear,GZIPInputStream(ByteArrayInputStream(file)).use { it.readBytes() })
        assertArrayEquals(clear,PortableArchive.unpack(file))
    }
    @Test fun rejectsInvalidOrTruncatedArchives() {
        assertThrows(IllegalArgumentException::class.java) { PortableArchive.unpack(byteArrayOf()) }
        assertThrows(IOException::class.java) { PortableArchive.unpack("not-an-archive".toByteArray()) }
        val file = PortableArchive.compress("trip-data".toByteArray())
        assertThrows(IOException::class.java) { PortableArchive.unpack(file.copyOf(file.size-4)) }
    }
    @Test fun boundsBothPlainAndDecompressedStreams() {
        assertThrows(IllegalArgumentException::class.java) { PortableArchive.readBounded(ByteArrayInputStream(ByteArray(8193)),8192) }
        assertEquals(8192,PortableArchive.readBounded(ByteArrayInputStream(ByteArray(8192)),8192).size)
        val file = PortableArchive.compress(ByteArray(8193))
        GZIPInputStream(ByteArrayInputStream(file)).use { stream -> assertThrows(IllegalArgumentException::class.java) { PortableArchive.readBounded(stream,8192) } }
    }
}
