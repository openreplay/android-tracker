package com.openreplay.tracker.listeners

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import com.openreplay.tracker.OpenReplay
import com.openreplay.tracker.managers.DebugUtils
import java.lang.ref.WeakReference

class LifecycleManager(
    context: Context,
    initialActivity: Activity? = null
) : Application.ActivityLifecycleCallbacks {

    private var application: Application? = null
    private var currentActivityRef: WeakReference<Activity>? = null
    
    // Track activity count to determine if app is truly in background
    @Volatile
    private var startedActivityCount = 0
    
    // Track if we're in a configuration change
    @Volatile
    private var isChangingConfiguration = false

    val currentActivity: Activity?
        get() = currentActivityRef?.get()

    init {
        application = if (context is Activity) {
            context.application
        } else if (context is Application) {
            context
        } else {
            context.applicationContext as? Application
        }
        
        // Capture initial activity if provided
        initialActivity?.let {
            currentActivityRef = WeakReference(it)
            DebugUtils.log("LifecycleManager initialized with activity: ${it.localClassName}")
        } ?: run {
            DebugUtils.log("LifecycleManager initialized without initial activity")
        }
        
        application?.registerActivityLifecycleCallbacks(this)
    }

    fun unregister() {
        application?.unregisterActivityLifecycleCallbacks(this)
        application = null
        currentActivityRef?.clear()
        currentActivityRef = null
        startedActivityCount = 0
        
        if (OpenReplay.options.debugLogs) {
            DebugUtils.log("LifecycleManager unregistered")
        }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        if (OpenReplay.options.debugLogs) {
            DebugUtils.log("Activity created: ${activity.localClassName}")
        }
        currentActivityRef = WeakReference(activity)
    }

    override fun onActivityStarted(activity: Activity) {
        currentActivityRef = WeakReference(activity)
        startedActivityCount++
        
        // Only start session when transitioning from background to foreground
        if (startedActivityCount == 1) {
            if (OpenReplay.options.debugLogs) {
                DebugUtils.log("App entering foreground")
            }
            Analytics.sendBackgroundEvent(0u) // Send foreground event
            OpenReplay.startSession(
                onStarted = {
                    if (OpenReplay.options.debugLogs) {
                        DebugUtils.log("OpenReplay session resumed")
                    }
                }
            )
        } else {
            if (OpenReplay.options.debugLogs) {
                DebugUtils.log("Activity started (count: $startedActivityCount): ${activity.localClassName}")
            }
        }
    }

    override fun onActivityResumed(activity: Activity) {
        currentActivityRef = WeakReference(activity)
        // Setup gesture detector for the current activity
        OpenReplay.setupGestureDetectorForActivity(activity)
    }

    override fun onActivityPaused(activity: Activity) {
        // Check if this is due to configuration change
        isChangingConfiguration = activity.isChangingConfigurations
        if (OpenReplay.options.debugLogs && isChangingConfiguration) {
            DebugUtils.log("Activity paused due to configuration change: ${activity.localClassName}")
        }
    }

    override fun onActivityStopped(activity: Activity) {
        startedActivityCount--
        
        // Only stop session when all activities are stopped AND not a configuration change
        if (startedActivityCount <= 0 && !isChangingConfiguration) {
            if (OpenReplay.options.debugLogs) {
                DebugUtils.log("App entering background")
            }
            Analytics.sendBackgroundEvent(1u) // Send background event
            OpenReplay.stop(false)
            startedActivityCount = 0 // Ensure it doesn't go negative
        } else {
            if (OpenReplay.options.debugLogs) {
                DebugUtils.log("Activity stopped (remaining: $startedActivityCount): ${activity.localClassName}")
            }
        }
        
        // Reset configuration change flag
        isChangingConfiguration = false
    }

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {
        // Additional logic if needed
    }

    override fun onActivityDestroyed(activity: Activity) {
        if (OpenReplay.options.debugLogs) {
            DebugUtils.log("Activity destroyed: ${activity.localClassName}")
        }
        if (currentActivityRef?.get() == activity) {
            currentActivityRef?.clear()
            currentActivityRef = null
        }
    }
}