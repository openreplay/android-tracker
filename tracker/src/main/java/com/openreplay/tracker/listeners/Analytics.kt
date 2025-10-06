package com.openreplay.tracker.listeners

import android.content.Context
import android.graphics.PointF
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.AttributeSet
import android.util.Log
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
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.lifecycle.*
import com.openreplay.tracker.managers.MessageCollector
import com.openreplay.tracker.managers.ScreenshotManager
import com.openreplay.tracker.models.script.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.composed

import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.*
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.debugInspectorInfo
import androidx.compose.ui.platform.testTag
import com.openreplay.tracker.OpenReplay
import com.openreplay.tracker.managers.DebugUtils

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
    private var observedViews: MutableList<View> = mutableListOf()
    private var observedInputs: MutableList<EditText> = mutableListOf()

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

        val message = ORMobileSwipeEvent(
            direction = direction.name.lowercase(), x = x, y = y, label = "Swipe"
        )
        MessageCollector.sendMessage(message)
    }

    fun sendTextInput(value: String, label: String?, masked: Boolean = false) {
        if (!enabled) return

        val message = ORMobileInputEvent(
            value = value,
            valueMasked = masked,
            label = label ?: "Input"
        )
        MessageCollector.sendMessage(message)
    }

    fun stop() {
        enabled = false
    }

    fun addObservedView(view: View, screenName: String, viewName: String) {
        view.tag = "Screen: $screenName, View: $viewName"
        observedViews.add(view)
    }

    fun addObservedInput(editText: EditText) {
        observedInputs.add(editText)
        editText.trackTextInput()
//        editText.setOnEditorActionListener { v, actionId, event ->
//            // Handle input finished event here
//            true
//        }
    }

    fun sendBackgroundEvent(value: ULong) {
        val message = ORMobilePerformanceEvent("background", value)
        MessageCollector.sendMessage(message)
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

            override fun onScroll(
                e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float
            ): Boolean {
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

        lifecycle.addObserver(GlobalViewTracker())
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
                val rect = Rect(
                    location[0], location[1], location[0] + child.width, location[1] + child.height
                )
                if (rect.contains(x.toInt(), y.toInt())) {
                    val foundView = findViewAtPosition(child, x, y)
                    if (foundView != null) return foundView
                }
            }
        }
        return root
    }

    private fun getViewDescription(view: View?): String {
        return extractElementLabel(view)
    }

    fun sanitizeView(view: View) {
        ScreenshotManager.addSanitizedElement(view)
    }
}

class TrackingFrameLayout(context: Context, attrs: AttributeSet?) : FrameLayout(context, attrs) {
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
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

class ActivityLifecycleTracker : LifecycleEventObserver {
    override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
        when (event) {
            Lifecycle.Event.ON_CREATE -> {
                println("Activity created")
            }

            Lifecycle.Event.ON_START -> {
                println("Activity started")
            }

            Lifecycle.Event.ON_RESUME -> {
                println("Activity resumed")
            }

            Lifecycle.Event.ON_PAUSE -> {
                println("Activity paused")
            }

            Lifecycle.Event.ON_STOP -> {
                println("Activity stopped")
            }

            Lifecycle.Event.ON_DESTROY -> {
                println("Activity destroyed")
            }

            Lifecycle.Event.ON_ANY -> {
                println("Activity any")
            }
        }
    }
}

/**
 * Extract comprehensive element properties from a view for tracking.
 * Includes: resource ID, text content, content description, and view type.
 */
fun extractElementLabel(view: View?): String {
    if (view == null) return "Unknown View"
    
    val parts = mutableListOf<String>()
    
    // Add resource ID if available
    try {
        if (view.id != View.NO_ID) {
            val resourceName = view.resources.getResourceEntryName(view.id)
            parts.add("id:$resourceName")
        }
    } catch (e: Exception) {
        // Resource not found, skip
    }
    
    // Add text content if available
    val text = when (view) {
        is EditText -> view.text.toString().take(50)
        is TextView -> view.text.toString().take(50)
        is Button -> view.text.toString().take(50)
        else -> null
    }
    
    if (!text.isNullOrBlank()) {
        parts.add("text:$text")
    }
    
    // Add content description if available
    if (!view.contentDescription.isNullOrBlank()) {
        parts.add("desc:${view.contentDescription.toString().take(50)}")
    }
    
    // Add view class name
    parts.add("type:${view.javaClass.simpleName}")
    
    return if (parts.isNotEmpty()) {
        parts.joinToString(" | ")
    } else {
        view.javaClass.simpleName
    }
}

fun trackAllTextViews(view: View) {
//    if (view is TextView) {
//        Analytics.addObservedView(view, "MainActivity", "TextView")
//    }

    if (view is EditText) {
        Analytics.addObservedInput(view)
    }

    if (view is ViewGroup) {
        for (i in 0 until view.childCount) {
            trackAllTextViews(view.getChildAt(i))
        }
    }
}

class GlobalViewTracker : LifecycleEventObserver {
    override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
        when (event) {
            Lifecycle.Event.ON_CREATE -> setupGlobalLayoutListener(source as AppCompatActivity)
            else -> {} // Implement other lifecycle events as needed
        }
    }

    private fun setupGlobalLayoutListener(activity: AppCompatActivity) {
//        val rootView = activity.findViewById<ViewGroup>(android.R.id.content)

        activity.window.decorView.post {
            trackAllTextViews(activity.window.decorView)
        }

//        rootView.viewTreeObserver.addOnGlobalLayoutListener {
//            // Iterate over all the views in the layout
//            for (i in 0 until rootView.childCount) {
//                val view = rootView.getChildAt(i)
//                inspectView(view, activity)
//            }
//        }
    }

    private fun inspectView(view: View, activity: AppCompatActivity) {
        val screenName = activity::class.java.simpleName
        val viewName = view::class.java.simpleName
        val visibility = view.visibility == View.VISIBLE

        // You might want to filter views further here
        Log.d("ViewTracker", "Screen: $screenName, View: $viewName, Visible: $visibility")
    }
}

class ORGestureListener(private val rootView: View) : GestureDetector.SimpleOnGestureListener() {
    private val handler = Handler(Looper.getMainLooper())
    private var isScrolling = false
    private var swipeDirection: SwipeDirection = SwipeDirection.UNDEFINED
    private var lastX: Float = 0f
    private var lastY: Float = 0f

    private val endOfScrollRunnable = Runnable {
        if (isScrolling) {
            isScrolling = false
            
            if (OpenReplay.options.debugLogs) {
                DebugUtils.log("Swipe detected: $swipeDirection at ($lastX, $lastY)")
            }

            Analytics.sendSwipe(swipeDirection, lastX, lastY)
        }
    }

    override fun onSingleTapUp(e: MotionEvent): Boolean {
        val clickedView = findViewAtPosition(rootView, e.rawX, e.rawY)
        val elementLabel = getViewDescription(clickedView)
        
        if (OpenReplay.options.debugLogs) {
            DebugUtils.log("Click detected: $elementLabel at (${e.x}, ${e.y})")
        }
        
        Analytics.sendClick(e, elementLabel)
        return true
    }

    override fun onDown(e: MotionEvent): Boolean {
        return true
    }

    override fun onScroll(
        e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float
    ): Boolean {
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

    private fun findViewAtPosition(root: View, x: Float, y: Float): View? {
        if (!root.isShown) return null
        
        // Get view's position on screen
        val location = IntArray(2)
        root.getLocationOnScreen(location)
        val viewX = location[0]
        val viewY = location[1]
        
        // Check if point is within this view's bounds
        val isInBounds = x >= viewX && x <= viewX + root.width && 
                        y >= viewY && y <= viewY + root.height
        
        if (!isInBounds) return null
        
        // If this is a ViewGroup, check children first (from top to bottom)
        if (root is ViewGroup) {
            for (i in root.childCount - 1 downTo 0) {
                val child = root.getChildAt(i)
                val foundView = findViewAtPosition(child, x, y)
                if (foundView != null) return foundView
            }
        }
        
        // Return this view if no child was found
        return root
    }

    private fun isPointInsideViewBounds(view: View, x: Float, y: Float): Boolean {
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        return x >= location[0] && x <= location[0] + view.width && 
               y >= location[1] && y <= location[1] + view.height
    }

    private fun getViewDescription(view: View?): String {
        return extractElementLabel(view)
    }
}

fun Modifier.trackTouchEvents(label: String? = "Unknown"): Modifier {
    var initialX = 0f
    var initialY = 0f
    var currentX = 0f
    var currentY = 0f
    return this.pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent()
                event.changes.forEach { change ->
                    if (change.pressed) {
//                        position = "X: ${change.position.x}, Y: ${change.position.y}"
                        println("$label X: ${change.position.x}, Y: ${change.position.y}")
                        change.consume()
                        Analytics.sendClick(
                            MotionEvent.obtain(
                                0,
                                0,
                                MotionEvent.ACTION_UP,
                                change.position.x,
                                change.position.y,
                                0
                            ), label
                        )
                    }
                }
            }
        }
    }.pointerInput(Unit) {
        detectDragGestures(onDragStart = { offset ->
            initialX = offset.x
            initialY = offset.y
            println("onDragStart at ${offset.x}, ${offset.y}")
        }, onDragEnd = {
            val distanceX = currentX - initialX
            val distanceY = currentY - initialY
            val direction = SwipeDirection.fromDistances(distanceX, distanceY)
            println("onDragEnd with swipe direction: $direction at ($currentX, $currentY)")
            Analytics.sendSwipe(direction, currentX, currentY)
        }, onDragCancel = {
            println("onDragCancel")
        }, onDrag = { change, _ ->
            currentX = change.position.x
            currentY = change.position.y
        })
    }
}

fun Modifier.trackTextInputChanges(
    label: String,
    value: String? = null,
    isMasked: Boolean = false
): Modifier {
    return this.onFocusChanged {
        if (!it.isFocused) {
            Analytics.sendTextInput(value ?: "", label, isMasked)
        }
    }
}

fun Modifier.sanitized(): Modifier = this.then(
    Modifier.testTag("sanitized")
)


