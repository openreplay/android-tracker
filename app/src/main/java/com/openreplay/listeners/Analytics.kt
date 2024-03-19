package com.openreplay.listeners

import android.content.Context
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import com.openreplay.managers.DebugUtils
import com.openreplay.managers.MessageCollector
import com.openreplay.models.script.ORMobileClickEvent
import com.openreplay.models.script.ORMobileSwipeEvent
import kotlin.math.abs

object Analytics {
    private var enabled: Boolean = false

    fun start() {
        enabled = true
    }

    fun sendClick(ev: MotionEvent) {
        if (!enabled) return

        val message = ORMobileClickEvent(label = "Button", x = ev.x, y = ev.y)
        MessageCollector.sendMessage(message)
    }

    fun sendSwipe(direction: String, velocityX: Float, velocityY: Float) {
        if (!enabled) return

        val message = ORMobileSwipeEvent(direction = direction, x = velocityX, y = velocityY, label = "Swipe")
        MessageCollector.sendMessage(message)
    }

    fun stop() {
        enabled = false
    }
}

open class TrackingActivity : AppCompatActivity(), GestureDetector.OnGestureListener {
    private lateinit var gestureDetector: GestureDetector
    private var isScrolling = false
    private val handler = Handler(Looper.getMainLooper())

    // Variables to store the last known positions
    private var lastX: Float = 0f
    private var lastY: Float = 0f

    private val rootView: View
        get() = window.decorView.rootView

    private val endOfScrollRunnable = Runnable {
        if (isScrolling) {
            isScrolling = false
            // Scroll has ended, send the event
            Analytics.sendSwipe("ScrollEnd", lastX, lastY)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        gestureDetector = GestureDetector(this, this)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    override fun onDown(e: MotionEvent): Boolean {
//        val view = findViewAtPosition(rootView, e.x, e.y)  // TODO TBD what to capture here
        Analytics.sendClick(e)
        return true
    }

    private fun findViewAtPosition(view: View, x: Float, y: Float): View? {
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                val child = view.getChildAt(i)
                val rect = Rect()
                child.getGlobalVisibleRect(rect)
                if (rect.contains(x.toInt(), y.toInt())) {
                    return findViewAtPosition(child, x, y) ?: child
                }
            }
        }
        return null
    }

    override fun onShowPress(e: MotionEvent) {
//        DebugUtils.log("Show press detected")
    }

    override fun onSingleTapUp(e: MotionEvent): Boolean {
//        DebugUtils.log("Single tap detected")
        return true
    }

    //override fun onScroll(e1: MotionEvent, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
    override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
        if (!isScrolling) {
            isScrolling = true
            // Scroll has started, you could also capture and send start position if needed
            // Analytics.sendSwipe("ScrollStart", e2.x, e2.y)
        }

        // Update last known positions on every scroll update
        lastX = e2.x
        lastY = e2.y

        // Remove any previous callbacks to reset the timer
        handler.removeCallbacks(endOfScrollRunnable)
        // Post a delayed runnable to catch end of scroll
        handler.postDelayed(endOfScrollRunnable, 200) // Adjust delay as needed
        return true
    }

    override fun onLongPress(e: MotionEvent) {
        // DebugUtils.log("Long press detected")
    }

    override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
        val deltaX = e2.x - (e1?.x ?: 0f)
        val deltaY = e2.y - (e1?.y ?: 0f)
        if (abs(deltaX) > abs(deltaY)) {
            if (deltaX > 0) {
                Analytics.sendSwipe("right", velocityX, velocityY)
            } else {
                Analytics.sendSwipe("left", velocityX, velocityY)
            }
        } else {
            if (deltaY > 0) {
                Analytics.sendSwipe("down", velocityX, velocityY)
            } else {
                Analytics.sendSwipe("up", velocityX, velocityY)
            }
        }
        return true
    }
}

class TrackingFrameLayout(context: Context, attrs: AttributeSet?) : FrameLayout(context, attrs) {
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        // Handle touch event tracking
        return super.dispatchTouchEvent(ev)
    }
}

fun View.trackViewAppearances(screenName: String, viewName: String) {
    // Handle view appearance tracking
}


fun EditText.trackTextInput(label: String? = null, masked: Boolean = false) {
    this.doAfterTextChanged { text ->
        // Handle text input tracking
    }
}