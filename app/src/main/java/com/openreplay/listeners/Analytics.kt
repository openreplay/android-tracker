package com.openreplay.listeners

import android.content.Context
import android.graphics.PointF
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import com.openreplay.OpenReplay
import com.openreplay.managers.DebugUtils
import com.openreplay.managers.MessageCollector
import com.openreplay.managers.ScreenshotManager
import com.openreplay.models.script.ORMobileClickEvent
import com.openreplay.models.script.ORMobileEvent
import com.openreplay.models.script.ORMobileInputEvent
import com.openreplay.models.script.ORMobileSwipeEvent
import kotlin.math.abs
import kotlin.math.atan2

enum class SwipeDirection {
    LEFT, RIGHT, UP, DOWN, UNDEFINED;

    companion object {
        fun fromDistances(distanceX: Float, distanceY: Float): SwipeDirection {
            return when {
                distanceX > 0 -> LEFT
                distanceX < 0 -> RIGHT
                distanceY > 0 -> UP
                distanceY < 0 -> DOWN
                else -> UNDEFINED
            }
        }
    }
}

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

    fun sendSwipe(direction: SwipeDirection, x: Float, y: Float) {
        if (!enabled) return

        val message = ORMobileSwipeEvent(direction = direction.name.lowercase(), x = x, y = y, label = "Swipe")
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
    private var swipeDirection: SwipeDirection = SwipeDirection.UNDEFINED

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

                swipeDirection = SwipeDirection.fromDistances(distanceX, distanceY)
                lastX = e2.x
                lastY = e2.y

                handler.removeCallbacks(endOfScrollRunnable)
                handler.postDelayed(endOfScrollRunnable, 200)
                return true
            }
        })
    }

    override fun onStart() {
        super.onStart()

        println("TrackingActivity started ${this::class.java.simpleName} started")
    }

    override fun onStop() {
        super.onStop()

        println("TrackingActivity ${this::class.java.simpleName} stopped")
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            touchStart = PointF(event.x, event.y)
        }

        if (event.actionMasked == MotionEvent.ACTION_UP) {
            touchStart = null
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
    val message = ORMobileEvent(name = "viewAppeared", payload = "$screenName:$viewName")
    MessageCollector.sendMessage(message)
}

fun EditText.trackTextInput(label: String? = null, masked: Boolean = false) {
    this.setOnFocusChangeListener { view, hasFocus ->
        if (!hasFocus) {
            // The EditText has lost focus
            val sender = view as EditText
            textInputFinished(sender, label, masked)
        }
    }

    this.setOnEditorActionListener { v, actionId, _ ->
        if (actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_ACTION_NEXT || actionId == EditorInfo.IME_ACTION_SEND) {
            val sender = v as EditText
            textInputFinished(sender, label, masked)
            false
        } else {
            false
        }
    }


//    this.doAfterTextChanged { text ->
//        if (OpenReplay.options.debugLogs) {
//            DebugUtils.log(">>>>>Text finish ${text.toString()} ${this.hint ?: "no_placeholder"}")
//        }
//
//        val textToSend =
//            if (this.inputType and InputType.TYPE_TEXT_VARIATION_PASSWORD == InputType.TYPE_TEXT_VARIATION_PASSWORD) {
//                "***"
//            } else {
//                text.toString()
//            }
//
//        MessageCollector.sendMessage(
//            ORMobileInputEvent(
//                value = textToSend,
//                valueMasked = isPasswordInputType() || masked,
//                label = this.hint?.toString() ?: ""
//            )
//        )
//    }
}

fun textInputFinished(view: EditText, label: String?, masked: Boolean) {
    val textToSend =
        if (view.inputType and InputType.TYPE_TEXT_VARIATION_PASSWORD == InputType.TYPE_TEXT_VARIATION_PASSWORD) {
            "***"
        } else {
            view.text.toString()
        }

    MessageCollector.sendMessage(
        ORMobileInputEvent(
            value = textToSend,
            valueMasked = view.isPasswordInputType() || masked,
            label = label ?: view.hint?.toString() ?: ""
        )
    )
}

fun EditText.isPasswordInputType(): Boolean {
    val inputTypes = listOf(
        InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
        InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
        InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
    )
    return inputTypes.any { it == this.inputType }
}

fun EditText.sanitize() {
    ScreenshotManager.addSanitizedElement(this)
}