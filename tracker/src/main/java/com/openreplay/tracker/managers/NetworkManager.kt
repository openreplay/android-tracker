package com.openreplay.tracker.managers

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.TrafficStats
import android.os.Build
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.openreplay.tracker.OpenReplay
import com.openreplay.tracker.models.SessionResponse
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.zip.GZIPOutputStream

object NetworkManager {
    private const val START_URL = "/v1/mobile/start"
    private const val INGEST_URL = "/v1/mobile/i"
    private const val LATE_URL = "/v1/mobile/late"
    private const val IMAGES_URL = "/v1/mobile/images"
    private const val CONDITIONS = "/v1/mobile/conditions"
    
    // Network configuration
    private const val THREAD_STATS_TAG = 1000
    private const val CONNECT_TIMEOUT_MS = 30000 // 30 seconds
    private const val READ_TIMEOUT_MS = 30000 // 30 seconds
    
    private val networkScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    var baseUrl = "https://api.openreplay.com/ingest"

    @Volatile
    var sessionId: String? = null

    @Volatile
    var projectId: String? = null

    @Volatile
    private var token: String? = null

    private var writeToFile = false
    private lateinit var appContext: Context

    fun initialize(context: Context) {
        appContext = context.applicationContext // Avoid memory leaks
    }

    fun getAppContext(): Context {
        if (!this::appContext.isInitialized) {
            throw IllegalStateException("NetworkManager must be initialized with a Context before usage.")
        }
        return appContext
    }

    private suspend fun createRequest(
        method: String,
        path: String,
        body: ByteArray? = null,
        headers: Map<String, String>? = null
    ): HttpURLConnection = withContext(Dispatchers.IO) {
        val url = URL(baseUrl + path)
        val connection = url.openConnection() as HttpURLConnection

        try {
            // Tag the thread for network usage tracking
            TrafficStats.setThreadStatsTag(THREAD_STATS_TAG)

            connection.requestMethod = method
            connection.doInput = true
            connection.useCaches = false
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS

            // Add headers to the connection
            headers?.forEach { (key, value) -> connection.setRequestProperty(key, value) }

            // Add body if it's a POST/PUT request
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Length", body.size.toString())
                connection.outputStream.use { outputStream ->
                    outputStream.write(body)
                }
            }
        } catch (e: Exception) {
            connection.disconnect() // Disconnect in case of failure
            throw e
        } finally {
            // Clear the thread's TrafficStats tag
            TrafficStats.clearThreadStatsTag()
        }

        return@withContext connection
    }

    /**
     * Check if network is available
     */
    private fun isNetworkAvailable(): Boolean {
        if (!this::appContext.isInitialized) return false
        
        val connectivityManager = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = connectivityManager.activeNetwork ?: return false
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
            return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } else {
            @Suppress("DEPRECATION")
            val networkInfo = connectivityManager.activeNetworkInfo
            @Suppress("DEPRECATION")
            return networkInfo?.isConnected == true
        }
    }

    /**
     * Read error stream for better error messages
     */
    private fun readErrorStream(connection: HttpURLConnection): String? {
        return try {
            connection.errorStream?.bufferedReader()?.readText()
        } catch (e: Exception) {
            null
        }
    }


    fun createSession(params: Map<String, Any>, completion: (SessionResponse?) -> Unit) {
        networkScope.launch {
            if (writeToFile) {
                token = "writeToFile"
                withContext(Dispatchers.Main) { completion(null) }
                return@launch
            }

            if (!isNetworkAvailable()) {
                DebugUtils.error("No network connection available for createSession")
                withContext(Dispatchers.Main) { completion(null) }
                return@launch
            }

            val json = Gson().toJson(params).toByteArray()
            var request: HttpURLConnection? = null

            try {
                request = createRequest(
                    "POST",
                    START_URL,
                    body = json,
                    headers = mapOf("Content-Type" to "application/json; charset=utf-8")
                )

                val responseCode = request.responseCode
                if (responseCode !in 200..299) {
                    val errorBody = readErrorStream(request)
                    DebugUtils.error("createSession failed with code $responseCode: $errorBody")
                    withContext(Dispatchers.Main) { completion(null) }
                    return@launch
                }

                val body = request.inputStream.use { inputStream ->
                    inputStream.bufferedReader().readText()
                }

                if (body.isNotEmpty()) {
                    val sessionResponse = Gson().fromJson(body, SessionResponse::class.java)
                    token = sessionResponse.token
                    sessionId = sessionResponse.sessionID
                    projectId = sessionResponse.projectID

                    if (OpenReplay.options.debugLogs) {
                        DebugUtils.log("Session created successfully: $sessionId")
                    }
                    withContext(Dispatchers.Main) { completion(sessionResponse) }
                } else {
                    DebugUtils.error("Empty response body for createSession")
                    withContext(Dispatchers.Main) { completion(null) }
                }
            } catch (e: Exception) {
                DebugUtils.error("Error in createSession: ${e.message}")
                withContext(Dispatchers.Main) { completion(null) }
            } finally {
                request?.disconnect()
            }
        }
    }

    fun sendMessage(content: ByteArray, completion: (Boolean) -> Unit) {
        networkScope.launch {
            if (writeToFile) {
                appendLocalFile(content)
                return@launch
            }

            if (!isNetworkAvailable()) {
                DebugUtils.error("No network connection available for sendMessage")
                withContext(Dispatchers.Main) { completion(false) }
                return@launch
            }

            val compressedContent = try {
                compressData(content).also {
                    if (OpenReplay.options.debugLogs) {
                        DebugUtils.log("Compressed ${content.size} bytes to ${it.size} bytes")
                    }
                }
            } catch (e: Exception) {
                DebugUtils.error("Error with compression: ${e.message}")
                content
            }

            var request: HttpURLConnection? = null
            try {
                request = createRequest(
                    "POST",
                    INGEST_URL,
                    body = compressedContent,
                    headers = mapOf(
                        "Authorization" to "Bearer $token",
                        "Content-Encoding" to "gzip",
                        "Content-Type" to "application/octet-stream"
                    )
                )

                val responseCode = request.responseCode
                if (responseCode in 200..299) {
                    if (OpenReplay.options.debugLogs) {
                        DebugUtils.log("Message sent successfully")
                    }
                    withContext(Dispatchers.Main) { completion(true) }
                } else {
                    val errorBody = readErrorStream(request)
                    DebugUtils.error("Failed to send message: $responseCode - $errorBody")
                    withContext(Dispatchers.Main) { completion(false) }
                }
            } catch (e: Exception) {
                DebugUtils.error("Message sending failed: ${e.message}")
                withContext(Dispatchers.Main) { completion(false) }
            } finally {
                request?.disconnect()
            }
        }
    }

    fun getConditions(completion: (List<ApiResponse>) -> Unit) {
        networkScope.launch {
            val token = this@NetworkManager.token ?: run {
                DebugUtils.error("No token available for getConditions.")
                withContext(Dispatchers.Main) { completion(emptyList()) }
                return@launch
            }

            if (!isNetworkAvailable()) {
                DebugUtils.error("No network connection available for getConditions")
                withContext(Dispatchers.Main) { completion(emptyList()) }
                return@launch
            }

            var request: HttpURLConnection? = null
            try {
                request = createRequest(
                    method = "GET",
                    path = "$CONDITIONS/$projectId",
                    headers = mapOf("Authorization" to "Bearer $token")
                )

                val responseCode = request.responseCode
                if (responseCode !in 200..299) {
                    val errorBody = readErrorStream(request)
                    DebugUtils.error("getConditions failed with code $responseCode: $errorBody")
                    withContext(Dispatchers.Main) { completion(emptyList()) }
                    return@launch
                }

                val responseBody = request.inputStream.use { inputStream ->
                    inputStream.bufferedReader().readText()
                }
                val gson = Gson()
                val type = object : TypeToken<Map<String, List<ApiResponse>>>() {}.type
                val jsonResponse = gson.fromJson<Map<String, List<ApiResponse>>>(responseBody, type)
                val conditions = jsonResponse["conditions"] ?: emptyList()
                
                if (OpenReplay.options.debugLogs) {
                    DebugUtils.log("Conditions fetched: ${conditions.size} items")
                }
                withContext(Dispatchers.Main) { completion(conditions) }
            } catch (e: Exception) {
                DebugUtils.error("Conditions fetch error: ${e.message}")
                withContext(Dispatchers.Main) { completion(emptyList()) }
            } finally {
                request?.disconnect()
            }
        }
    }

    private fun compressData(data: ByteArray): ByteArray {
        val byteArrayOutputStream = ByteArrayOutputStream()
        GZIPOutputStream(byteArrayOutputStream).use { gzipOutputStream ->
            gzipOutputStream.write(data)
        }
        return byteArrayOutputStream.toByteArray()
    }

    private fun appendLocalFile(data: ByteArray) {
        networkScope.launch {
            val filePath = File(getAppContext().filesDir, "session.dat")
            try {
                // Create parent directories if they don't exist
                filePath.parentFile?.mkdirs()
                
                // Append data to file
                FileOutputStream(filePath, true).use { outputStream ->
                    outputStream.write(data)
                }
                
                if (OpenReplay.options.debugLogs) {
                    DebugUtils.log("Data appended to file at: ${filePath.absolutePath}")
                }
            } catch (e: IOException) {
                DebugUtils.error("File append error: ${e.message}")
            }
        }
    }

    fun sendLateMessage(content: ByteArray, completion: (Boolean) -> Unit) {
        networkScope.launch {
            val token = UserDefaults.lastToken ?: run {
                DebugUtils.error("No last token found for sendLateMessage.")
                withContext(Dispatchers.Main) { completion(false) }
                return@launch
            }

            if (!isNetworkAvailable()) {
                DebugUtils.error("No network connection available for sendLateMessage")
                withContext(Dispatchers.Main) { completion(false) }
                return@launch
            }

            var request: HttpURLConnection? = null
            try {
                request = createRequest(
                    method = "POST",
                    path = LATE_URL,
                    body = content,
                    headers = mapOf("Authorization" to "Bearer $token")
                )

                val responseCode = request.responseCode
                if (responseCode in 200..299) {
                    if (OpenReplay.options.debugLogs) {
                        DebugUtils.log("Late message sent successfully")
                    }
                    withContext(Dispatchers.Main) { completion(true) }
                } else {
                    val errorBody = readErrorStream(request)
                    DebugUtils.error("Failed to send late message: $responseCode - $errorBody")
                    withContext(Dispatchers.Main) { completion(false) }
                }
            } catch (e: Exception) {
                DebugUtils.error("Error sending late message: ${e.message}")
                withContext(Dispatchers.Main) { completion(false) }
            } finally {
                request?.disconnect()
            }
        }
    }

    private fun buildMultipartBody(
        boundary: String,
        formFields: Map<String, String>,
        fileField: Pair<String, Pair<String, ByteArray>>
    ): ByteArray {
        val outputStream = ByteArrayOutputStream()
        val writer = outputStream.bufferedWriter()

        // Write form fields
        formFields.forEach { (name, value) ->
            writer.write("--$boundary\r\n")
            writer.write("Content-Disposition: form-data; name=\"$name\"\r\n\r\n")
            writer.write("$value\r\n")
        }

        // Write file field
        val (fileName, fileData) = fileField.second
        writer.write("--$boundary\r\n")
        writer.write("Content-Disposition: form-data; name=\"${fileField.first}\"; filename=\"$fileName\"\r\n")
        writer.write("Content-Type: application/gzip\r\n\r\n")
        writer.flush()
        outputStream.write(fileData)
        writer.write("\r\n")
        writer.write("--$boundary--\r\n")
        writer.flush()

        return outputStream.toByteArray()
    }

    fun sendImages(
        projectKey: String,
        images: ByteArray,
        name: String,
        completion: (Boolean) -> Unit
    ) {
        networkScope.launch {
            val token = this@NetworkManager.token ?: run {
                DebugUtils.error("No token available for sendImages.")
                withContext(Dispatchers.Main) { completion(false) }
                return@launch
            }

            if (!isNetworkAvailable()) {
                DebugUtils.error("No network connection available for sendImages")
                withContext(Dispatchers.Main) { completion(false) }
                return@launch
            }

            var request: HttpURLConnection? = null
            try {
                val boundary = "Boundary-${UUID.randomUUID()}"
                val requestBody = buildMultipartBody(
                    boundary,
                    formFields = mapOf("projectKey" to projectKey),
                    fileField = "batch" to Pair(name, images)
                )

                request = createRequest(
                    method = "POST",
                    path = IMAGES_URL,
                    body = requestBody,
                    headers = mapOf(
                        "Authorization" to "Bearer $token",
                        "Content-Type" to "multipart/form-data; boundary=$boundary"
                    )
                )

                val responseCode = request.responseCode
                if (responseCode in 200..299) {
                    if (OpenReplay.options.debugLogs) {
                        DebugUtils.log("Images sent successfully")
                    }
                    withContext(Dispatchers.Main) { completion(true) }
                } else {
                    val errorBody = readErrorStream(request)
                    DebugUtils.error("Failed to send images: $responseCode - $errorBody")
                    withContext(Dispatchers.Main) { completion(false) }
                }
            } catch (e: Exception) {
                DebugUtils.error("Error sending images: ${e.message}")
                withContext(Dispatchers.Main) { completion(false) }
            } finally {
                request?.disconnect()
            }
        }
    }

    /**
     * Cancel all pending network operations. Call this during app shutdown.
     */
    fun cancelAll() {
        networkScope.cancel()
        if (OpenReplay.options.debugLogs) {
            DebugUtils.log("NetworkManager: All network operations cancelled")
        }
    }
}
