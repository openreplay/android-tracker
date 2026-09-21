package com.openreplay.tracker

import com.openreplay.tracker.models.script.ORMobileNetworkCall
import com.openreplay.tracker.models.script.fromValues
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Decodes ORMobileNetworkCall exactly the way the OpenReplay backend does
 * (backend/pkg/messages/read-message.go, DecodeMobileNetworkCall):
 * type byte, then varint Timestamp, varint Length, then
 * string Type, Method, URL, Request, Response, varint Status, varint Duration.
 */
class ORMobileNetworkCallWireFormatTest {

    private class BackendReader(private val buf: ByteArray) {
        var pos = 0
        fun readByte(): Int = buf[pos++].toInt() and 0xFF
        fun readUint(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                val b = readByte()
                result = result or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
            }
        }
        fun readString(): String {
            val len = readUint().toInt()
            val s = String(buf, pos, len, Charsets.UTF_8)
            pos += len
            return s
        }
        fun remaining(): Int = buf.size - pos
    }

    @Test
    fun `backend decodes request, response, status and duration from the android payload`() {
        val msg = ORMobileNetworkCall(
            type = "request",
            method = "POST",
            URL = "https://api.example.com/login",
            request = "{\"user\":\"alice\"}",
            response = "{\"token\":\"abc\"}",
            status = 200,
            duration = 345uL,
        )

        val r = BackendReader(msg.contentData())
        assertEquals(105, r.readByte())
        assertEquals(msg.timestamp.toLong(), r.readUint())
        val length = r.readUint()
        val bodyStart = r.pos

        assertEquals("request", r.readString())
        assertEquals("POST", r.readString())
        assertEquals("https://api.example.com/login", r.readString())
        assertEquals("request body must come before response body", "{\"user\":\"alice\"}", r.readString())
        assertEquals("response body must come after request body", "{\"token\":\"abc\"}", r.readString())
        assertEquals("status must be a varint", 200L, r.readUint())
        assertEquals("duration must directly follow a varint status", 345L, r.readUint())

        assertEquals("declared Length must equal the decoded body", length, (r.pos - bodyStart).toLong())
        assertEquals("no trailing bytes after duration", 0, r.remaining())
    }

    @Test
    fun `a raw Int reaching the encoder is written as a varint, never fixed width`() {
        assertArrayEquals(byteArrayOf(0xC8.toByte(), 0x01), fromValues(200))
        assertArrayEquals(fromValues(200uL), fromValues(200))
        assertArrayEquals(byteArrayOf(0x00), fromValues(0))
    }
}
