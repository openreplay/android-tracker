import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.openreplay.tracker.OpenReplay
import com.openreplay.tracker.managers.ApiResponse
import com.openreplay.tracker.managers.DebugUtils
import com.openreplay.tracker.managers.UserDefaults
import com.openreplay.tracker.models.SessionResponse
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

    var baseUrl = "https://api.openreplay.com/ingest"
    var sessionId: String? = null
    var projectId: String? = null
    private var token: String? = null
    private var writeToFile = false

    private lateinit var appContext: Context

    fun initialize(context: Context) {
        appContext = context.applicationContext // Use application context to avoid leaks
    }

    fun getAppContext(): Context {
        if (!this::appContext.isInitialized) {
            throw IllegalStateException("NetworkManager must be initialized with a Context before usage.")
        }
        return appContext
    }

    private fun createRequest(
        method: String,
        path: String,
        body: ByteArray? = null,
        headers: Map<String, String>? = null
    ): HttpURLConnection {
        val url = URL(baseUrl + path)
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.doInput = true
        connection.useCaches = false

        // Set headers before any interaction with the streams
        headers?.forEach { (key, value) ->
            connection.setRequestProperty(key, value)
        }

        if (body != null) {
            connection.doOutput = true
            connection.outputStream.use { it.write(body) }
        }

        return connection
    }

    private fun asyncCallAPI(
        request: HttpURLConnection,
        onSuccess: (HttpURLConnection) -> Unit,
        onError: (Exception?) -> Unit
    ) {
        if (writeToFile) return
        try {
            if (request.responseCode in 200..299) {
                onSuccess(request)
            } else {
                onError(Exception("HTTP error code: ${request.responseCode}"))
            }
        } catch (e: Exception) {
            onError(e)
        } finally {
            request.disconnect()
        }
    }

    private fun callAPI(request: HttpURLConnection) {
        if (writeToFile) return
        try {
            request.connect()
        } catch (e: Exception) {
            DebugUtils.log(e.printStackTrace().toString())
        } finally {
            request.disconnect()
        }
    }

    fun createSession(params: Map<String, Any>, completion: (SessionResponse?) -> Unit) {
        if (writeToFile) {
            this.token = "writeToFile"
            completion(null)
            return
        }
        val json = Gson().toJson(params).toByteArray()
        val request = createRequest(
            "POST",
            START_URL,
            body = json,
            headers = mapOf("Content-Type" to "application/json; charset=utf-8")
        )

        asyncCallAPI(request, onSuccess = { conn ->
            val body = conn.inputStream.bufferedReader().readText()
            try {
                val sessionResponse = Gson().fromJson(body, SessionResponse::class.java)
                this.token = sessionResponse.token
                this.sessionId = sessionResponse.sessionID
                this.projectId = sessionResponse.projectID
                println("Session created with ID: ${sessionResponse.sessionID}")
                completion(sessionResponse)
            } catch (e: Exception) {
                DebugUtils.log("Can't unwrap session start resp: $e")
                completion(null)
            }
        }, onError = {
            DebugUtils.log("Can't start session: $it")
            completion(null)
        })
    }

    fun sendMessage(content: ByteArray, completion: (Boolean) -> Unit) {
        if (writeToFile) {
            appendLocalFile(content) // Pass context to dynamically determine the file path
            return
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

        asyncCallAPI(request, onSuccess = {
            DebugUtils.log("Message sent successfully")
            completion(true)
        }, onError = {
            DebugUtils.log("Message sending failed: ${it?.message}")
            completion(false)
        })
    }

    fun sendLateMessage(content: ByteArray, completion: (Boolean) -> Unit) {
        val token = UserDefaults.lastToken ?: run {
            println("! No last token found")
            completion(false)
            return
        }

        val request = createRequest(
            method = "POST",
            path = LATE_URL,
            body = content,
            headers = mapOf("Authorization" to "Bearer $token")
        )

        asyncCallAPI(request, onSuccess = {
            println("<<< Late messages sent successfully")
            completion(true)
        }, onError = {
            println("<<< Failed to send late messages: ${it?.message}")
            completion(false)
        })
    }

    fun getConditions(completion: (List<ApiResponse>) -> Unit) {
        val token = this.token ?: run {
            DebugUtils.log("No token available for getConditions.")
            completion(emptyList())
            return
        }

        val request = createRequest(
            method = "GET",
            path = "$CONDITIONS/$projectId",
            headers = mapOf("Authorization" to "Bearer $token")
        )

        asyncCallAPI(request, onSuccess = { connection ->
            val responseBody = connection.inputStream.bufferedReader().use { it.readText() }
            try {
                val gson = Gson()
                val type = object : TypeToken<Map<String, List<ApiResponse>>>() {}.type
                val jsonResponse =
                    gson.fromJson<Map<String, List<ApiResponse>>>(responseBody, type)
                val conditions = jsonResponse["conditions"] ?: emptyList()
                completion(conditions)
            } catch (e: Exception) {
                DebugUtils.error("OpenReplay: Conditions JSON parsing error: $e")
                completion(emptyList())
            }
        }, onError = {
            DebugUtils.log("Can't get conditions: ${it?.message}")
            completion(emptyList())
        })
    }

    fun sendImages(
        projectKey: String,
        images: ByteArray,
        name: String,
        completion: (Boolean) -> Unit
    ) {
        val token = this.token ?: run {
            DebugUtils.log("No token available for sendImages.")
            completion(false)
            return
        }

        val boundary = "Boundary-${UUID.randomUUID()}"
        val requestBody = buildMultipartBody(
            boundary,
            mapOf("projectKey" to projectKey),
            "batch" to Pair(name, images)
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

        asyncCallAPI(request, onSuccess = {
            DebugUtils.log("Images sent successfully")
            completion(true)
        }, onError = {
            println("Failed to send images: ${it?.message}")
            completion(false)
        })
    }

    private fun buildMultipartBody(
        boundary: String,
        formFields: Map<String, String>,
        fileField: Pair<String, Pair<String, ByteArray>>
    ): ByteArray {
        val outputStream = ByteArrayOutputStream()
        val writer = outputStream.bufferedWriter()

        formFields.forEach { (name, value) ->
            writer.write("--$boundary\r\n")
            writer.write("Content-Disposition: form-data; name=\"$name\"\r\n\r\n")
            writer.write("$value\r\n")
        }

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


    private fun compressData(data: ByteArray): ByteArray {
        val byteArrayOutputStream = ByteArrayOutputStream()
        GZIPOutputStream(byteArrayOutputStream).use { gzipOutputStream ->
            gzipOutputStream.write(data)
        }
        return byteArrayOutputStream.toByteArray()
    }

    private fun appendLocalFile(data: ByteArray) {
        if (OpenReplay.options.debugLogs) {
            val filePath = File(getAppContext().filesDir, "session.dat")
            try {
                filePath.apply {
                    // Create file and directories if they don't exist
                    parentFile?.mkdirs()
                    createNewFile()
                }.outputStream().apply {
                    // Append mode set to true
                    FileOutputStream(filePath, true).use { stream ->
                        stream.write(data)
                    }
                }
                println("Data appended to file at: ${filePath.absolutePath}")
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }
    }
}
