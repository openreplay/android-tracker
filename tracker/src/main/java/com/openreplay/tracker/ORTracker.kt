package com.openreplay.tracker


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
import com.openreplay.tracker.listeners.LifecycleManager
import com.openreplay.tracker.listeners.LogsListener
import com.openreplay.tracker.listeners.ORGestureListener
import com.openreplay.tracker.listeners.PerformanceListener
import com.openreplay.tracker.listeners.sendNetworkMessage
import com.openreplay.tracker.managers.NetworkManager
import com.openreplay.tracker.managers.ConditionsManager
import com.openreplay.tracker.managers.DebugUtils
import com.openreplay.tracker.managers.MessageCollector
import com.openreplay.tracker.managers.MessageHandler
import com.openreplay.tracker.managers.ScreenshotManager
import com.openreplay.tracker.managers.UserDefaults
import com.openreplay.tracker.models.OROptions
import com.openreplay.tracker.models.RecordingQuality
import com.openreplay.tracker.models.SessionRequest
import com.openreplay.tracker.models.script.ORMobileEvent
import com.openreplay.tracker.models.script.ORMobileMetadata
import com.openreplay.tracker.models.script.ORMobileUserID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
    private var lifecycleManager: LifecycleManager? = null
    private var lateMessagesFile: File? = null

    var serverURL: String
        get() = NetworkManager.baseUrl
        set(value) {
            NetworkManager.baseUrl = value
        }

    private var appContext: Context? = null
    private var gestureDetector: GestureDetector? = null
    private var gestureListener: ORGestureListener? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var broadcastReceiver: BroadcastReceiver? = null
    private var connectivityManager: ConnectivityManager? = null
    
    // Reusable Gson instance to avoid creating new instances
    private val gson by lazy { Gson() }
    
    // Session state management
    @Volatile
    private var isSessionStarted = false
    private val sessionLock = Any()


    fun start(context: Context, projectKey: String, options: OROptions, onStarted: () -> Unit) {
        // Use application context to avoid leaks
        val appContext = context.applicationContext
        NetworkManager.initialize(appContext)
        CoroutineScope(Dispatchers.IO).launch {
            UserDefaults.init(appContext)
        }
        this.appContext = appContext
        this.options = this.options.merge(options)
        this.projectKey = projectKey

        // Initialize LifecycleManager immediately to capture current activity
        if (this.lifecycleManager == null) {
            this.lifecycleManager = LifecycleManager(appContext, context as? Activity)
        }

        this.connectivityManager =
            appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            // Unregister previous callback if exists
            networkCallback?.let {
                try {
                    connectivityManager?.unregisterNetworkCallback(it)
                } catch (e: Exception) {
                    // Ignore if not registered
                }
            }
            
            this.networkCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(
                    network: android.net.Network,
                    capabilities: NetworkCapabilities
                ) {
                    super.onCapabilitiesChanged(network, capabilities)
                    handleNetworkChange(capabilities, onStarted)
                }
            }

            val callback = this.networkCallback
            if (callback != null) {
                this.connectivityManager?.registerDefaultNetworkCallback(callback)
            }
        } else {
            // Unregister previous receiver if exists
            broadcastReceiver?.let {
                try {
                    appContext.unregisterReceiver(it)
                } catch (e: Exception) {
                    // Ignore if not registered
                }
            }
            
            // For API levels below 24, listen for connectivity changes using BroadcastReceiver
            val intentFilter = IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION)
            this.broadcastReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    val activeNetworkInfo = connectivityManager?.activeNetworkInfo
                    if (activeNetworkInfo != null && activeNetworkInfo.isConnected) {
                        // Call your method to handle connectivity change
                        startSession(onStarted)
                    }
                }
            }
            appContext.registerReceiver(this.broadcastReceiver, intentFilter)
        }
        checkForLateMessages()
    }

    private fun handleNetworkChange(capabilities: NetworkCapabilities, onStarted: () -> Unit) {
        when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) && !options.wifiOnly -> {
                startSession(onStarted)
            }
        }
    }

    fun startSession(onStarted: () -> Unit) {
        synchronized(sessionLock) {
            if (isSessionStarted) {
                if (options.debugLogs) {
                    DebugUtils.log("Session already started, skipping duplicate start")
                }
                return
            }
            
            val context = appContext
            if (context == null) {
                DebugUtils.error("App context is null, cannot start session")
                return
            }
            
            sessionStartTs = Date().time
            SessionRequest.create(context, false) { sessionResponse ->
                if (sessionResponse == null) {
                    DebugUtils.error("Openreplay: no response from /start request")
                    return@create
                }

                MessageCollector.start(context)

                with(options) {
                    if (screen) {
                        ScreenshotManager.setSettings(
                            settings = getCaptureSettings(
                                fps = 1,
                                quality = options.screenshotQuality
                            )
                        )
                        ScreenshotManager.start(context, sessionStartTs)
                    }
                    if (logs) LogsListener.start()
                    if (crashes) {
                        Crash.init(context)
                        Crash.start()
                    }
                    if (performances) PerformanceListener.getInstance(context).start()
                    if (analytics) Analytics.start()
                }
                
                isSessionStarted = true
                onStarted()
            }
        }
    }

    fun getLateMessagesFile(context: Context): File {
        return lateMessagesFile ?: File(context.cacheDir, "lateMessages.dat").also {
            lateMessagesFile = it
        }
    }

    fun coldStart(context: Context, projectKey: String, options: OROptions, onStarted: () -> Unit) {
        // Use application context to avoid leaks
        val appContext = context.applicationContext
        NetworkManager.initialize(appContext)
        this.appContext = appContext
        this.options = options
        this.projectKey = projectKey
        this.bufferingMode = true

        // Initialize LifecycleManager immediately to capture current activity
        if (this.lifecycleManager == null) {
            this.lifecycleManager = LifecycleManager(appContext, context as? Activity)
        }

        CoroutineScope(Dispatchers.IO).launch {
            UserDefaults.init(appContext)
        }

        val context = appContext
        if (context == null) {
            DebugUtils.error("App context is null, cannot start cold start")
            return
        }
        
        SessionRequest.create(context, false) { sessionResponse ->
            if (sessionResponse == null) {
                DebugUtils.error("Openreplay: no response from /start request")
                return@create
            }
            ConditionsManager.getConditions()
            MessageCollector.cycleBuffer()
            onStarted()

            with(this.options) {
                if (screen) {
                    ScreenshotManager.setSettings(
                        settings = getCaptureSettings(
                            fps = 1,
                            quality = OpenReplay.options.screenshotQuality
                        )
                    )
                    ScreenshotManager.start(context, sessionStartTs)
                }
                if (logs) LogsListener.start()
                if (crashes) {
                    Crash.init(context)
                    Crash.start()
                }
                if (performances) PerformanceListener.getInstance(context).start()
                if (analytics) Analytics.start()
            }
        }
    }

    fun triggerRecording(condition: String?) {
        this.bufferingMode = false
        if (options.debugLogs) {
            DebugUtils.log("Triggering recording with condition: $condition")
        }
        
        val context = appContext
        if (context == null) {
            DebugUtils.error("App context is null, cannot trigger recording")
            return
        }
        
        SessionRequest.create(context = context, doNotRecord = false) { sessionResponse ->
            if (sessionResponse != null) {
                MessageCollector.syncBuffers()
                MessageCollector.start(context)
            } else {
                DebugUtils.error("Openreplay: no response from /start request")
            }
        }
    }

    private fun checkForLateMessages() {
        val context = appContext ?: run {
            DebugUtils.log("appContext is null. Cannot check for late messages.")
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            val lateMessagesFile = File(context.filesDir, "lateMessages.dat")
            if (lateMessagesFile.exists()) {
                try {
                    val crashData = lateMessagesFile.readBytes()
                    NetworkManager.sendLateMessage(crashData) { success ->
                        if (success) {
                            CoroutineScope(Dispatchers.IO).launch {
                                lateMessagesFile.delete()
                            }
                        }
                    }
                } catch (e: Exception) {
                    DebugUtils.log("Error processing late messages: ${e.message}")
                }
            }
        }
    }

    fun stop(closeSession: Boolean = true) {
        synchronized(sessionLock) {
            ScreenshotManager.stop()
            Analytics.stop()
            LogsListener.stop()
            appContext?.let {
                PerformanceListener.getInstance(it).stop()
            }
            Crash.stop()
            MessageCollector.stop()
            
            // Clean up gesture listener
            gestureListener?.cleanup()
            gestureListener = null
            
            // Unregister network callbacks to prevent memory leaks
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                networkCallback?.let {
                    try {
                        connectivityManager?.unregisterNetworkCallback(it)
                    } catch (e: IllegalArgumentException) {
                        // Already unregistered
                        if (options.debugLogs) {
                            DebugUtils.log("Network callback already unregistered")
                        }
                    }
                }
                networkCallback = null
            } else {
                broadcastReceiver?.let {
                    try {
                        appContext?.unregisterReceiver(it)
                    } catch (e: IllegalArgumentException) {
                        // Receiver was already unregistered
                        if (options.debugLogs) {
                            DebugUtils.log("Broadcast receiver already unregistered")
                        }
                    }
                }
                broadcastReceiver = null
            }
            
            // Unregister lifecycle callbacks
            lifecycleManager?.unregister()
            lifecycleManager = null
            
            // Clear gesture detector references
            gestureDetector = null
            
            if (closeSession) {
                SessionRequest.clear()
            }
            
            connectivityManager = null
            
            // Reset session state
            isSessionStarted = false
        }
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
        sendNetworkMessage(url, method, requestJSON, responseJSON, status, duration)
    }

    fun sendMessage(type: String, msg: Any) {
        MessageHandler.sendMessage(type, msg)
    }

    fun eventStr(name: String, jsonPayload: String) {
        val message = ORMobileEvent(name, payload = jsonPayload)
        MessageCollector.sendMessage(message)
    }

    /**
     * Setup gesture detector for a specific activity.
     * This method should be called from LifecycleManager when an activity is resumed.
     * Using WeakReference pattern through LifecycleManager prevents memory leaks.
     * 
     * The gesture detector is triggered via the activity's dispatchTouchEvent() method,
     * ensuring ALL touch events are captured without interference with normal touch handling.
     */
    fun setupGestureDetectorForActivity(activity: Activity) {
        val rootView = activity.window.decorView.rootView
        val listener = ORGestureListener(rootView)
        this.gestureListener = listener
        this.gestureDetector = GestureDetector(activity, listener)
        
        if (options.debugLogs) {
            DebugUtils.log("Gesture detector setup for activity: ${activity.localClassName}")
        }
    }

    /**
     * Process touch events through the gesture detector.
     * This should be called from the activity's dispatchTouchEvent() method.
     * 
     * @param event The MotionEvent to process
     * @return true if the gesture detector processed the event, false otherwise
     */
    fun onTouchEvent(event: MotionEvent): Boolean {
        val handled = this.gestureDetector?.onTouchEvent(event) ?: false
        
        // Optional debug logging for touch events
        if (options.debugLogs && event.action == MotionEvent.ACTION_DOWN) {
            DebugUtils.log("Touch event captured at (${event.x}, ${event.y})")
        }
        
        return handled
    }

    fun getSessionID(): String {
        return SessionRequest.getSessionId() ?: ""
    }

    /**
     * Get the current active activity.
     * Returns null if no activity is currently active.
     */
    fun getCurrentActivity(): Activity? {
        return lifecycleManager?.currentActivity
    }

    /**
     * Check if gesture tracking is properly initialized.
     * Useful for debugging gesture tracking issues.
     */
    fun isGestureTrackingEnabled(): Boolean {
        val isEnabled = gestureDetector != null && options.analytics
        if (options.debugLogs) {
            DebugUtils.log("Gesture tracking enabled: $isEnabled (detector: ${gestureDetector != null}, analytics: ${options.analytics})")
        }
        return isEnabled
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
