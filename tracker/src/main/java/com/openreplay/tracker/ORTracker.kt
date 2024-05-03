package com.openreplay.tracker

import NetworkManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.view.View
import com.google.gson.Gson
import com.openreplay.tracker.listeners.Analytics
import com.openreplay.tracker.listeners.Crash
import com.openreplay.tracker.listeners.LogsListener
import com.openreplay.tracker.listeners.NetworkListener
import com.openreplay.tracker.listeners.PerformanceListener
import com.openreplay.tracker.listeners.sendNetworkMessage
import com.openreplay.tracker.managers.*
import com.openreplay.tracker.models.OROptions
import com.openreplay.tracker.models.SessionRequest
import java.util.*
import kotlin.math.max
import kotlin.math.min
import com.openreplay.tracker.models.RecordingQuality
import com.openreplay.tracker.models.script.ORMobileEvent
import com.openreplay.tracker.models.script.ORMobileMetadata
import com.openreplay.tracker.models.script.ORMobileUserID
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

        val connectivityManager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

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
        Analytics.stop()
        MessageCollector.stop()
        PerformanceListener.getInstance(appContext!!).stop()
        ScreenshotManager.stopCapturing()
        Crash.stop()
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
}

fun getCaptureSettings(fps: Int, quality: RecordingQuality): Pair<Int, Int> {
    val limitedFPS = min(max(fps, 1), 99)
    val captureRate = 1000 / limitedFPS // Milliseconds per frame

    val imgCompression = when (quality) {
        RecordingQuality.Low -> 10
        RecordingQuality.Standard -> 30
        RecordingQuality.High -> 60
    }

    return Pair(captureRate, imgCompression)
}
