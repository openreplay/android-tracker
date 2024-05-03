package com.openreplay.tracker.listeners

import android.util.Log
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

    private var ignoredKeys = listOf("password")
    private var ignoredHeaders = listOf("Authentication", "Auth")

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
        url = connection.url.toString()
        method = connection.requestMethod
        requestHeaders = connection.headerFields.mapValues {
            // Joining all header values with a semicolon if there are multiple values for the same header
            it.value.joinToString("; ")
        }.filterKeys {
            // Excluding the null key that HttpURLConnection uses for the response line
            it != null
        } as Map<String, String>

        connection.inputStream?.let {
            requestBody = it.readBytes().toString(StandardCharsets.UTF_8)
        } ?: run {
            requestBody = ""
            Log.d("DebugUtils", "error getting request body (start request)")
        }
    }

    fun finish(connection: HttpURLConnection?, data: ByteArray?) {
        val endTime = System.currentTimeMillis()
        val responseBody = data?.toString(StandardCharsets.UTF_8)

        val requestContent = mapOf(
            "body" to sanitizeBody(requestBody),
            "headers" to sanitizeHeaders(requestHeaders)
        )

        // Transform the headers from Map<String, List<String>> to Map<String, String>
        val responseHeaders = connection?.headerFields?.mapValues { it.value.joinToString("; ") } ?: emptyMap()
        val responseContent = mapOf(
            "body" to sanitizeBody(responseBody),
            "headers" to sanitizeHeaders(responseHeaders)
        )

        val requestJSON = JSONObject(requestContent).toString()
        val responseJSON = JSONObject(responseContent).toString()

        val status = connection?.responseCode ?: 0
        val duration = endTime - startTime

        sendNetworkMessage(url, method, requestJSON, responseJSON, status, duration)
    }

    private fun sanitizeHeaders(headers: Map<String, String>?): Map<String, String>? {
        return headers?.mapValues { (key, value) ->
            if (ignoredHeaders.contains(key)) "***" else value
        }
    }

    private fun sanitizeBody(body: String?): String? {
        var sanitizedBody = body
        ignoredKeys.forEach { key ->
            sanitizedBody = sanitizedBody?.replace("\"$key\":\"[^\"]*\"".toRegex(), "\"$key\":\"***\"")
        }
        return sanitizedBody
    }
}

fun sendNetworkMessage(
    url: String,
    method: String,
    requestJSON: String,
    responseJSON: String,
    status: Int,
    duration: Long
) {
    val message = ORMobileNetworkCall(
        type = "request",
        method = method,
        URL = url,
        request = requestJSON,
        response = responseJSON,
        status = status.toULong(),
        duration = duration.toULong()
    )

    MessageCollector.sendMessage(message)
}
