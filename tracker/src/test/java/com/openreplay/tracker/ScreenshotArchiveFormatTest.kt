package com.openreplay.tracker

import com.openreplay.tracker.managers.ScreenshotArchiveFormat
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

class ScreenshotArchiveFormatTest {

    // record = [ timestamp : uint64 LE ][ size : uint32 LE ][ jpeg bytes (size) ]
    @Test
    fun `frame record is little-endian timestamp, size, then payload`() {
        val out = ByteArrayOutputStream()
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x01, 0x02, 0x03)

        ScreenshotArchiveFormat.writeFrameRecord(out, timestamp = 1_700_000_000_123L, jpeg = jpeg)

        val bytes = out.toByteArray()
        assertEquals(8 + 4 + jpeg.size, bytes.size)
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(1_700_000_000_123L, buf.long)
        assertEquals(jpeg.size, buf.int)
        assertArrayEquals(jpeg, bytes.copyOfRange(12, bytes.size))
    }

    @Test
    fun `frame record byte layout matches iOS appendUInt64LE and appendUInt32LE`() {
        val out = ByteArrayOutputStream()
        ScreenshotArchiveFormat.writeFrameRecord(out, timestamp = 0x0102030405060708L, jpeg = byteArrayOf(0x42))

        val expected = byteArrayOf(
            0x08, 0x07, 0x06, 0x05, 0x04, 0x03, 0x02, 0x01, // ts, LE
            0x01, 0x00, 0x00, 0x00,                         // size = 1, LE
            0x42,
        )
        assertArrayEquals(expected, out.toByteArray())
    }

    @Test
    fun `concatenated records survive a gzip round trip`() {
        val raw = ByteArrayOutputStream()
        GZIPOutputStream(raw).use { gz ->
            ScreenshotArchiveFormat.writeFrameRecord(gz, 10L, byteArrayOf(1, 2))
            ScreenshotArchiveFormat.writeFrameRecord(gz, 20L, byteArrayOf(3))
        }

        val decoded = GZIPInputStream(ByteArrayInputStream(raw.toByteArray())).readBytes()
        val buf = ByteBuffer.wrap(decoded).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(10L, buf.long); assertEquals(2, buf.int); assertEquals(1.toByte(), buf.get()); assertEquals(2.toByte(), buf.get())
        assertEquals(20L, buf.long); assertEquals(1, buf.int); assertEquals(3.toByte(), buf.get())
        assertEquals(0, buf.remaining())
    }

    @Test
    fun `frames archive is named sessionId-lastTs gz`() {
        assertEquals("s1-500.gz", ScreenshotArchiveFormat.archiveName("s1", "500", frames = true))
    }

    @Test
    fun `tar archive is named sessionId-lastTs tar gz`() {
        assertEquals("s1-500.tar.gz", ScreenshotArchiveFormat.archiveName("s1", "500", frames = false))
    }

    @Test
    fun `finished archives are recognised by either suffix but not tmp`() {
        assertEquals(true, ScreenshotArchiveFormat.isFinishedArchive("s1-500.gz"))
        assertEquals(true, ScreenshotArchiveFormat.isFinishedArchive("s1-500.tar.gz"))
        assertEquals(false, ScreenshotArchiveFormat.isFinishedArchive("s1-500.gz.tmp"))
        assertEquals(false, ScreenshotArchiveFormat.isFinishedArchive("s1-500.tar.gz.tmp"))
    }

    @Test
    fun `archive timestamp is parsed from either suffix for oldest-first ordering`() {
        assertEquals(500L, ScreenshotArchiveFormat.archiveTimestamp("s1-500.gz"))
        assertEquals(600L, ScreenshotArchiveFormat.archiveTimestamp("s1-600.tar.gz"))
        assertEquals(Long.MAX_VALUE, ScreenshotArchiveFormat.archiveTimestamp("garbage.gz"))
    }
}
