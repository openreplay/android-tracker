package com.openreplay.sampleapp

import android.os.Bundle
import com.google.android.material.bottomnavigation.BottomNavigationView
import androidx.navigation.findNavController
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.setupActionBarWithNavController
import androidx.navigation.ui.setupWithNavController
import com.openreplay.ORTracker
import com.openreplay.listeners.NetworkListener
import com.openreplay.listeners.TrackingActivity
import com.openreplay.models.OROptions
import com.openreplay.sampleapp.databinding.ActivityMainBinding
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : TrackingActivity() {
    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
//        registerActivityLifecycleCallbacks(AppLifecycleTracker())

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val navView: BottomNavigationView = binding.navView

        val navController = findNavController(R.id.nav_host_fragment_activity_main)
        val appBarConfiguration = AppBarConfiguration(
            setOf(
                R.id.navigation_home, R.id.navigation_dashboard, R.id.navigation_notifications
            )
        )
        setupActionBarWithNavController(navController, appBarConfiguration)
        navView.setupWithNavController(navController)


        val tracker = ORTracker.getInstance(this)
        tracker.start("34LtpOwyUI2ELFUNVkMn", OROptions.defaults)
        tracker.setUserID("Shekar")
        tracker.setMetadata("plan", "free")

        data class User(val id: Int, val name: String, val email: String)

        val user = User(id = 1, name = "John Doe", email = "john.doe@example.com")
        tracker.event("userCreated", user)

        tracker.sanitizeView(navView)

        makeSampleRequest()
    }
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

            // Connect and fetch data
//            connection.connect()

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