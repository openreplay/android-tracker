package com.openreplay.tracker

import com.openreplay.tracker.listeners.NetworkPayloadLimiter
import com.openreplay.tracker.listeners.buildNetworkCallMessage
import com.openreplay.tracker.listeners.utf8SafeCut
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets

class NetworkPayloadLimiterTest {

    private fun utf8Bytes(s: String) = s.toByteArray(StandardCharsets.UTF_8).size

    @Test
    fun `oversized string body is truncated to a valid JSON object under budget with a size marker and headers kept`() {
        val hugeBody = "x".repeat(5 * 1024 * 1024)
        val json = JSONObject(mapOf("body" to hugeBody, "headers" to mapOf("Content-Type" to "text/plain"))).toString()

        val result = NetworkPayloadLimiter.fit(json)

        assertTrue(utf8Bytes(result) <= NetworkPayloadLimiter.MAX_PAYLOAD_BYTES)
        val parsed = JSONObject(result) // throws if not valid JSON
        val body = parsed.getString("body")
        assertTrue(body.contains("[truncated: ${hugeBody.length} bytes]"))
        assertEquals("text/plain", parsed.getJSONObject("headers").getString("Content-Type"))
    }

    @Test
    fun `multi-byte characters at the cut boundary are not corrupted`() {
        // Repeats of a 4-byte emoji and a 2-byte Cyrillic char so any naive char-count
        // cut is very likely to land mid-sequence.
        val hugeBody = ("😀" + "б").repeat(400_000) // ~5 chars * 400k
        val json = JSONObject(mapOf("body" to hugeBody)).toString()

        val result = NetworkPayloadLimiter.fit(json)

        assertTrue(utf8Bytes(result) <= NetworkPayloadLimiter.MAX_PAYLOAD_BYTES)
        val parsed = JSONObject(result)
        val body = parsed.getString("body")
        // Every char in the truncated prefix (before the marker) must be well-formed:
        // re-encoding to UTF-8 and back must not introduce replacement characters.
        val prefix = body.substringBefore("…[truncated")
        val roundTripped = String(prefix.toByteArray(StandardCharsets.UTF_8), StandardCharsets.UTF_8)
        assertEquals(prefix, roundTripped)
        assertTrue(!prefix.contains('�'))
    }

    @Test
    fun `non-JSON input over budget falls back to valid JSON under budget`() {
        val garbage = "not json at all ".repeat(50_000)
        assertTrue(utf8Bytes(garbage) > NetworkPayloadLimiter.MAX_PAYLOAD_BYTES)

        val result = NetworkPayloadLimiter.fit(garbage)

        assertTrue(utf8Bytes(result) <= NetworkPayloadLimiter.MAX_PAYLOAD_BYTES)
        JSONObject(result) // must parse without throwing
    }

    @Test
    fun `top-level JSON array over budget falls back to valid JSON under budget`() {
        val hugeArray = "[" + (1..500_000).joinToString(",") + "]"
        assertTrue(utf8Bytes(hugeArray) > NetworkPayloadLimiter.MAX_PAYLOAD_BYTES)

        val result = NetworkPayloadLimiter.fit(hugeArray)

        assertTrue(utf8Bytes(result) <= NetworkPayloadLimiter.MAX_PAYLOAD_BYTES)
        JSONObject(result)
    }

    @Test
    fun `object body over budget is replaced with a marker while headers are kept`() {
        val nestedObject = JSONObject(mapOf("nested" to "y".repeat(300_000)))
        val json = JSONObject()
            .put("body", nestedObject)
            .put("headers", JSONObject(mapOf("Content-Type" to "application/json")))
            .toString()
        assertTrue(utf8Bytes(json) > NetworkPayloadLimiter.MAX_PAYLOAD_BYTES)

        val result = NetworkPayloadLimiter.fit(json)

        assertTrue(utf8Bytes(result) <= NetworkPayloadLimiter.MAX_PAYLOAD_BYTES)
        val parsed = JSONObject(result)
        assertTrue(parsed.getString("body").contains("truncated"))
        assertEquals("application/json", parsed.getJSONObject("headers").getString("Content-Type"))
    }

    @Test
    fun `under-budget input is returned unchanged`() {
        val small = JSONObject(mapOf("body" to "hello", "headers" to emptyMap<String, String>())).toString()

        val result = NetworkPayloadLimiter.fit(small)

        assertEquals(small, result)
    }

    @Test
    fun `heavy escaping does not push the result over budget`() {
        // Lots of quotes/backslashes inflate size a lot when re-serialized as JSON.
        val nasty = "\"\\\"\\".repeat(300_000)
        val json = JSONObject(mapOf("body" to nasty)).toString()
        assertTrue(utf8Bytes(json) > NetworkPayloadLimiter.MAX_PAYLOAD_BYTES)

        val result = NetworkPayloadLimiter.fit(json)

        assertTrue(utf8Bytes(result) <= NetworkPayloadLimiter.MAX_PAYLOAD_BYTES)
        JSONObject(result)
    }

    @Test
    fun `sendNetworkMessage's fitting builds a message whose contentData never exceeds the wire budget`() {
        // Goes through the real production helper (buildNetworkCallMessage, called by
        // sendNetworkMessage) rather than re-implementing the fitting call in the test,
        // so it also covers the RN-bridge / OpenReplay#networkRequest path, not just
        // the native NetworkListener.finish() path.
        val hugeRequest = JSONObject(mapOf("body" to "r".repeat(5 * 1024 * 1024))).toString()
        val hugeResponse = JSONObject(mapOf("body" to "s".repeat(5 * 1024 * 1024))).toString()

        val message = buildNetworkCallMessage(
            url = "https://example.com/api",
            method = "GET",
            requestJSON = hugeRequest,
            responseJSON = hugeResponse,
            status = 200,
            duration = 42uL
        )

        val data = message.contentData()

        assertTrue(data.size <= 500_000)
    }

    // --- utf8SafeCut -------------------------------------------------------------

    /** Decodes with UTF-8's strict/replacing decoder and asserts no U+FFFD appears,
     *  i.e. every byte kept forms a complete, valid code point - and that the result
     *  is a byte-for-byte prefix of the original. */
    private fun assertCleanPrefix(original: ByteArray, cut: ByteArray) {
        assertTrue(cut.size <= original.size)
        assertEquals(original.toList().subList(0, cut.size), cut.toList())
        val decoded = String(cut, StandardCharsets.UTF_8)
        assertTrue(!decoded.contains('�'))
    }

    @Test
    fun `utf8SafeCut never splits a 3-byte sequence - aa euro euro euro euro`() {
        val s = "aa€€€€" // 'a','a', four 3-byte euro signs
        val bytes = s.toByteArray(StandardCharsets.UTF_8)
        for (maxBytes in 0..bytes.size) {
            assertCleanPrefix(bytes, utf8SafeCut(bytes, maxBytes))
        }
    }

    @Test
    fun `utf8SafeCut never splits a 4-byte sequence - a emoji emoji`() {
        val s = "a😀😀" // 'a', two 4-byte emoji
        val bytes = s.toByteArray(StandardCharsets.UTF_8)
        for (maxBytes in 0..bytes.size) {
            assertCleanPrefix(bytes, utf8SafeCut(bytes, maxBytes))
        }
    }

    @Test
    fun `utf8SafeCut exhaustively handles every cut point of mixed ASCII 2-3-4-byte text`() {
        // ASCII + 2-byte (Cyrillic) + 3-byte (euro) + 4-byte (emoji), repeated.
        val s = "aб€😀bб€😀".repeat(50)
        val bytes = s.toByteArray(StandardCharsets.UTF_8)
        for (maxBytes in 0..bytes.size) {
            assertCleanPrefix(bytes, utf8SafeCut(bytes, maxBytes))
        }
    }

    @Test
    fun `utf8SafeCut returns data unchanged when maxBytes at or above size`() {
        val bytes = "hello €".toByteArray(StandardCharsets.UTF_8)
        assertEquals(bytes.toList(), utf8SafeCut(bytes, bytes.size).toList())
        assertEquals(bytes.toList(), utf8SafeCut(bytes, bytes.size + 10).toList())
    }
}
