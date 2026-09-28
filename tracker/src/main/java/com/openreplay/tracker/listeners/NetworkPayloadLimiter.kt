package com.openreplay.tracker.listeners

import org.json.JSONObject
import java.nio.charset.StandardCharsets

/**
 * Keeps a network call's request/response JSON payload under the wire budget so an
 * oversized body (e.g. a multi-MB response proxied through the React Native bridge)
 * can never trip ORMessages.kt's MAX_STRING_LENGTH `require()` and crash the host app.
 *
 * Pure/stateless so it can be unit-tested on the JVM without Android deps. `fit()` can
 * run on a caller's thread (e.g. the RN bridge thread), so it never lets an exception
 * escape - any failure degrades to a minimal fallback JSON instead.
 */
internal object NetworkPayloadLimiter {
    /** Per-side (request or response) budget; two of these plus URL/method/status/duration
     *  must stay comfortably under MessageCollector.maxMessagesSize (500_000 bytes). */
    const val MAX_PAYLOAD_BYTES = 200_000

    private const val FALLBACK = "{}"

    /**
     * Returns [json] unchanged if it already fits [maxBytes]; otherwise truncates its
     * "body" field (if a string) and re-serializes so the result fits. Falls back to a
     * minimal JSON object - keeping "headers" when they fit - if the body can't be found
     * or truncated, isn't a string, or parsing fails (including a top-level JSON array).
     */
    fun fit(json: String, maxBytes: Int = MAX_PAYLOAD_BYTES): String {
        if (fitsBudget(json, maxBytes)) return json

        return try {
            val obj = JSONObject(json)
            when (val body = obj.opt("body")) {
                is String -> fitStringBody(obj, body, maxBytes)
                null -> fallback(obj, utf8ByteSize(json), maxBytes)
                else -> {
                    // Object/array body: nothing sensible to cut inside it - replace it
                    // wholesale with a marker reporting its own serialized size.
                    val bodyBytes = utf8ByteSize(body.toString())
                    obj.put("body", markerFor(bodyBytes))
                    val result = obj.toString()
                    if (fitsBudget(result, maxBytes)) result else fallback(obj, bodyBytes, maxBytes)
                }
            }
        } catch (e: Exception) {
            // Not a JSON object - parse failure, or a top-level JSON array - so there
            // are no "headers" to try to preserve.
            fallbackNoHeaders(utf8ByteSize(json), maxBytes)
        }
    }

    private fun fitStringBody(obj: JSONObject, body: String, maxBytes: Int): String {
        val originalBytes = utf8ByteSize(body)
        val marker = markerFor(originalBytes)

        // Binary-search the largest prefix of body (cut at a UTF-8 code-point boundary)
        // whose re-serialized JSON fits maxBytes. high is capped at maxBytes since every
        // char is at least 1 UTF-8 byte, so no candidate above it could ever fit.
        var low = 0
        var high = minOf(body.length, maxBytes)
        var found = false
        var best = ""
        while (low <= high) {
            val mid = (low + high) / 2
            val candidate = safeCut(body, mid) + marker
            obj.put("body", candidate)
            val serialized = obj.toString()
            if (fitsBudget(serialized, maxBytes)) {
                best = candidate
                found = true
                low = mid + 1
            } else {
                high = mid - 1
            }
        }

        if (!found) return fallback(obj, originalBytes, maxBytes)

        obj.put("body", best)
        val result = obj.toString()
        return if (fitsBudget(result, maxBytes)) result else fallback(obj, originalBytes, maxBytes)
    }

    /** Builds a fallback JSON object reporting [originalBytes], keeping [obj]'s other
     *  fields (e.g. headers) when they still fit; drops them, then gives up to "{}",
     *  if they don't. Respects the caller's [maxBytes], not the default budget. */
    private fun fallback(obj: JSONObject, originalBytes: Int, maxBytes: Int): String {
        obj.put("body", markerFor(originalBytes))
        val withOtherFields = obj.toString()
        if (fitsBudget(withOtherFields, maxBytes)) return withOtherFields
        return fallbackNoHeaders(originalBytes, maxBytes)
    }

    private fun fallbackNoHeaders(originalBytes: Int, maxBytes: Int): String {
        val bodyOnly = JSONObject().put("body", markerFor(originalBytes)).toString()
        return if (fitsBudget(bodyOnly, maxBytes)) bodyOnly else FALLBACK
    }

    private fun markerFor(originalBytes: Int) = "…[truncated: $originalBytes bytes]"

    private fun fitsBudget(s: String, maxBytes: Int): Boolean {
        // Fast path: length * 3 is an upper bound on UTF-8 byte size (max 3 bytes per
        // Java char; surrogate pairs use 2 chars for 4 bytes, so this never underestimates).
        if (s.length.toLong() * 3 <= maxBytes) return true
        return utf8ByteSize(s) <= maxBytes
    }

    private fun utf8ByteSize(s: String): Int = s.toByteArray(StandardCharsets.UTF_8).size

    /** Cuts [s] to at most [maxChars] chars without splitting a surrogate pair. */
    private fun safeCut(s: String, maxChars: Int): String {
        if (maxChars >= s.length) return s
        if (maxChars <= 0) return ""
        var end = maxChars
        // If we'd split a surrogate pair (high surrogate at end-1, low surrogate at end),
        // back off by one so the pair stays together.
        if (Character.isHighSurrogate(s[end - 1]) && end < s.length && Character.isLowSurrogate(s[end])) {
            end -= 1
        }
        return s.substring(0, end)
    }
}
