import android.content.Context
import android.net.TrafficStats
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.openreplay.tracker.OpenReplay
import com.openreplay.tracker.managers.ApiResponse
import com.openreplay.tracker.managers.DebugUtils
import com.openreplay.tracker.managers.UserDefaults
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
            TrafficStats.setThreadStatsTag(1000)

            connection.requestMethod = method
            connection.doInput = true
            connection.useCaches = false

            // Add headers to the connection
            headers?.forEach { (key, value) -> connection.setRequestProperty(key, value) }

            // Add body if it's a POST/PUT request
            if (body != null) {
                connection.doOutput = true
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


    fun createSession(params: Map<String, Any>, completion: (SessionResponse?) -> Unit) {
        networkScope.launch {
            if (writeToFile) {
                token = "writeToFile"
                withContext(Dispatchers.Main) { completion(null) }
                return@launch
            }

            val json = Gson().toJson(params).toByteArray()

            try {
                val request = createRequest(
                    "POST",
                    START_URL,
                    body = json,
                    headers = mapOf("Content-Type" to "application/json; charset=utf-8")
                )

                val body = request.inputStream.use { inputStream ->
                    inputStream.bufferedReader().readText()
                }

                if (body.isNotEmpty()) {
                    val sessionResponse = Gson().fromJson(body, SessionResponse::class.java)
                    token = sessionResponse.token
                    sessionId = sessionResponse.sessionID
                    projectId = sessionResponse.projectID

                    withContext(Dispatchers.Main) { completion(sessionResponse) }
                } else {
                    DebugUtils.log("Empty response body for createSession")
                    withContext(Dispatchers.Main) { completion(null) }
                }
            } catch (e: Exception) {
                DebugUtils.log("Error in createSession: ${e.message}")
                withContext(Dispatchers.Main) { completion(null) }
            }
        }
    }

    fun sendMessage(content: ByteArray, completion: (Boolean) -> Unit) {
        networkScope.launch {
            if (writeToFile) {
                appendLocalFile(content)
                return@launch
            }

            val compressedContent = try {
                compressData(content).also {
                    DebugUtils.log("Compressed ${content.size} bytes to ${it.size} bytes")
                }
            } catch (e: Exception) {
                DebugUtils.log("Error with compression: ${e.message}")
                content
            }

            val request = createRequest(
                "POST",
                INGEST_URL,
                body = compressedContent,
                headers = mapOf(
                    "Authorization" to "Bearer $token",
                    "Content-Encoding" to "gzip",
                    "Content-Type" to "application/octet-stream"
                )
            )

            try {
                request.connect()
                if (request.responseCode in 200..299) {
                    DebugUtils.log("Message sent successfully")
                    withContext(Dispatchers.Main) { completion(true) }
                } else {
                    DebugUtils.log("Failed to send message: ${request.responseCode}")
                    withContext(Dispatchers.Main) { completion(false) }
                }
//                DebugUtils.log("Message sent successfully")
//                withContext(Dispatchers.Main) { completion(true) }
            } catch (e: Exception) {
                DebugUtils.log("Message sending failed: ${e.message}")
                withContext(Dispatchers.Main) { completion(false) }
            } finally {
                request.disconnect()
            }
        }
    }

    fun getConditions(completion: (List<ApiResponse>) -> Unit) {
        networkScope.launch {
            val token = this@NetworkManager.token ?: run {
                DebugUtils.log("No token available for getConditions.")
                withContext(Dispatchers.Main) { completion(emptyList()) }
                return@launch
            }

            val request = createRequest(
                method = "GET",
                path = "$CONDITIONS/$projectId",
                headers = mapOf("Authorization" to "Bearer $token")
            )

            try {
                request.connect()
                val responseBody = request.inputStream.use { inputStream ->
                    inputStream.bufferedReader().readText()
                }
                val gson = Gson()
                val type = object : TypeToken<Map<String, List<ApiResponse>>>() {}.type
                val jsonResponse = gson.fromJson<Map<String, List<ApiResponse>>>(responseBody, type)
                val conditions = jsonResponse["conditions"] ?: emptyList()
                withContext(Dispatchers.Main) { completion(conditions) }
            } catch (e: Exception) {
                DebugUtils.error("Conditions JSON parsing error: $e")
                withContext(Dispatchers.Main) { completion(emptyList()) }
            } finally {
                request.disconnect()
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
            if (OpenReplay.options.debugLogs) {
                val filePath = File(getAppContext().filesDir, "session.dat")
                try {
                    filePath.apply {
                        parentFile?.mkdirs()
                        createNewFile()
                    }.outputStream().apply {
                        FileOutputStream(filePath, true).use { it.write(data) }
                    }
                    DebugUtils.log("Data appended to file at: ${filePath.absolutePath}")
                } catch (e: IOException) {
                    DebugUtils.log("File append error: ${e.message}")
                }
            }
        }
    }

    fun sendLateMessage(content: ByteArray, completion: (Boolean) -> Unit) {
        networkScope.launch {
            val token = UserDefaults.lastToken ?: run {
                DebugUtils.log("No last token found for sendLateMessage.")
                withContext(Dispatchers.Main) { completion(false) }
                return@launch
            }

            val request = createRequest(
                method = "POST",
                path = LATE_URL,
                body = content,
                headers = mapOf("Authorization" to "Bearer $token")
            )

            try {
                request.connect()
                if (request.responseCode in 200..299) {
                    DebugUtils.log("Late message sent successfully")
                    withContext(Dispatchers.Main) { completion(true) }
                } else {
                    DebugUtils.log("Failed to send late message: ${request.responseCode}")
                    withContext(Dispatchers.Main) { completion(false) }
                }
            } catch (e: Exception) {
                DebugUtils.log("Error sending late message: ${e.message}")
                withContext(Dispatchers.Main) { completion(false) }
            } finally {
                request.disconnect()
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
                DebugUtils.log("No token available for sendImages.")
                withContext(Dispatchers.Main) { completion(false) }
                return@launch
            }

            val boundary = "Boundary-${UUID.randomUUID()}"
            val requestBody = buildMultipartBody(
                boundary,
                formFields = mapOf("projectKey" to projectKey),
                fileField = "batch" to Pair(name, images)
            )

            val request = createRequest(
                method = "POST",
                path = IMAGES_URL,
                body = requestBody,
                headers = mapOf(
                    "Authorization" to "Bearer $token",
                    "Content-Type" to "multipart/form-data; boundary=$boundary"
                )
            )

            try {
                request.connect()
                if (request.responseCode in 200..299) {
                    DebugUtils.log("Images sent successfully")
                    withContext(Dispatchers.Main) { completion(true) }
                } else {
                    DebugUtils.log("Failed to send images: ${request.responseCode}")
                    withContext(Dispatchers.Main) { completion(false) }
                }
            } catch (e: Exception) {
                DebugUtils.log("Error sending images: ${e.message}")
                withContext(Dispatchers.Main) { completion(false) }
            } finally {
                request.disconnect()
            }
        }
    }
}
