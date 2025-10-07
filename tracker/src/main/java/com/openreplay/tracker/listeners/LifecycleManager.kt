package com.openreplay.tracker.listeners

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import com.openreplay.tracker.OpenReplay
import com.openreplay.tracker.R
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
        
        DebugUtils.log("LifecycleManager unregistered")
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        DebugUtils.log("Activity created: ${activity.localClassName}")
        currentActivityRef = WeakReference(activity)
    }

    override fun onActivityStarted(activity: Activity) {
        currentActivityRef = WeakReference(activity)
        startedActivityCount++
        
        // Only resume session when transitioning from background to foreground
        if (startedActivityCount == 1) {
            DebugUtils.log("App entering foreground - resuming tracker")
            OpenReplay.resume()
            Analytics.sendBackgroundEvent(0u)
        } else {
            DebugUtils.log("Activity started (count: $startedActivityCount): ${activity.localClassName}")
        }
    }

    override fun onActivityResumed(activity: Activity) {
        currentActivityRef = WeakReference(activity)
        OpenReplay.setupGestureDetectorForActivity(activity)
        
        if (OpenReplay.options.analytics) {
            activity.window?.decorView?.post {
                autoTrackInputFields(activity)
            }
        }
    }
    
    private fun autoTrackInputFields(activity: Activity) {
        val rootView = activity.window?.decorView?.rootView
        if (rootView != null) {
            val editTexts = findAllEditTexts(rootView)
            var trackedCount = 0
            
            editTexts.forEach { editText ->
                if (!isAlreadyTracked(editText) && !isExcluded(editText)) {
                    val label = editText.hint?.toString() 
                        ?: editText.contentDescription?.toString()
                        ?: "input_${editText.id}"
                    
                    val isSensitive = editText.isPasswordInputType()
                    editText.trackTextInput(label = label, masked = isSensitive)
                    editText.setTag(R.id.openreplay_tracked, true)
                    trackedCount++
                }
            }
            
            if (trackedCount > 0) {
                DebugUtils.log("Auto-tracked $trackedCount input field(s) in ${activity.localClassName}")
            }
        }
    }
    
    private fun findAllEditTexts(view: View): List<EditText> {
        val editTexts = mutableListOf<EditText>()
        
        if (view is EditText) {
            editTexts.add(view)
        } else if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                editTexts.addAll(findAllEditTexts(view.getChildAt(i)))
            }
        }
        
        return editTexts
    }
    
    private fun isAlreadyTracked(editText: EditText): Boolean {
        return editText.getTag(R.id.openreplay_tracked) == true
    }
    
    private fun isExcluded(editText: EditText): Boolean {
        return editText.getTag(R.id.openreplay_exclude) == true
    }

    override fun onActivityPaused(activity: Activity) {
        isChangingConfiguration = activity.isChangingConfigurations
        if (isChangingConfiguration) {
            DebugUtils.log("Activity paused due to configuration change: ${activity.localClassName}")
        }
    }

    override fun onActivityStopped(activity: Activity) {
        startedActivityCount--
        
        // Only pause session when all activities are stopped AND not a configuration change
        if (startedActivityCount <= 0 && !isChangingConfiguration) {
            DebugUtils.log("App entering background - pausing tracker")
            Analytics.sendBackgroundEvent(1u)
            OpenReplay.pause()
            startedActivityCount = 0 // Ensure it doesn't go negative
        } else {
            DebugUtils.log("Activity stopped (remaining: $startedActivityCount): ${activity.localClassName}")
        }
        
        isChangingConfiguration = false
    }

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {
        // Additional logic if needed
    }

    override fun onActivityDestroyed(activity: Activity) {
        DebugUtils.log("Activity destroyed: ${activity.localClassName}")
        if (currentActivityRef?.get() == activity) {
            currentActivityRef?.clear()
            currentActivityRef = null
        }
    }
}