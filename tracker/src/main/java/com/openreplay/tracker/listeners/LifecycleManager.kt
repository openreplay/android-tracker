package com.openreplay.tracker.listeners

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import com.openreplay.tracker.OpenReplay
import com.openreplay.tracker.managers.DebugUtils
import java.lang.ref.WeakReference

class LifecycleManager(
    context: Context
) : Application.ActivityLifecycleCallbacks {

    private var application: Application? = null
    private var currentActivityRef: WeakReference<Activity>? = null

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
        application?.registerActivityLifecycleCallbacks(this)
    }

    fun unregister() {
        application?.unregisterActivityLifecycleCallbacks(this)
        application = null
        currentActivityRef = null
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        DebugUtils.log("Activity created: ${activity.localClassName}")
        currentActivityRef = WeakReference(activity)
    }

    override fun onActivityStarted(activity: Activity) {
        currentActivityRef = WeakReference(activity)
        Analytics.sendBackgroundEvent(0u) // Send foreground event
        OpenReplay.startSession(
            onStarted = {
                DebugUtils.log("OpenReplay session resumed")
            },
        )
    }

    override fun onActivityResumed(activity: Activity) {
        currentActivityRef = WeakReference(activity)
        // Setup gesture detector for the current activity
        OpenReplay.setupGestureDetectorForActivity(activity)
    }

    override fun onActivityPaused(activity: Activity) {
        // Additional logic if needed
    }

    override fun onActivityStopped(activity: Activity) {
        Analytics.sendBackgroundEvent(1u) // Send background event
        OpenReplay.stop(false)
    }

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {
        // Additional logic if needed
    }

    override fun onActivityDestroyed(activity: Activity) {
        DebugUtils.log("Activity destroyed: ${activity.localClassName}")
        if (currentActivityRef?.get() == activity) {
            currentActivityRef = null
        }
    }
}