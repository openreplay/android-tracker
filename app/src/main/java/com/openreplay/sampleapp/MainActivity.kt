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
import com.openreplay.tracker.listeners.NetworkListener
import com.openreplay.tracker.models.OROptions
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (BuildConfig.DEBUG) {
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy.Builder()
                    .detectAll()
                    .penaltyLog()
                    .build()
            )

            StrictMode.setVmPolicy(
                StrictMode.VmPolicy.Builder()
                    .detectAll()
                    .penaltyLog()
                    .build()
            )
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val navView: BottomNavigationView = binding.navView
        val navController = findNavController(R.id.nav_host_fragment_activity_main)
        val appBarConfiguration = AppBarConfiguration(
            setOf(R.id.navigation_home, R.id.navigation_dashboard, R.id.navigation_notifications)
        )
        navController.addOnDestinationChangedListener { _, destination, _ ->
            OpenReplay.event(
                "navigation_tab_changed",
                mapOf(
                    "destination" to (destination.label ?: "unknown"),
                    "destinationId" to destination.id
                )
            )
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
        val projectKey = BuildConfig.OR_PROJECT_KEY
        if (projectKey.isEmpty()) {
            android.util.Log.w("OpenReplay", "OR_PROJECT_KEY not configured. Tracking disabled. Please set it in local.properties")
            return
        }
        
        OpenReplay.serverURL = BuildConfig.OR_SERVER_URL
        OpenReplay.setUserID("Android User" + (0..10).random())
        
        OpenReplay.start(
            context = this,
            projectKey = projectKey,
            options = OROptions(analytics = true, screen = true, logs = true, wifiOnly = false, debugLogs = true),
            onStarted = {
                val sessionId = OpenReplay.getSessionID()
                OpenReplay.event(
                    "session_started",
                    mapOf(
                        "sessionId" to sessionId,
                        "platform" to "Android",
                        "appVersion" to BuildConfig.VERSION_NAME
                    )
                )

                OpenReplay.event(
                    "user_profile_loaded",
                    mapOf(
                        "name" to "John Doe",
                        "age" to 25,
                        "role" to "tester"
                    )
                )
            }
        )
    }
}