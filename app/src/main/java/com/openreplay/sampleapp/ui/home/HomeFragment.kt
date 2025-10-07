package com.openreplay.sampleapp.ui.home

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.core.view.GestureDetectorCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import com.openreplay.sampleapp.databinding.FragmentHomeBinding
import com.openreplay.tracker.OpenReplay
import com.openreplay.tracker.listeners.Analytics
import com.openreplay.tracker.listeners.NetworkListener
import com.openreplay.tracker.listeners.SwipeDirection
import com.openreplay.tracker.listeners.sanitize
import com.openreplay.tracker.managers.MessageCollector
import com.openreplay.tracker.managers.ScreenshotManager
import com.openreplay.tracker.models.script.ORMobileViewComponentEvent
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject
import kotlin.concurrent.thread
import kotlin.math.abs

class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!
    private val homeViewModel by lazy { ViewModelProvider(this)[HomeViewModel::class.java] }
    private var isSanitized = true

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        setupInputTracking()
        setupSwipeDetection()
        setupButtons()
    }

    private fun setupInputTracking() {
        binding.inputTest.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                Analytics.sendTextInput(
                    value = s.toString(),
                    label = "test_input",
                    masked = false
                )
            }
        })

        binding.inputPassword.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                Analytics.sendTextInput(
                    value = "***",
                    label = "password_input",
                    masked = true
                )
            }
        })

        binding.inputSanitized.sanitize()
        updateStatus("Sanitization applied to 'input_sanitized' field")
    }

    private fun setupSwipeDetection() {
        var startX = 0f
        var startY = 0f
        
        binding.swipeArea.setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.x
                    startY = event.y
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val endX = event.x
                    val endY = event.y
                    val deltaX = endX - startX
                    val deltaY = endY - startY
                    
                    if (abs(deltaX) > 50 || abs(deltaY) > 50) {
                        val direction = when {
                            abs(deltaX) > abs(deltaY) -> {
                                if (deltaX > 0) SwipeDirection.RIGHT else SwipeDirection.LEFT
                            }
                            else -> {
                                if (deltaY > 0) SwipeDirection.DOWN else SwipeDirection.UP
                            }
                        }
                        
                        Analytics.sendSwipe(direction, endX, endY)
                        updateStatus("Swipe detected: $direction")
                        binding.textSwipeHint.text = "Swipe detected: $direction\n⬅️ ➡️ ⬆️ ⬇️"
                        
                        view.postDelayed({
                            binding.textSwipeHint.text = "👆 Swipe Here 👆\n⬅️ ➡️ ⬆️ ⬇️"
                        }, 2000)
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun setupButtons() {
        binding.btnToggleSanitize.setOnClickListener {
            isSanitized = !isSanitized
            if (isSanitized) {
                binding.inputSanitized.sanitize()
                binding.btnToggleSanitize.text = "Toggle Sanitization (Currently: ON)"
                updateStatus("Sanitization ENABLED - Field will be masked in screenshots")
            } else {
                ScreenshotManager.removeSanitizedElement(binding.inputSanitized)
                binding.btnToggleSanitize.text = "Toggle Sanitization (Currently: OFF)"
                updateStatus("Sanitization DISABLED - Field will be visible in screenshots")
            }
        }

        binding.btnNetworkGet.setOnClickListener {
            updateStatus("Sending GET request...")
            makeNetworkGetRequest()
        }

        binding.btnNetworkPost.setOnClickListener {
            updateStatus("Sending POST request...")
            makeNetworkPostRequest()
        }

        binding.btnGraphql.setOnClickListener {
            updateStatus("Sending GraphQL event...")
            triggerGraphQLEvent()
        }

        binding.btnClickEvent.setOnClickListener {
            updateStatus("Simulating click event...")
            simulateClickEvent()
        }

        binding.btnCustomEvent.setOnClickListener {
            updateStatus("Sending custom event...")
            sendCustomEvent()
        }

        binding.btnViewComponent.setOnClickListener {
            updateStatus("Sending view component event...")
            sendViewComponentEvent()
        }

        binding.btnMetadata.setOnClickListener {
            updateStatus("Updating metadata...")
            updateUserMetadata()
        }

        binding.btnError.setOnClickListener {
            updateStatus("Triggering error...")
            triggerError()
        }
    }

    private fun makeNetworkGetRequest() {
        thread {
            try {
                val url = URL("https://jsonplaceholder.typicode.com/posts/1")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.setRequestProperty("Content-Type", "application/json")

                val networkListener = NetworkListener(connection)

                val reader = BufferedReader(InputStreamReader(connection.inputStream))
                val response = StringBuilder()
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    response.append(line)
                }
                reader.close()

                networkListener.finish(connection, response.toString().toByteArray())
                
                activity?.runOnUiThread {
                    updateStatus("GET request completed")
                }
            } catch (e: Exception) {
                activity?.runOnUiThread {
                    updateStatus("GET request failed: ${e.message}")
                }
            }
        }
    }

    private fun makeNetworkPostRequest() {
        thread {
            try {
                val url = URL("https://jsonplaceholder.typicode.com/posts")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json")
                connection.doOutput = true

                val networkListener = NetworkListener(connection)
                
                val postData = JSONObject().apply {
                    put("title", "Test Post")
                    put("body", "This is a test post from OpenReplay")
                    put("userId", 1)
                }
                
                networkListener.setRequestBody(postData.toString())
                
                connection.outputStream.use { os ->
                    os.write(postData.toString().toByteArray())
                }

                val reader = BufferedReader(InputStreamReader(connection.inputStream))
                val response = StringBuilder()
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    response.append(line)
                }
                reader.close()

                networkListener.finish(connection, response.toString().toByteArray())
                
                activity?.runOnUiThread {
                    updateStatus("POST request completed")
                }
            } catch (e: Exception) {
                activity?.runOnUiThread {
                    updateStatus("POST request failed: ${e.message}")
                }
            }
        }
    }

    private fun triggerGraphQLEvent() {
        val variables = mapOf(
            "userId" to 123,
            "includeDetails" to true
        )

        val message = mapOf(
            "operationKind" to "query",
            "operationName" to "getUserProfile",
            "variables" to variables,
            "response" to mapOf(
                "data" to mapOf(
                    "user" to mapOf(
                        "id" to 123,
                        "name" to "Test User",
                        "email" to "test@example.com",
                        "role" to "tester"
                    )
                )
            ),
            "duration" to 145
        )

        OpenReplay.sendMessage("gql", message)
        updateStatus("GraphQL event sent")
    }

    private fun simulateClickEvent() {
        val motionEvent = MotionEvent.obtain(
            System.currentTimeMillis(),
            System.currentTimeMillis(),
            MotionEvent.ACTION_UP,
            150f,
            200f,
            0
        )
        Analytics.sendClick(motionEvent, "Simulated Button Click")
        motionEvent.recycle()
        updateStatus("Click event sent (150, 200)")
    }

    private fun sendCustomEvent() {
        OpenReplay.event(
            "button_click_test",
            mapOf(
                "button_id" to "custom_event_btn",
                "timestamp" to System.currentTimeMillis(),
                "screen" to "home_fragment",
                "user_action" to "manual_trigger"
            )
        )
        updateStatus("Custom event sent")
    }

    private fun sendViewComponentEvent() {
        val event = ORMobileViewComponentEvent(
            screenName = "HomeFragment",
            viewName = "test_component",
            visible = true
        )
        MessageCollector.sendMessage(event)
        updateStatus("View component event sent (visible=true)")
    }

    private fun updateUserMetadata() {
        OpenReplay.setUserID("test_user_${System.currentTimeMillis()}")
        OpenReplay.setMetadata("subscription", "premium")
        OpenReplay.setMetadata("theme", "dark")
        OpenReplay.setMetadata("language", "en")
        updateStatus("User metadata updated")
    }

    private fun triggerError() {
        try {
            throw RuntimeException("Intentional test error from OpenReplay tracker")
        } catch (e: Exception) {
            OpenReplay.event(
                "error_triggered",
                mapOf(
                    "error_message" to e.message,
                    "error_type" to e.javaClass.simpleName
                )
            )
            updateStatus("Error triggered and logged")
        }
    }

    private fun updateStatus(message: String) {
        activity?.runOnUiThread {
            binding.textStatus.text = "Status: $message"
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}