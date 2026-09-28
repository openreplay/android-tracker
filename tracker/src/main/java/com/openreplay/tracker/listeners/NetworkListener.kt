package com.openreplay.tracker.listeners

import android.util.Log
import com.openreplay.tracker.OpenReplay
import com.openreplay.tracker.managers.DebugUtils
import com.openreplay.tracker.managers.MessageCollector
import com.openreplay.tracker.models.script.ORMobileNetworkCall
import java.net.HttpURLConnection
import java.nio.charset.StandardCharsets
import org.json.JSONObject

open class NetworkListener {
    private val startTime: Long = System.currentTimeMillis()
    private var url: String = ""
    private var method: String = "GET"
    private var requestBody: String? = null
    private var requestHeaders: Map<String, String>? = null

    private var ignoredKeys = listOf("password", "token", "secret", "api_key", "apiKey")
    private var ignoredHeaders = listOf(
        "Authorization",
        "Auth",
        "Cookie",
        "Set-Cookie",
        "X-Api-Key",
        "X-Auth-Token"
    )

    // Coarse pre-cut applied here to bound how much raw body we hold/serialize before
    // NetworkPayloadLimiter.fit() (invoked once, in sendNetworkMessage) does the real,
    // budget-accurate truncation on the final JSON. Deliberately larger than fit()'s
    // MAX_PAYLOAD_BYTES so this only kicks in for genuinely huge bodies and fit() is
    // left to do the real work (and own the truncation marker) in the common case.
    private val preCutBytes = 1024 * 1024

    // Thread safety lock
    private val lock = Any()

    constructor()

    constructor(connection: HttpURLConnection) {
        start(connection)
    }

    fun setIgnoredKeys(ignoredKeys: List<String>) {
        this.ignoredKeys = ignoredKeys
    }

    fun setIgnoredHeaders(ignoredHeaders: List<String>) {
        this.ignoredHeaders = ignoredHeaders
    }

    private fun start(connection: HttpURLConnection) {
        try {
            url = connection.url.toString()
            method = connection.requestMethod

            // Capture request headers (these are the headers being sent)
            requestHeaders = connection.requestProperties.mapValues { entry ->
                entry.value.joinToString("; ")
            }

            // Note: HttpURLConnection doesn't provide easy access to request body after it's written.
            // Request body should be set externally using setRequestBody() method before the request is sent.
            // Attempting to read from inputStream here is incorrect as that's for response data.
            DebugUtils.log("NetworkListener started: $method $url")
        } catch (e: Exception) {
            DebugUtils.error("Error in NetworkListener.start: ${e.message}")
        }
    }

    /**
     * Set the request body manually. This should be called before the request is sent.
     * Use this when you have access to the request body data.
     */
    fun setRequestBody(body: String?) {
        synchronized(lock) {
            this.requestBody = body?.let { cutStringToUtf8Bytes(it, preCutBytes) }
        }
    }

    fun finish(connection: HttpURLConnection?, data: ByteArray?) {
        synchronized(lock) {
            try {
                val endTime = System.currentTimeMillis()

                // Coarse pre-cut only; NetworkPayloadLimiter.fit() (in sendNetworkMessage)
                // does the final, budget-accurate truncation and owns the marker text.
                val responseBody = when {
                    data == null -> null
                    data.size <= preCutBytes -> data.toString(StandardCharsets.UTF_8)
                    else -> utf8SafeCut(data, preCutBytes).toString(StandardCharsets.UTF_8)
                }

                val requestContent = mapOf(
                    "body" to sanitizeBody(requestBody),
                    "headers" to sanitizeHeaders(requestHeaders)
                )

                // Transform the headers from Map<String, List<String>> to Map<String, String>
                val responseHeaders = try {
                    connection?.headerFields?.mapValues { it.value.joinToString("; ") }?.filterKeys { it != null } as? Map<String, String> ?: emptyMap()
                } catch (e: Exception) {
                    DebugUtils.error("Error reading response headers: ${e.message}")
                    emptyMap()
                }

                val responseContent = mapOf(
                    "body" to sanitizeBody(responseBody),
                    "headers" to sanitizeHeaders(responseHeaders)
                )

                val requestJSON = JSONObject(requestContent).toString()
                val responseJSON = JSONObject(responseContent).toString()

                val status = connection?.responseCode ?: 0
                val duration = endTime - startTime

                DebugUtils.log("Network call completed: $method $url - Status: $status, Duration: ${duration}ms")

                sendNetworkMessage(url, method, requestJSON, responseJSON, status, duration.toULong())
            } catch (e: Exception) {
                DebugUtils.error("Error in NetworkListener.finish: ${e.message}")
            }
        }
    }

    private fun sanitizeHeaders(headers: Map<String, String>?): Map<String, String>? {
        return headers?.mapValues { (key, value) ->
            // Case-insensitive header matching
            if (ignoredHeaders.any { it.equals(key, ignoreCase = true) }) {
                "***"
            } else {
                value
            }
        }
    }

    private fun sanitizeBody(body: String?): String? {
        if (body.isNullOrBlank()) return body

        try {
            var sanitizedBody = body
            ignoredKeys.forEach { key ->
                // Handle various JSON formats
                // Handles: "key":"value", "key": "value", "key":"value with spaces"
                sanitizedBody = sanitizedBody?.replace(
                    "\"$key\"\\s*:\\s*\"[^\"]*\"".toRegex(RegexOption.IGNORE_CASE),
                    "\"$key\": \"***\""
                )
                // Handle non-string values: "key":123, "key":true, "key":null
                sanitizedBody = sanitizedBody?.replace(
                    "\"$key\"\\s*:\\s*[^,}\\]]*".toRegex(RegexOption.IGNORE_CASE),
                    "\"$key\": \"***\""
                )
                // Handle form data: key=value
                sanitizedBody = sanitizedBody?.replace(
                    "$key=[^&]*".toRegex(RegexOption.IGNORE_CASE),
                    "$key=***"
                )
            }
            return sanitizedBody
        } catch (e: Exception) {
            DebugUtils.error("Error sanitizing body: ${e.message}")
            return "[Error sanitizing body]"
        }
    }
}

/**
 * Cuts [data] to at most [maxBytes] without splitting a multi-byte UTF-8 sequence.
 * Looks at the last byte that would be kept (index maxBytes - 1): walks back over up
 * to 3 continuation bytes (0b10xxxxxx) to find that byte's sequence leader, then - if
 * the leader's full sequence would extend past maxBytes - drops the whole sequence.
 * Avoids boxing to a List<Byte> (no `.take()`) for a multi-MB array.
 */
internal fun utf8SafeCut(data: ByteArray, maxBytes: Int): ByteArray {
    if (maxBytes >= data.size) return data
    if (maxBytes <= 0) return ByteArray(0)

    var i = maxBytes - 1
    var stepsBack = 0
    while (i > 0 && (data[i].toInt() and 0xC0) == 0x80 && stepsBack < 3) {
        i--
        stepsBack++
    }

    val leader = data[i].toInt() and 0xFF
    val seqLen = when {
        leader and 0x80 == 0x00 -> 1 // ASCII
        leader and 0xE0 == 0xC0 -> 2
        leader and 0xF0 == 0xE0 -> 3
        leader and 0xF8 == 0xF0 -> 4
        else -> 1 // stray/invalid continuation byte: treat as a single byte
    }

    val end = if (i + seqLen > maxBytes) i else maxBytes
    return data.copyOf(end)
}

/** UTF-8-byte-safe cut of a String to at most [maxBytes] bytes (never chars), so a
 *  surrogate pair or multi-byte character can't be split. */
internal fun cutStringToUtf8Bytes(s: String, maxBytes: Int): String {
    val bytes = s.toByteArray(StandardCharsets.UTF_8)
    if (bytes.size <= maxBytes) return s
    return utf8SafeCut(bytes, maxBytes).toString(StandardCharsets.UTF_8)
}

/**
 * Builds the ORMobileNetworkCall message for a completed network call, fitting the
 * request/response JSON to the wire budget first. Pure (no MessageCollector/Android
 * dependency) so it's unit-testable on the JVM.
 */
internal fun buildNetworkCallMessage(
    url: String,
    method: String,
    requestJSON: String,
    responseJSON: String,
    status: Int,
    duration: ULong
): ORMobileNetworkCall {
    return ORMobileNetworkCall(
        type = "request",
        method = method,
        URL = url,
        request = NetworkPayloadLimiter.fit(requestJSON),
        response = NetworkPayloadLimiter.fit(responseJSON),
        status = status,
        duration = duration
    )
}

/**
 * Chokepoint for network call reporting: the native [NetworkListener], the React
 * Native bridge, and `OpenReplay.networkRequest` all funnel through here, so fitting
 * the payloads here (rather than in [NetworkListener.finish]) guarantees every caller
 * gets the same oversized-body protection.
 */
fun sendNetworkMessage(
    url: String,
    method: String,
    requestJSON: String,
    responseJSON: String,
    status: Int,
    duration: ULong
) {
    MessageCollector.sendMessage(
        buildNetworkCallMessage(url, method, requestJSON, responseJSON, status, duration)
    )
}
