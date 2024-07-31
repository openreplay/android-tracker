package com.openreplay.sampleapp

import android.os.Bundle
import android.view.GestureDetector
import android.view.MotionEvent
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.bottomnavigation.BottomNavigationView
import androidx.navigation.findNavController
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.setupActionBarWithNavController
import androidx.navigation.ui.setupWithNavController
import com.openreplay.sampleapp.databinding.ActivityMainBinding
import com.openreplay.tracker.OpenReplay
import com.openreplay.tracker.listeners.ORGestureListener
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
            setOf(
                R.id.navigation_home, R.id.navigation_dashboard, R.id.navigation_notifications
            )
        )
        setupActionBarWithNavController(navController, appBarConfiguration)
        navView.setupWithNavController(navController)

        OpenReplay.setupGestureDetector(this)
        OpenReplay.serverURL = BuildConfig.SERVER_URL
        OpenReplay.start(
            this,
            BuildConfig.PROJECT_KEY,
            OROptions.defaults,
            onStarted = {
                println("OpenReplay Started")
                OpenReplay.setUserID("Library")

                data class User(val name: String, val age: Int)
                OpenReplay.event("Test Event", User("John", 25))
            }
        )
    }


    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        ev?.let {
            OpenReplay.onTouchEvent(it)
        }
        return super.dispatchTouchEvent(ev)
    }
}