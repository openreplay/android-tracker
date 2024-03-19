package com.openreplay.sampleapp

import android.os.Bundle
import com.google.android.material.bottomnavigation.BottomNavigationView
import androidx.navigation.findNavController
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.setupActionBarWithNavController
import androidx.navigation.ui.setupWithNavController
import com.openreplay.ORTracker
import com.openreplay.listeners.TrackingActivity
import com.openreplay.models.OROptions
import com.openreplay.sampleapp.databinding.ActivityMainBinding

class MainActivity : TrackingActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
//        registerActivityLifecycleCallbacks(AppLifecycleTracker())

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val navView: BottomNavigationView = binding.navView

        val navController = findNavController(R.id.nav_host_fragment_activity_main)
        // Passing each menu ID as a set of Ids because each
        // menu should be considered as top level destinations.
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
    }
}