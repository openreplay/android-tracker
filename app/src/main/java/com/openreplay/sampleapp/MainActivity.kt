package com.openreplay.sampleapp

import android.os.Bundle
import android.view.MotionEvent
import androidx.appcompat.app.AppCompatActivity
import androidx.navigation.findNavController
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.setupActionBarWithNavController
import androidx.navigation.ui.setupWithNavController
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.openreplay.sampleapp.databinding.ActivityMainBinding
import com.openreplay.tracker.OpenReplay
import com.openreplay.tracker.models.OROptions


class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val navView: BottomNavigationView = binding.navView
        val navController = findNavController(R.id.nav_host_fragment_activity_main)
        val appBarConfiguration = AppBarConfiguration(
            setOf(R.id.navigation_home, R.id.navigation_dashboard, R.id.navigation_notifications)
        )
        navController.addOnDestinationChangedListener { _, destination, _ ->
            OpenReplay.event("Event Tab click", destination.label)
        }
        setupActionBarWithNavController(navController, appBarConfiguration)
        navView.setupWithNavController(navController)
    }

    override fun onStart() {
        super.onStart()
        startOpenReplay()
    }

    override fun onStop() {
        super.onStop()
        stopOpenReplay()
    }

    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        ev?.let { OpenReplay.onTouchEvent(it) }
        return super.dispatchTouchEvent(ev)
    }


    private fun startOpenReplay() {
        OpenReplay.setupGestureDetector(this)
        // OpenReplay.serverURL = BuildConfig.SERVER_URL
        OpenReplay.setUserID("TEST")
        OpenReplay.start(
            context = this,
            projectKey = BuildConfig.PROJECT_KEY,
            options = OROptions(screen = true, logs = true, wifiOnly = false),
            onStarted = {
                OpenReplay.event("Test Event", User("John Doe", 25))

                val id = OpenReplay.getSessionID()
                OpenReplay.event("Session ID", id)
            }
        )
    }

    private fun stopOpenReplay() {
        OpenReplay.stop()
    }

    private data class User(val name: String, val age: Int)
}