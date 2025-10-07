package com.openreplay.sampleapp.ui.dashboard

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.openreplay.sampleapp.databinding.FragmentDashboardBinding
import com.openreplay.tracker.OpenReplay
import com.openreplay.tracker.listeners.NetworkListener
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

class DashboardFragment : Fragment() {

    private var _binding: FragmentDashboardBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDashboardBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupButtons()
    }

    private fun setupButtons() {
        binding.btnGetRequest.setOnClickListener {
            updateStatus("🔄 Sending GET request...")
            makeGetRequest()
        }

        binding.btnPostRequest.setOnClickListener {
            updateStatus("🔄 Sending POST request...")
            makePostRequest()
        }

        binding.btnGraphql.setOnClickListener {
            updateStatus("🔄 Sending GraphQL query...")
            sendGraphQLEvent()
        }
    }

    private fun makeGetRequest() {
        thread {
            try {
                val url = URL("https://jsonplaceholder.typicode.com/users/1")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setRequestProperty("User-Agent", "OpenReplay-Demo/1.0")

                val networkListener = NetworkListener(connection)

                val responseCode = connection.responseCode
                val reader = BufferedReader(InputStreamReader(connection.inputStream))
                val response = StringBuilder()
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    response.append(line)
                }
                reader.close()

                networkListener.finish(connection, response.toString().toByteArray())

                val jsonResponse = JSONObject(response.toString())
                val prettyResponse = """
                    Status: $responseCode OK
                    
                    User Details:
                    Name: ${jsonResponse.getString("name")}
                    Email: ${jsonResponse.getString("email")}
                    Phone: ${jsonResponse.getString("phone")}
                    Company: ${jsonResponse.getJSONObject("company").getString("name")}
                """.trimIndent()

                activity?.runOnUiThread {
                    binding.textResponse.text = prettyResponse
                    updateStatus("✅ GET request completed - User data received")
                }
            } catch (e: Exception) {
                activity?.runOnUiThread {
                    binding.textResponse.text = "Error: ${e.message}"
                    updateStatus("❌ GET request failed: ${e.message}")
                }
            }
        }
    }

    private fun makePostRequest() {
        thread {
            try {
                val url = URL("https://jsonplaceholder.typicode.com/posts")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json")
                connection.doOutput = true

                val networkListener = NetworkListener(connection)

                val postData = JSONObject().apply {
                    put("title", "OpenReplay Session Analysis")
                    put("body", "Analyzing user behavior patterns for better UX insights")
                    put("userId", 1)
                }

                networkListener.setRequestBody(postData.toString())

                connection.outputStream.use { os ->
                    os.write(postData.toString().toByteArray())
                }

                val responseCode = connection.responseCode
                val reader = BufferedReader(InputStreamReader(connection.inputStream))
                val response = StringBuilder()
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    response.append(line)
                }
                reader.close()

                networkListener.finish(connection, response.toString().toByteArray())

                val jsonResponse = JSONObject(response.toString())
                val prettyResponse = """
                    Status: $responseCode Created
                    
                    Post Created:
                    ID: ${jsonResponse.getInt("id")}
                    Title: ${jsonResponse.getString("title")}
                    Body: ${jsonResponse.getString("body")}
                    User ID: ${jsonResponse.getInt("userId")}
                """.trimIndent()

                activity?.runOnUiThread {
                    binding.textResponse.text = prettyResponse
                    updateStatus("✅ POST request completed - Resource created")
                }
            } catch (e: Exception) {
                activity?.runOnUiThread {
                    binding.textResponse.text = "Error: ${e.message}"
                    updateStatus("❌ POST request failed: ${e.message}")
                }
            }
        }
    }

    private fun sendGraphQLEvent() {
        thread {
            try {
                val variables = mapOf(
                    "userId" to 42,
                    "includeMetrics" to true,
                    "dateRange" to "last_7_days"
                )

                val responseData = mapOf(
                    "data" to mapOf(
                        "user" to mapOf(
                            "id" to 42,
                            "name" to "John Developer",
                            "email" to "john@example.com",
                            "role" to "Senior Engineer",
                            "team" to "Mobile",
                            "metrics" to mapOf(
                                "sessionsCount" to 156,
                                "averageDuration" to 342,
                                "errorRate" to 0.02
                            )
                        )
                    )
                )

                val message = mapOf(
                    "operationKind" to "query",
                    "operationName" to "GetUserAnalytics",
                    "variables" to variables,
                    "response" to responseData,
                    "duration" to 187
                )

                OpenReplay.sendMessage("gql", message)

                val prettyResponse = """
                    GraphQL Query: GetUserAnalytics
                    Duration: 187ms
                    
                    User Analytics:
                    Name: John Developer
                    Email: john@example.com
                    Role: Senior Engineer
                    Team: Mobile
                    
                    Metrics (Last 7 Days):
                    Sessions: 156
                    Avg Duration: 342s
                    Error Rate: 0.02%
                """.trimIndent()

                activity?.runOnUiThread {
                    binding.textResponse.text = prettyResponse
                    updateStatus("✅ GraphQL query sent - Analytics retrieved")
                }
            } catch (e: Exception) {
                activity?.runOnUiThread {
                    binding.textResponse.text = "Error: ${e.message}"
                    updateStatus("❌ GraphQL query failed: ${e.message}")
                }
            }
        }
    }

    private fun updateStatus(message: String) {
        binding.textStatus.text = message
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}