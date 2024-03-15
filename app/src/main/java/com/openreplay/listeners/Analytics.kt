package com.openreplay.listeners

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import com.openreplay.managers.MessageCollector
import com.openreplay.models.script.ORIOSClickEvent

object Analytics {
    private var enabled: Boolean = false

    fun start() {
        enabled = true
    }

    fun sendClick(ev: MotionEvent) {
        if (!enabled) return

        val message = ORIOSClickEvent(label = "Button", x = ev.x, y = ev.y)
        MessageCollector.sendMessage(message)
    }

    fun stop() {
        enabled = false
    }
}

class AppLifecycleTracker : Application.ActivityLifecycleCallbacks {
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        // Track activity creation or view appearances
        println("Activity created: ${activity.localClassName}")
    }

    override fun onActivityStarted(activity: Activity) {
        TODO("Not yet implemented")
    }

    override fun onActivityResumed(activity: Activity) {
        TODO("Not yet implemented")
    }

    override fun onActivityPaused(activity: Activity) {
        TODO("Not yet implemented")
    }

    override fun onActivityStopped(activity: Activity) {
        TODO("Not yet implemented")
    }

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {
        TODO("Not yet implemented")
    }

    override fun onActivityDestroyed(activity: Activity) {
        // Track activity destruction or view disappearances
    }
}

open class TrackingActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Any global setup for onCreate lifecycle event
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        Analytics.sendClick(ev)
        return super.dispatchTouchEvent(ev)
    }
}

class TrackingFrameLayout(context: Context, attrs: AttributeSet?) : FrameLayout(context, attrs) {
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        // Handle touch event tracking
        return super.dispatchTouchEvent(ev)
    }
}

fun View.trackViewAppearances(screenName: String, viewName: String) {
    // Setup tracking logic, potentially using OnAttachStateChangeListener
}


fun EditText.trackTextInput(label: String? = null, masked: Boolean = false) {
    this.doAfterTextChanged { text ->
        // Handle text input tracking
    }
}