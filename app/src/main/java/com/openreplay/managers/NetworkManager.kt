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

    private var baseUrl = "https://api.openreplay.com/ingest"
    private var sessionId: String? = null
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

//    fun sendMessage(content: ByteArray, completion: (Boolean) -> Unit) {
//        if (writeToFile) {
//            appendLocalFile(content)
//            return
//        }
//        val token = "Bearer $token"
//        val request = createRequest("POST", INGEST_URL, content.toRequestBody()).newBuilder()
//            .addHeader("Authorization", token)
//            .addHeader("Content-Encoding", "application/gzip")
//            .build()
//
//        callAPI(request, onSuccess = { _ ->
//            completion(true)
//        }, onError = { _ ->
//            completion(false)
//        })
//    }

    fun sendMessage(content: ByteArray, completion: (Boolean) -> Unit) {
        if (writeToFile) {
            appendLocalFile(content)
            return
        }

        val token = getToken() // Assume this retrieves your token

        val compressedContent = try {
            // Assume compressData() is your method to compress data, similar to GzipArchive.archive(data: content)
            val oldSize = content.size
            val compressed = compressData(content)
            val newSize = compressed.size
            DebugUtils.log("Compressed $oldSize bytes to $newSize bytes")
            compressed
        } catch (e: Exception) {
            DebugUtils.log("Error with compression: ${e.message}")
            content // Use original content if compression fails
        }

        val request = Request.Builder()
            .url(INGEST_URL)
            .post(compressedContent.toRequestBody("application/octet-stream".toMediaTypeOrNull()))
            .addHeader("Authorization", "Bearer $token")
            .addHeader("Content-Encoding", "gzip")
            .build()

        OkHttpClient().newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                DebugUtils.log("Network call failed: ${e.message}")
                completion(false)
            }

            override fun onResponse(call: Call, response: Response) {
                if (response.isSuccessful) {
                    completion(true)
                } else {
                    DebugUtils.log("Network call failed: HTTP ${response.code}")
                    completion(false)
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

    private fun getToken(): String {
        return "Bearer $token"
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

    private fun appendLocalFile(data: ByteArray) {
        if (OpenReplay.options.debugLogs) { // Ensure Openreplay is adapted to your Kotlin implementation
            println("appendInFile ${data.size} bytes")

            val filePath = "/Users/shekarsiri/Desktop/session.dat"
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
