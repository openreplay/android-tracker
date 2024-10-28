package com.openreplay.tracker

import NetworkManager
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.google.gson.Gson
import com.openreplay.tracker.listeners.Analytics
import com.openreplay.tracker.listeners.Crash
import com.openreplay.tracker.listeners.LogsListener
import com.openreplay.tracker.listeners.ORGestureListener
import com.openreplay.tracker.listeners.PerformanceListener
import com.openreplay.tracker.listeners.sendNetworkMessage
import com.openreplay.tracker.managers.ConditionsManager
import com.openreplay.tracker.managers.DebugUtils
import com.openreplay.tracker.managers.MessageCollector
import com.openreplay.tracker.managers.ScreenshotManager
import com.openreplay.tracker.managers.UserDefaults
import com.openreplay.tracker.models.OROptions
import com.openreplay.tracker.models.RecordingQuality
import com.openreplay.tracker.models.SessionRequest
import com.openreplay.tracker.models.script.ORMobileEvent
import com.openreplay.tracker.models.script.ORMobileMetadata
import com.openreplay.tracker.models.script.ORMobileUserID
import java.io.File
import java.util.Date
import kotlin.math.max
import kotlin.math.min

enum class CheckState {
    UNCHECKED, CAN_START, CANT_START
}

object OpenReplay {
    var projectKey: String? = null
    var sessionStartTs: Long = 0
    var bufferingMode = false
    var options: OROptions = OROptions.defaults

    var serverURL: String
        get() = NetworkManager.baseUrl
        set(value) {
            NetworkManager.baseUrl = value
        }

    private var appContext: Context? = null
    private var gestureDetector: GestureDetector? = null


    fun start(context: Context, projectKey: String, options: OROptions, onStarted: () -> Unit) {
        UserDefaults.init(context)
        this.appContext = context // Use application context to avoid leaks
        this.options = this.options.merge(options)
        this.projectKey = projectKey

        val connectivityManager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val networkCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(
                    network: android.net.Network,
                    capabilities: NetworkCapabilities
                ) {
                    super.onCapabilitiesChanged(network, capabilities)
                    handleNetworkChange(capabilities, onStarted)
                }
            }

            connectivityManager.registerDefaultNetworkCallback(networkCallback)
        } else {
            // For API levels below 24, listen for connectivity changes using BroadcastReceiver
            val intentFilter = IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION)
            context.registerReceiver(object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    val activeNetworkInfo = connectivityManager.activeNetworkInfo
                    if (activeNetworkInfo != null && activeNetworkInfo.isConnected) {
                        // Call your method to handle connectivity change
                        sessionStartTs = Date().time
                        startSession(onStarted)
                    }
                }
            }, intentFilter)
        }
        checkForLateMessages()
    }

    private fun handleNetworkChange(capabilities: NetworkCapabilities, onStarted: () -> Unit) {
        when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) && !options.wifiOnly -> {
                sessionStartTs = Date().time
                startSession(onStarted)
            }
        }
    }

    private fun startSession(onStarted: () -> Unit) {
        setupGestureDetector(appContext!!)
        SessionRequest.create(appContext!!, false) { sessionResponse ->
            sessionResponse ?: return@create println("Openreplay: no response from /start request")
            MessageCollector.start()

            val captureSettings =
                getCaptureSettings(fps = 1, quality = this.options.screenshotQuality)
            ScreenshotManager.setSettings(settings = captureSettings)

            with(options) {
                if (logs) LogsListener.start()
                if (crashes) {
                    Crash.init(appContext!!)
                    Crash.start()
                }
                if (performances) PerformanceListener.getInstance(appContext!!).start()
                if (screen) ScreenshotManager.start(appContext!!, sessionStartTs)
                if (analytics) Analytics.start()
            }
            onStarted()
        }
    }

    fun getLateMessagesFile(): File {
        return File(appContext!!.cacheDir, "lateMessages.dat")
    }

    fun coldStart(context: Context, projectKey: String, options: OROptions, onStarted: () -> Unit) {
        this.appContext = context // Use application context to avoid leaks
        this.options = options
        this.projectKey = projectKey
        this.bufferingMode = true
        UserDefaults.init(context)

        SessionRequest.create(appContext!!, false) { sessionResponse ->
            sessionResponse ?: return@create println("Openreplay: no response from /start request")
            ConditionsManager.getConditions()
            MessageCollector.cycleBuffer()
            onStarted()

            with(this.options) {
                if (logs) LogsListener.start()
                if (crashes) {
                    Crash.init(appContext!!)
                    Crash.start()
                }
                if (performances) PerformanceListener.getInstance(appContext!!).start()
                if (screen) {
                    val captureSettings =
                        getCaptureSettings(fps = 1, quality = options.screenshotQuality)
                    ScreenshotManager.setSettings(settings = captureSettings)
                    ScreenshotManager.start(appContext!!, sessionStartTs)
                    ScreenshotManager.cycleBuffer()
                }
                if (analytics) Analytics.start()
            }
        }
    }

    fun triggerRecording(condition: String?) {
        this.bufferingMode = false
        if (options.debugLogs) {
            DebugUtils.log("Triggering recording with condition: $condition")
        }
        SessionRequest.create(context = appContext!!, doNotRecord = false) { sessionResponse ->
            sessionResponse?.let {
                MessageCollector.syncBuffers()
                ScreenshotManager.syncBuffers()
                MessageCollector.start()
            } ?: run {
                println("Openreplay: no response from /start request")
            }
        }
    }

    private fun checkForLateMessages() {
        val lateMessagesFile = File(appContext!!.filesDir, "lateMessages.dat")
        if (lateMessagesFile.exists()) {
            val crashData = lateMessagesFile.readBytes()
            NetworkManager.sendLateMessage(crashData) { success ->
                if (success) {
                    lateMessagesFile.delete()
                }
            }
        }
    }

    fun stop() {
        ScreenshotManager.stopCapturing()
        Analytics.stop()
        LogsListener.stop()
        PerformanceListener.getInstance(appContext!!).stop()
        Crash.stop()
        MessageCollector.stop()
    }

    fun setUserID(userID: String) {
        val message = ORMobileUserID(iD = userID)
        MessageCollector.sendMessage(message)
    }

    fun setMetadata(key: String, value: String) {
        val message = ORMobileMetadata(key = key, value = value)
        MessageCollector.sendMessage(message)
    }

    fun userAnonymousID(iD: String) {
        val message = ORMobileUserID(iD = iD)
        MessageCollector.sendMessage(message)
    }

    fun addIgnoredView(view: View) {
        ScreenshotManager.addSanitizedElement(view)
    }

    fun sanitizeView(view: View) {
        ScreenshotManager.addSanitizedElement(view)
    }

    fun event(name: String, `object`: Any?) {
        val gson = Gson()
        val jsonPayload = `object`?.let { gson.toJson(it) } ?: ""
        eventStr(name, jsonPayload)
    }

    fun networkRequest(
        url: String,
        method: String,
        requestJSON: String,
        responseJSON: String,
        status: Int,
        duration: ULong
    ) {
        sendNetworkMessage(url, method, requestJSON, responseJSON, status, duration.toLong())
    }

    fun eventStr(name: String, jsonPayload: String) {
        val message = ORMobileEvent(name, payload = jsonPayload)
        MessageCollector.sendMessage(message)
    }

//    fun setupGestureDetector(context: Context) {
//        val rootView = (context as Activity).window.decorView.rootView
//        val gestureListener = ORGestureListener(rootView)
//        this.gestureDetector = GestureDetector(context, gestureListener)
//    }

//    @Composable
//    fun GestureDetectorBox(onGestureDetected: () -> Unit) {
//        val context = LocalContext.current
//        setupGestureDetector(context) {
//            onGestureDetected()
//        }
//
//        Box(
//            modifier = Modifier
//                .fillMaxSize()
//                .pointerInput(Unit) {
//                    detectTapGestures(
//                        onTap = {
//                            onGestureDetected()
//                        }
//                    )
//                }
//        ) {
//            Text(text = "Tap me")
//        }
//    }

    fun setupGestureDetector(context: Context) {
        val activity = context as Activity
        val rootView = activity.window.decorView.rootView

        val gestureListener = ORGestureListener(rootView)
        val gestureDetector = GestureDetector(context, gestureListener)

        // Set up gesture detection for legacy Android views
        rootView.setOnTouchListener { v, event ->
            gestureDetector.onTouchEvent(event)
        }

        // Handle Jetpack Compose views
        if (rootView is ViewGroup) {
            println("jetpack view listener")
            for (i in 0 until rootView.childCount) {
                val child = rootView.getChildAt(i)
                if (child is AbstractComposeView) {
                    println("child listener")
                    child.setOnTouchListener { v, event ->
                        gestureDetector.onTouchEvent(event)
                    }
                }
            }
        }
    }

    fun onTouchEvent(event: MotionEvent) {
        this.gestureDetector?.onTouchEvent(event)
    }
}

fun getCaptureSettings(fps: Int, quality: RecordingQuality): Triple<Int, Int, Int> {
    val limitedFPS = min(max(fps, 1), 99)
    val captureRate = 1000 / limitedFPS // Milliseconds per frame

    val imgCompression = when (quality) {
        RecordingQuality.Low -> 10
        RecordingQuality.Standard -> 30
        RecordingQuality.High -> 60
    }
    val imgResolution = when (quality) {
        RecordingQuality.Low -> 480
        RecordingQuality.Standard -> 720
        RecordingQuality.High -> 1080
    }

    return Triple(captureRate, imgCompression, imgResolution)
}

class SanitizableViewGroup(context: Context) : ViewGroup(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        var maxHeight = 0
        var maxWidth = 0

        for (i in 0 until childCount) {
            val child = getChildAt(i)
            measureChild(child, widthMeasureSpec, heightMeasureSpec)
            maxWidth = maxOf(maxWidth, child.measuredWidth)
            maxHeight = maxOf(maxHeight, child.measuredHeight)
        }

        val width = resolveSize(maxWidth, widthMeasureSpec)
        val height = resolveSize(maxHeight, heightMeasureSpec)
        setMeasuredDimension(width, height)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            child.layout(0, 0, child.measuredWidth, child.measuredHeight)
        }
    }
}


@Composable
fun Sanitized(
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    AndroidView(
        factory = {
            SanitizableViewGroup(context).apply {
                // Add a FrameLayout to hold the composable content
                val frameLayout = FrameLayout(context)
                addView(frameLayout)

                // Set LayoutParams for the frame layout
                frameLayout.layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .wrapContentHeight(),
        update = { viewGroup ->
            // Update the content inside the frame layout
            val frameLayout = viewGroup.getChildAt(0) as FrameLayout
            frameLayout.removeAllViews()

            ComposeView(context).apply {
                setContent {
                    content()
                }
                frameLayout.addView(this)
            }
        }
    )
}
