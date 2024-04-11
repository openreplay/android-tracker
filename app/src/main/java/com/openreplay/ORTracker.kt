package com.openreplay

import PerformanceListener
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.view.View
import com.google.gson.Gson
import com.openreplay.listeners.Analytics
import com.openreplay.listeners.Crash
import com.openreplay.listeners.LogsListener
import com.openreplay.managers.ConditionsManager
import com.openreplay.models.OROptions
import com.openreplay.models.SessionRequest
import java.util.*
import kotlin.math.max
import kotlin.math.min
import com.openreplay.managers.MessageCollector
import com.openreplay.managers.ScreenshotManager
import com.openreplay.managers.UserDefaults
import com.openreplay.models.RecordingQuality
import com.openreplay.models.script.ORMobileEvent
import com.openreplay.models.script.ORMobileMetadata
import com.openreplay.models.script.ORMobileUserID
import java.io.File

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


    fun start(context: Context, projectKey: String, options: OROptions, onStarted: () -> Unit) {
        UserDefaults.init(context)
        this.appContext = context // Use application context to avoid leaks
        this.options = this.options.merge(options)
        this.projectKey = projectKey

        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        val networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: android.net.Network, capabilities: NetworkCapabilities) {
                super.onCapabilitiesChanged(network, capabilities)
                handleNetworkChange(capabilities, onStarted)
            }
        }

        connectivityManager.registerDefaultNetworkCallback(networkCallback)
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
        SessionRequest.create(appContext!!, false) { sessionResponse ->
            sessionResponse ?: return@create println("Openreplay: no response from /start request")

//            ConditionsManager.getConditions()
            MessageCollector.start()

            val captureSettings = getCaptureSettings(fps = 3, quality = this.options.screenshotQuality)
            ScreenshotManager.setSettings(settings = captureSettings)

            with(options) {
                if (logs) LogsListener.start()
                if (crashes) Crash.start()
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

    fun coldStart(context: Context, projectKey: String, options: OROptions) {
        this.appContext = context.applicationContext // Use application context to avoid leaks
        this.options = options
        this.projectKey = projectKey
        this.bufferingMode = true

        SessionRequest.create(appContext!!, false) { sessionResponse ->
            sessionResponse ?: return@create println("Openreplay: no response from /start request")
            ConditionsManager.getConditions()

            MessageCollector.cycleBuffer()

            with(this.options) {
                if (logs) LogsListener.start()
                if (crashes) Crash.start()
                if (performances) PerformanceListener.getInstance(appContext!!).start()
                if (screen) {
                    val captureSettings = getCaptureSettings(fps = 3, quality = options.screenshotQuality)
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
        SessionRequest.create(context = appContext!!, doNotRecord = false) { sessionResponse ->
            sessionResponse?.let {
                MessageCollector.syncBuffers()
                ScreenshotManager.syncBuffers()

//                val lateMessagesFile = File(context.filesDir, "lateMessages.dat")
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
        Analytics.stop()
        MessageCollector.stop()
        PerformanceListener.getInstance(appContext!!).stop()
        ScreenshotManager.stopCapturing()
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

    fun addIgnoredView(view: View) { // TODO - check to use this or sanitizeView
        ScreenshotManager.addSanitizedElement(view)
    }

    fun sanitizeView(view: View) {
        ScreenshotManager.addSanitizedElement(view)
    }

    fun event(name: String, `object`: Any?) {
        val gson = Gson()
        val jsonPayload = `object`?.let { gson.toJson(it) } ?: ""
        event(name, jsonPayload)
    }

    fun event(name: String, jsonPayload: String) {
        val message = ORMobileEvent(name, payload = jsonPayload)
        MessageCollector.sendMessage(message)
    }
}

//class ORTracker private constructor(private val context: Context) {
//    private var appContext: Context? = context
//    private var bufferingMode = true
//
//    companion object {
//        @Volatile
//        private var instance: ORTracker? = null
//
//        fun getInstance(context: Context): ORTracker {
//            return instance ?: synchronized(this) {
//                instance ?: ORTracker(context).also {
//                    instance = it
//                }
//            }
//        }
//    }
//
//    //    private val userDefaults: SharedPreferences =
////        context.getSharedPreferences("io.asayer.AsayerSDK-defaults", Context.MODE_PRIVATE)
//    private var projectKey: String? = null
//    private var sessionStartTs: Long = 0
//    var trackerState = CheckState.UNCHECKED
//    private var options: OROptions = OROptions.defaults
//
//    init {
//        // Android-specific network monitoring setup goes here
//        UserDefaults.init(context)
//    }
//
//
//    fun event(name: String, `object`: Any?) {
//        val gson = Gson()
//        val jsonPayload = `object`?.let { gson.toJson(it) } ?: ""
//        event(name, jsonPayload)
//    }
//
//    private fun event(name: String, jsonPayload: String) {
//        val message = ORMobileEvent(name, payload = jsonPayload)
//        MessageCollector.sendMessage(message)
//    }
//
//    fun start(projectKey: String, options: OROptions, onStarted: () -> Unit) {
//        this.options = options
//        this.projectKey = projectKey
//
//        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
//
//        val networkCallback = object : ConnectivityManager.NetworkCallback() {
//            override fun onCapabilitiesChanged(network: android.net.Network, capabilities: NetworkCapabilities) {
//                super.onCapabilitiesChanged(network, capabilities)
//
//                when {
//                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> {
//                        // Check and handle Wi-Fi connectivity
//                        trackerState = if (PerformanceListener.isActive) {
//                            PerformanceListener.networkStateChange(1)
//                            CheckState.CAN_START
//                        } else CheckState.CAN_START
//                    }
//
//                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> {
//                        // Check and handle Cellular connectivity
//                        if (options.wifiOnly) {
//                            trackerState = CheckState.CANT_START
//                            println("Connected to Cellular and options.wifiOnly is true. OpenReplay will not start.")
//                        } else {
//                            trackerState = CheckState.CAN_START
//                        }
//                    }
//
//                    else -> {
//                        trackerState = CheckState.CANT_START
//                        println("Not connected to either WiFi or Cellular. OpenReplay will not start.")
//                    }
//                }
//
//                // Handling tracker state
//                if (trackerState == CheckState.CAN_START) {
//                    startSession(options, onStarted)
//                }
//            }
//        }
//
//        connectivityManager.registerDefaultNetworkCallback(networkCallback)
//    }
//
//    private fun startSession(options: OROptions, onStarted: () -> Unit) {
//        SessionRequest.create(appContext!!, doNotRecord = false) { sessionResponse ->
//            sessionResponse ?: return@create println("OpenReplay: no response from /start request")
//            sessionStartTs = Date().time
//
//            onStarted()
//            val captureSettings = getCaptureSettings(
//                fps = 3,
//                quality = options.screenshotQuality
//            )
//
//            ConditionsManager.getConditions()
//
//            ScreenshotManager.setSettings(settings = captureSettings)
////            val lateMessagesFile = File(context.filesDir, "lateMessages.dat")
//            MessageCollector.start()
//
//            with(options) {
//                if (logs) LogsListener.start()
//                if (crashes) Crash.start()
//                if (performances) PerformanceListener.getInstance(context).start()
//                if (screen) ScreenshotManager.start(context = appContext!!, startTs = sessionStartTs)
//                if (analytics) Analytics.start()
//            }
//        }
//    }
//
//    fun stop() {
//        Analytics.stop()
//        MessageCollector.stop()
//        PerformanceListener.getInstance(context).stop()
//        ScreenshotManager.stopCapturing()
//    }
//
//    fun setUserID(userID: String) {
//        val message = ORMobileUserID(iD = userID)
//        MessageCollector.sendMessage(message)
//    }
//
//    fun setMetadata(key: String, value: String) {
//        val message = ORMobileMetadata(key = key, value = value)
//        MessageCollector.sendMessage(message)
//    }
//
//    fun userAnonymousID(iD: String) {
//        val message = ORMobileUserID(iD = iD)
//        MessageCollector.sendMessage(message)
//    }
//
//    fun addIgnoredView(view: View) { // TODO - check to use this or sanitizeView
//        ScreenshotManager.addSanitizedElement(view)
//    }
//
//    fun sanitizeView(view: View) {
//        ScreenshotManager.addSanitizedElement(view)
//    }
//}

fun getCaptureSettings(fps: Int, quality: RecordingQuality): Pair<Int, Int> {
    val limitedFPS = min(max(fps, 1), 99)
    val captureRate = 1000 / limitedFPS // Milliseconds per frame

    val imgCompression = when (quality) {
        RecordingQuality.Low -> 10
        RecordingQuality.Standard -> 30
        RecordingQuality.High -> 60
        else -> 30 // default to standard if quality string is not recognized
    }

    return Pair(captureRate, imgCompression)
}
