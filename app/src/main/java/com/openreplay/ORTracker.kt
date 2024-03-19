package com.openreplay

import PerformanceListener
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.openreplay.listeners.Analytics
import com.openreplay.models.OROptions
import com.openreplay.models.SessionRequest
import java.util.*
import kotlin.math.max
import kotlin.math.min
import com.openreplay.managers.MessageCollector
import com.openreplay.managers.UserDefaults
import com.openreplay.models.script.ORMobileMetadata
import com.openreplay.models.script.ORMobileUserID
import java.io.File

enum class CheckState {
    UNCHECKED, CAN_START, CANT_START
}

class ORTracker private constructor(private val context: Context) {
    companion object {
        @Volatile
        private var instance: ORTracker? = null
        fun getInstance(context: Context): ORTracker =
            instance ?: synchronized(this) {
                instance ?: ORTracker(context.applicationContext).also { instance = it }
            }
    }

    //    private val userDefaults: SharedPreferences =
//        context.getSharedPreferences("io.asayer.AsayerSDK-defaults", Context.MODE_PRIVATE)
    private var projectKey: String? = null

    //    var pkgVersion = "1.1.10"
    private var sessionStartTs: Long = 0
    var trackerState = CheckState.UNCHECKED

    //    private var networkCheckTimer: Timer? = null
//    var bufferingMode = false
//    var serverURL: String
//        get() = NetworkManager.baseUrl
//        set(value) {
//            NetworkManager.baseUrl = value
//        }
    private var options: OROptions = OROptions.defaults
//    private var networkCheckHandler: Handler? = null
//    private var networkCheckRunnable: Runnable? = null

    init {
        // Android-specific network monitoring setup goes here
        UserDefaults.init(context)
    }

    fun start(projectKey: String, options: OROptions) {
        this.options = options
        this.projectKey = projectKey

        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        val networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: android.net.Network, capabilities: NetworkCapabilities) {
                super.onCapabilitiesChanged(network, capabilities)

                when {
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> {
                        // Check and handle Wi-Fi connectivity
                        trackerState = if (PerformanceListener.isActive) {
                            PerformanceListener.networkStateChange(1)
                            CheckState.CAN_START
                        } else CheckState.CAN_START
                    }

                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> {
                        // Check and handle Cellular connectivity
                        if (options.wifiOnly) {
                            trackerState = CheckState.CANT_START
                            println("Connected to Cellular and options.wifiOnly is true. OpenReplay will not start.")
                        } else {
                            trackerState = CheckState.CAN_START
                        }
                    }

                    else -> {
                        trackerState = CheckState.CANT_START
                        println("Not connected to either WiFi or Cellular. OpenReplay will not start.")
                    }
                }

                // Handling tracker state
                if (trackerState == CheckState.CAN_START) {
                    startSession(options)
                }
            }
        }

        connectivityManager.registerDefaultNetworkCallback(networkCallback)
    }

    private fun startSession(options: OROptions) {
        SessionRequest.create(doNotRecord = false) { sessionResponse ->
            sessionResponse ?: return@create println("OpenReplay: no response from /start request")
            sessionStartTs = Date().time
//            val captureSettings = getCaptureSettings(
//                fps = 3,
//                quality = "high"
//            )

//            ScreenshotManager.shared.setSettings(settings = captureSettings)
//            val messageCollector = MessageCollector(context)
//            messageCollector.start()
            val lateMessagesFile = File(context.filesDir, "lateMessages.dat")
            MessageCollector.start(lateMessagesFile)

            with(options) {
//                if (logs) LogsListener.shared.start()
//                if (crashes) Crashes.shared.start() // Assuming `Crashes` is a typo, and you meant `Crashes`
                if (performances) PerformanceListener.getInstance(context).start()
//                if (screen) ScreenshotManager.shared.start(startTs = sessionStartTs)
                if (analytics) Analytics.start()
            }
        }
    }

    fun coldStart(projectKey: String, options: OROptions) {
        // Implementation goes here
    }

    fun triggerRecording(condition: String?) {
        // Implementation goes here
    }

    fun stop() {
        Analytics.stop()
        MessageCollector.stop()
        PerformanceListener.getInstance(context).stop()
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

    // Additional methods adapted from the Swift class...
}

fun getCaptureSettings(fps: Int, quality: String): Pair<Double, Double> {
    val limitedFPS = min(max(fps, 1), 99)
    val captureRate = 1.0 / limitedFPS

    val imgCompression = when (quality.lowercase()) {
        "low" -> 0.4
        "standard" -> 0.5
        "high" -> 0.6
        else -> 0.5 // default to standard if quality string is not recognized
    }

    return Pair(captureRate, imgCompression)
}
