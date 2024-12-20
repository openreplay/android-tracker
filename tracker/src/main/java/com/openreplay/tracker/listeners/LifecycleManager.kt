package com.openreplay.tracker.listeners

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import com.openreplay.tracker.OpenReplay
import com.openreplay.tracker.managers.DebugUtils

class LifecycleManager(
    context: Context
) : Application.ActivityLifecycleCallbacks {

    init {
        if (context is Activity) {
            context.application.registerActivityLifecycleCallbacks(this)
        } else {
            throw IllegalArgumentException("Context must be an instance of Activity")
        }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        DebugUtils.log("Activity created: ${activity.localClassName}")
    }

    override fun onActivityStarted(activity: Activity) {
        Analytics.sendBackgroundEvent(1u) // Send foreground event
        OpenReplay.startSession(
            onStarted = {
                DebugUtils.log("OpenReplay session resumed")
            },
        )
    }

    override fun onActivityResumed(activity: Activity) {
        // Additional logic if needed
    }

    override fun onActivityPaused(activity: Activity) {
        // Additional logic if needed
    }

    override fun onActivityStopped(activity: Activity) {
        Analytics.sendBackgroundEvent(0u) // Send background event
        OpenReplay.stop(false)
    }

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {
        // Additional logic if needed
    }

    override fun onActivityDestroyed(activity: Activity) {
        DebugUtils.log("Activity destroyed: ${activity.localClassName}")
    }
}