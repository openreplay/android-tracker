package com.openreplay.sampleapp

import android.os.Bundle
import android.os.StrictMode
import android.view.MotionEvent
import androidx.appcompat.app.AppCompatActivity
import androidx.navigation.findNavController
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.setupActionBarWithNavController
import androidx.navigation.ui.setupWithNavController
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.openreplay.sampleapp.databinding.ActivityMainBinding
import com.openreplay.tracker.OpenReplay
import com.openreplay.tracker.analytics.ViewTrackingManager
import com.openreplay.tracker.listeners.NetworkListener
import com.openreplay.tracker.models.OROptions
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL


class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var viewTrackingManager: ViewTrackingManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

//        val rootView = window.decorView.rootView
//        viewTrackingManager = ViewTrackingManager(rootView)

        // Add the ViewTrackingManager as a lifecycle observer
//        lifecycle.addObserver(viewTrackingManager)

        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder()
                .detectAll() // Detect all potential thread violations
                .penaltyLog() // Log violations in Logcat
                .penaltyDeath() // Crash the app on violation (optional for debugging)
                .build()
        )

        // Enable StrictMode for VM policies
        StrictMode.setVmPolicy(
            StrictMode.VmPolicy.Builder()
                .detectAll() // Detect all potential VM violations
                .penaltyLog() // Log violations in Logcat
                .build()
        )

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val navView: BottomNavigationView = binding.navView
        val navController = findNavController(R.id.nav_host_fragment_activity_main)
        val appBarConfiguration = AppBarConfiguration(
            setOf(R.id.navigation_home, R.id.navigation_dashboard, R.id.navigation_notifications)
        )
        navController.addOnDestinationChangedListener { _, destination, _ ->
            OpenReplay.event("Event Tab click", destination.label)
            makeGraphQLRequest()
        }
        setupActionBarWithNavController(navController, appBarConfiguration)
        navView.setupWithNavController(navController)
    }

    override fun onStart() {
        super.onStart()
        startOpenReplay()
    }

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        ev?.let { OpenReplay.onTouchEvent(it) }
        return super.dispatchTouchEvent(ev)
    }


    private fun startOpenReplay() {
        OpenReplay.serverURL = "https://foss.openreplay.com/ingest"
        OpenReplay.setUserID("Android User" + (0..100).random())

        OpenReplay.start(
            context = this,
            projectKey = "",
            options = OROptions(screen = true, logs = true, wifiOnly = false, debugLogs = true),
            onStarted = {
                OpenReplay.event("Test Event", User("John Doe", 25))

                val id = OpenReplay.getSessionID()
                OpenReplay.event("Session ID", id)
                makeSampleRequest()
            }
        )
    }

    private data class User(val name: String, val age: Int)
}

fun makeGraphQLRequest() {
    val query = """
        query {
            user {
                id
                name
            }
        }
    """.trimIndent()

    val variables = mapOf("id" to 1)

    val message = mapOf(
        "operationKind" to "query",
        "operationName" to "getUser",
        "variables" to variables,
        "response" to mapOf("id" to 1, "name" to "John Doe"),
        "duration" to 100
    )


    val messageString = message.toString()
    OpenReplay.sendMessage("gql", message)
}

fun makeSampleRequest() {
    Thread {
        try {
            val url = URL("https://jsonplaceholder.typicode.com/posts/1")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"

            // Optionally set request headers
            connection.setRequestProperty("Content-Type", "application/json")

            // Initialize the network listener for this connection
            val networkListener = NetworkListener(connection)

            val reader = BufferedReader(InputStreamReader(connection.inputStream))
            val response = StringBuilder()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                response.append(line)
            }
            reader.close()

            // Using the network listener to log the finish event
            networkListener.finish(connection, response.toString().toByteArray())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }.start()
}