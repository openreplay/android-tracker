package com.openreplay.tracker

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.openreplay.tracker.listeners.buildNetworkCallMessage
import com.openreplay.tracker.managers.MessageCollector
import com.openreplay.tracker.models.script.ORMobileEvent
import com.openreplay.tracker.models.script.ORMobileLog
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.charset.StandardCharsets

/**
 * Reproduces GitHub issue #11 on a real Android runtime (real android.util.Log and a
 * real, non-stubbed org.json.JSONObject - unlike the local JVM unit tests) and
 * verifies the fix.
 *
 * No `OpenReplay.start()`/session is needed: every path exercised here only checks
 * `MessageCollector`'s `isPaused` flag (false by default; the test never calls
 * `pause()`), not whether a session/collector has been started. So `sendMessage()`
 * always falls through to `message.contentData()` on the caller's thread. On `main`
 * (pre-fix) that guard doesn't exist either, so an oversized string reaches
 * `fromValues()`'s `require()` and throws synchronously - which is exactly what
 * lets test (a)/(b) below fail with `IllegalArgumentException: String too long` on
 * `main` and pass on the fix branch: if `contentData()` were never reached, the
 * oversized input would silently not throw on `main` and the "before" run would
 * give a false negative.
 */
@RunWith(AndroidJUnit4::class)
class OversizedMessageInstrumentedTest {

    // 5 MB, comfortably over ORMessages.kt's private MAX_STRING_LENGTH (1_048_576 bytes).
    private val fiveMb = 5 * 1024 * 1024

    @Test
    fun networkRequest_withFiveMegabyteResponse_doesNotThrow() {
        val responseBody = "x".repeat(fiveMb)
        val responseJSON = JSONObject(
            mapOf("body" to responseBody, "headers" to mapOf("Content-Type" to "text/plain"))
        ).toString()

        // Public API entry point used by the React Native bridge / host apps.
        OpenReplay.networkRequest(
            url = "https://example.invalid/api",
            method = "GET",
            requestJSON = "{}",
            responseJSON = responseJSON,
            status = 200,
            duration = 123uL
        )
        // No exception => pass. (On main this throws IllegalArgumentException.)
    }

    @Test
    fun sendMessage_withOversizedLogAndEvent_doesNotThrow() {
        val hugeContent = "y".repeat(fiveMb)

        MessageCollector.sendMessage(ORMobileLog(severity = "error", content = hugeContent))
        MessageCollector.sendMessage(ORMobileEvent(name = "huge_event", payload = hugeContent))
        // No exception => pass. (On main this throws IllegalArgumentException.)
    }

    @Test
    fun buildNetworkCallMessage_withFiveMegabyteResponse_fitsWireBudgetAndParsesWithAndroidJson() {
        // Quotes, backslashes, emoji and Cyrillic so escaping and multi-byte cut
        // boundaries are exercised on the real android.icu / org.json stack, not the
        // JVM unit-test stub.
        val nasty = "\"quote\\slash/π漢字😀б".repeat(200_000) // ~5+ MB
        assertTrue(nasty.toByteArray(StandardCharsets.UTF_8).size > fiveMb)

        val responseJSON = JSONObject(
            mapOf("body" to nasty, "headers" to mapOf("Content-Type" to "application/json"))
        ).toString()

        val message = buildNetworkCallMessage(
            url = "https://example.invalid/api",
            method = "POST",
            requestJSON = "{}",
            responseJSON = responseJSON,
            status = 200,
            duration = 42uL
        )

        val data = message.contentData()
        assertTrue("contentData() was ${data.size} bytes", data.size <= 500_000)

        // The fitted response, as actually stored on the message, must itself be
        // valid Android org.json, keep headers, and carry the truncation marker.
        val fittedResponse = message.response
        val parsed = JSONObject(fittedResponse)
        assertEquals("application/json", parsed.getJSONObject("headers").getString("Content-Type"))
        assertTrue(parsed.getString("body").contains("[truncated:"))
    }
}
