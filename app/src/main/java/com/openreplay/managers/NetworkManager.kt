import com.google.gson.Gson
import com.openreplay.OpenReplay
import com.openreplay.managers.DebugUtils
import com.openreplay.managers.UserDefaults
import com.openreplay.models.SessionResponse
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream

object NetworkManager {
    private const val START_URL = "/v1/mobile/start"
    private const val INGEST_URL = "/v1/mobile/i"
    private const val LATE_URL = "/v1/mobile/late"
    private const val IMAGES_URL = "/v1/mobile/images"

    private var baseUrl = "https://foss.openreplay.com/ingest"
    var sessionId: String? = null
    private var token: String? = null
    private var writeToFile = false

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private fun createRequest(method: String, path: String, body: RequestBody? = null): Request {
        return Request.Builder()
            .url(baseUrl + path)
            .method(method, body)
//            .addHeader("Content-Type", "application/json")
//            .addHeader("Accept", "application/json")
//            .addHeader("Authorization", "Bearer $token")
            .build()
    }

    private fun callAPI(
        request: Request,
        onSuccess: (Response) -> Unit,
        onError: (Exception?) -> Unit
    ) {
        if (writeToFile) return

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                onError(e)
            }

            override fun onResponse(call: Call, response: Response) {
                if (response.isSuccessful) {
                    onSuccess(response)
                } else {
                    onError(Exception("HTTP error code: ${response.code}"))
                }
            }
        })
    }

    fun createSession(params: Map<String, Any>, completion: (SessionResponse?) -> Unit) {
        if (writeToFile) {
            this.token = "writeToFile"
            completion(null)
            return
        }
        val mediaType = "application/json; charset=utf-8".toMediaType()
        val json = Gson().toJson(params)
        val requestBody = json.toRequestBody(mediaType)
        val request = createRequest("POST", START_URL, requestBody)

        callAPI(request, onSuccess = { response ->
            val body = response.body.string()
            try {
                val sessionResponse = Gson().fromJson(body, SessionResponse::class.java)
                this.token = sessionResponse.token
                this.sessionId = sessionResponse.sessionID // Ensure this matches the property name in SessionResponse
                completion(sessionResponse) // Pass the session object on success
            } catch (e: Exception) {
                DebugUtils.log("Can't unwrap session start resp: $e")
                completion(null) // Pass null on failure to parse the response
            }
        }, onError = {
            DebugUtils.log("Can't start session: $it")
            completion(null) // Pass null on API call failure
        })
    }

    fun sendMessage(content: ByteArray, completion: (Boolean) -> Unit) {
        if (writeToFile) {
            appendLocalFile(content)
            return
        }

        val token = "Bearer $token"

        val compressedContent = try {
            compressData(content).also { compressed ->
                DebugUtils.log("Compressed ${content.size} bytes to ${compressed.size} bytes")
            }
        } catch (e: Exception) {
            DebugUtils.log("Error with compression: ${e.message}")
            content // Fallback to original content if compression fails
        }

        val mediaType = "application/octet-stream".toMediaTypeOrNull()
        val requestBody = compressedContent.toRequestBody(mediaType)

        val request = Request.Builder()
            .url(baseUrl.trimEnd('/') + "/" + INGEST_URL.trimStart('/'))
            .post(requestBody)
            .addHeader("Authorization", token)
            .addHeader("Content-Encoding", "gzip")
            .build()

        OkHttpClient().newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                DebugUtils.log("Network call failed: ${e.message}")
                completion(false)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { // Ensure response body is closed to avoid leaks
                    if (it.isSuccessful) {
                        DebugUtils.log("Network call successful with HTTP ${it.code} and ${it.body.contentLength()} bytes sent")
                        completion(true)
                    } else {
                        DebugUtils.log("Network call failed: HTTP ${it.code}")
                        completion(false)
                    }
                }
            }
        })
    }

    private fun compressData(data: ByteArray): ByteArray {
        val byteArrayOutputStream = ByteArrayOutputStream()
        GZIPOutputStream(byteArrayOutputStream).use { gzipOutputStream ->
            gzipOutputStream.write(data)
        }
        return byteArrayOutputStream.toByteArray()
    }

    fun sendLateMessage(content: ByteArray, completion: (Boolean) -> Unit) {
        println(">>>sending late messages")
        val token = UserDefaults.lastToken ?: run {
            println("! No last token found")
            completion(false)
            return
        }
        val request = createRequest(
            method = "POST",
            path = LATE_URL,
            body = content.toRequestBody()
        ).newBuilder()
            .addHeader("Authorization", "Bearer $token")
            .build()

        callAPI(request, onSuccess = {
            completion(true)
            println("<<< late messages sent")
        }, onError = {
            completion(false)
        })
    }

    fun sendImages(projectKey: String, images: ByteArray, name: String, completion: (Boolean) -> Unit) {
        val token = this.token // Assuming 'token' is a property of your class
        if (token == null) {
            completion(false)
            return
        }

        val boundary = "Boundary-${UUID.randomUUID()}"
        //val mediaType = "multipart/form-data; boundary=$boundary".toMediaTypeOrNull()
        val requestBodyBuilder = MultipartBody.Builder(boundary).setType(MultipartBody.FORM)

        requestBodyBuilder.addFormDataPart("projectKey", projectKey)

        // Assuming 'images' is the byte array you want to upload
        requestBodyBuilder.addFormDataPart(
            "batch",
            name,
            images.toRequestBody("application/gzip".toMediaTypeOrNull(), 0, images.size)
        )

        val request = createRequest(
            method = "POST",
            path = IMAGES_URL,
            body = requestBodyBuilder.build()
        ).newBuilder()
            .addHeader("Authorization", "Bearer $token")
            .build()

        callAPI(request, onSuccess = {
            completion(true)
            println(">>>>>> sending ${request.body?.contentLength()} bytes")
        }, onError = {
            completion(false)
        })
    }

    fun sendImagesBatch(
        gzData: ByteArray, archiveName: String, completion: (Boolean) -> Unit = {
            println("Images batch sent: $it")
        }
    ) {
        val token = this.token
        if (token == null) {
            completion(false)
            return
        }

        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("batch", archiveName, gzData.toRequestBody("application/gzip".toMediaTypeOrNull()))
            .build()

        val request = createRequest("POST", IMAGES_URL, requestBody).newBuilder()
            .addHeader("Authorization", "Bearer $token")
            .build()

        callAPI(request, onSuccess = {
            completion(true)
        }, onError = {
            completion(false)
        })
    }

    private fun appendLocalFile(data: ByteArray) {
        if (OpenReplay.options.debugLogs) { // Ensure Openreplay is adapted to your Kotlin implementation
            println("appendInFile ${data.size} bytes")

            val filePath = "/Users/shekarsiri/Desktop/session.dat" // TODO fix this
            try {
                File(filePath).apply {
                    // Create file and directories if they don't exist
                    parentFile?.mkdirs()
                    createNewFile()
                }.outputStream().apply {
                    // Append mode set to true
                    val fileOutputStream = FileOutputStream(filePath, true)
                    fileOutputStream.use { stream ->
                        stream.write(data)
                    }
                }
                println("Data successfully appended to file.")
            } catch (e: IOException) {
                e.printStackTrace()
                println("Failed to append data to file.")
            }
        }
    }
}
