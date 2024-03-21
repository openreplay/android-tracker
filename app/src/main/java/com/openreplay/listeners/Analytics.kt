package com.openreplay.listeners

import android.content.Context
import android.graphics.PointF
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import com.openreplay.managers.MessageCollector
import com.openreplay.managers.ScreenshotManager
import com.openreplay.models.script.ORMobileClickEvent
import com.openreplay.models.script.ORMobileInputEvent
import com.openreplay.models.script.ORMobileSwipeEvent
import kotlin.math.abs
import kotlin.math.atan2

object Analytics {
    private var enabled: Boolean = false

    fun start() {
        enabled = true
    }

    fun sendClick(ev: MotionEvent, label: String? = null) {
        if (!enabled) return


        val message = ORMobileClickEvent(label = label ?: "Button", x = ev.x, y = ev.y)
        MessageCollector.sendMessage(message)
    }

    fun sendSwipe(direction: String, x: Float, y: Float) {
        if (!enabled) return

        val message = ORMobileSwipeEvent(direction = direction, x = x, y = y, label = "Swipe")
        MessageCollector.sendMessage(message)
    }

    fun stop() {
        enabled = false
    }
}

open class TrackingActivity : AppCompatActivity() {
    private lateinit var gestureDetector: GestureDetector
    private val handler = Handler(Looper.getMainLooper())
    private var touchStart: PointF? = null
    private var isScrolling = false
    private var lastX: Float = 0f
    private var lastY: Float = 0f
    private var swipeDirection: String = "Undefined"

    private val rootView: View
        get() = window.decorView.rootView


    private val endOfScrollRunnable = Runnable {
        if (isScrolling) {
            isScrolling = false

            Analytics.sendSwipe(swipeDirection, lastX, lastY)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
//        gestureDetector = GestureDetector(this, this)
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                val clickedView = findViewAtPosition(rootView, e.x, e.y)
                val description = getViewDescription(clickedView)
                Analytics.sendClick(e, description)
                return true
            }

            override fun onDown(e: MotionEvent): Boolean {
                return true
            }

            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
                if (!isScrolling) {
                    isScrolling = true
                }

                swipeDirection = when {
                    distanceX > 0 -> "left"
                    distanceX < 0 -> "right"
                    distanceY > 0 -> "up"
                    distanceY < 0 -> "down"
                    else -> "Undefined"
                }
                lastX = e2.x
                lastY = e2.y

                handler.removeCallbacks(endOfScrollRunnable)
                handler.postDelayed(endOfScrollRunnable, 200) // Adjust delay as needed
                return true
            }
        })
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            touchStart = PointF(event.x, event.y)
        }
        return super.dispatchTouchEvent(event)
    }

    private fun findViewAtPosition(root: View, x: Float, y: Float): View? {
        if (!View::class.java.isInstance(root) || !root.isShown) return null
        if (root is ViewGroup) {
            for (i in root.childCount - 1 downTo 0) {
                val child = root.getChildAt(i)
                val location = IntArray(2)
                child.getLocationOnScreen(location)
                val rect = Rect(location[0], location[1], location[0] + child.width, location[1] + child.height)
                if (rect.contains(x.toInt(), y.toInt())) {
                    val foundView = findViewAtPosition(child, x, y)
                    if (foundView != null) return foundView
                }
            }
        }
        return root
    }

    private fun getViewDescription(view: View?): String {
        return when (view) {
            is EditText -> view.text.toString()
            is TextView -> view.text.toString()
            is Button -> view.text.toString()
            else -> view?.javaClass?.simpleName ?: "Unknown View"
        }
    }

    private fun getSwipeDirection(start: MotionEvent, end: MotionEvent): String {
        val deltaX = end.x - start.x
        val deltaY = end.y - start.y
        val angle = atan2(deltaY, deltaX) * (180 / Math.PI)
        return when {
            angle > 45 && angle <= 135 -> "up"
            angle >= -135 && angle <= -45 -> "down"
            angle < -135 || angle > 135 -> "left"
            else -> "right"
        }
    }

    fun sanitizeView(view: View) {
        ScreenshotManager.addSanitizedElement(view)
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
        val message = ORMobileInputEvent(label = label ?: "Input", value = text.toString(), valueMasked = masked)
        MessageCollector.sendMessage(message)
    }
}

fun EditText.sanitize() {
    ScreenshotManager.addSanitizedElement(this)
}