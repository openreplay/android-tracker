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

    // Maximum size for captured request/response bodies (1MB)
    private val maxBodySize = 1024 * 1024

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
            this.requestBody = body?.take(maxBodySize)
        }
    }

    fun finish(connection: HttpURLConnection?, data: ByteArray?) {
        synchronized(lock) {
            try {
                val endTime = System.currentTimeMillis()
                
                // Limit response body size
                val responseBody = if (data != null && data.size <= maxBodySize) {
                    data.toString(StandardCharsets.UTF_8)
                } else if (data != null && data.size > maxBodySize) {
                    DebugUtils.log("Response body too large (${data.size} bytes), truncating")
                    data.take(maxBodySize).toByteArray().toString(StandardCharsets.UTF_8) + "... [truncated]"
                } else {
                    null
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

fun sendNetworkMessage(
    url: String,
    method: String,
    requestJSON: String,
    responseJSON: String,
    status: Int,
    duration: ULong
) {
    val message = ORMobileNetworkCall(
        type = "request",
        method = method,
        URL = url,
        request = requestJSON,
        response = responseJSON,
        status = status,
        duration = duration
    )

    MessageCollector.sendMessage(message)
}
