package com.openreplay.tracker.models

import android.content.Context
import android.os.*
import android.util.DisplayMetrics
import android.view.WindowManager
import com.openreplay.tracker.OpenReplay
import java.util.Date
import java.io.Serializable
import com.openreplay.tracker.managers.DebugUtils
import com.openreplay.tracker.managers.UserDefaults
import kotlin.math.abs

object SessionRequest {
    private var params = mutableMapOf<String, Any>()

    fun create(context: Context, doNotRecord: Boolean, completion: (SessionResponse?) -> Unit) {
//        val projectKey = OpenReplay.projectKey
//        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
//        val batteryLevel = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
//        val batteryStatus = batteryManager.getIntProperty(BatteryManager.BATTERY_STATUS_UNKNOWN)
        val resolution = getDeviceResolution(context)

//        val performances = mapOf(
//            "physicalMemory" to Runtime.getRuntime().maxMemory(),
//            "processorCount" to Runtime.getRuntime().availableProcessors().toLong(),
//            "systemUptime" to SystemClock.uptimeMillis(),
//            "isLowPowerModeEnabled" to 0L,
//            "batteryLevel" to batteryLevel,
//            "batteryState" to batteryStatus,
//            "orientation" to context.resources.configuration.orientation,
//        )

//        val deviceName = Build.MODEL ?: "Unknown"
        val deviceModel = Build.DEVICE ?: "Unknown"
        val deviceType = if (isTablet(context)) "tablet" else "mobile"

        params = mutableMapOf(
            "platform" to "android",
            "width" to resolution.first,
            "height" to resolution.second,
            "doNotRecord" to doNotRecord,
            "projectKey" to OpenReplay.projectKey!!,
            "trackerVersion" to OpenReplay.options.pkgVersion,
            "revID" to "N/A",
            "userUUID" to UserDefaults.userUUID,
            "userOSVersion" to Build.VERSION.RELEASE,
            "userDevice" to deviceModel,
            "userDeviceType" to deviceType,
            "timestamp" to Date().time,
            "deviceMemory" to Runtime.getRuntime().maxMemory() / 1024,
            "timezone" to getTimezone()
        )
        callAPI(completion)
    }

    private fun getDeviceResolution(context: Context): Pair<Int, Int> {
        val metrics = DisplayMetrics()
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // For Android R and above
            val windowMetrics = windowManager.currentWindowMetrics
            val bounds = windowMetrics.bounds
            val width = bounds.width()
            val height = bounds.height()
            return Pair(width, height)
        } else {
            // For older versions
            @Suppress("DEPRECATION")
            val display = windowManager.defaultDisplay
            @Suppress("DEPRECATION")
            display.getMetrics(metrics)
            val width = metrics.widthPixels
            val height = metrics.heightPixels
            return Pair(width, height)
        }
    }

//    fun isTablet(context: Context): Boolean {
//        val displayMetrics = context.resources.displayMetrics
//        val widthDp = displayMetrics.widthPixels / displayMetrics.density
//        val heightDp = displayMetrics.heightPixels / displayMetrics.density
//        val screenDiagonalDp = sqrt((widthDp * widthDp + heightDp * heightDp).toDouble()).toInt()
//        return screenDiagonalDp >= 600 // Threshold for considering a device as a tablet
//    }

    fun isTablet(context: Context): Boolean {
        val configuration = context.resources.configuration
        val smallestScreenWidthDp = configuration.smallestScreenWidthDp
        return smallestScreenWidthDp >= 600
    }


    private fun callAPI(completion: (SessionResponse?) -> Unit) {
        if (params.isEmpty()) return
        NetworkManager.createSession(params) { sessionResponse ->
            if (sessionResponse == null) {
                Handler(Looper.getMainLooper()).postDelayed({
                    callAPI(completion)
                }, 5000)
                return@createSession
            }
            DebugUtils.log(">>>> Starting session : ${sessionResponse.sessionID}")
            completion(sessionResponse)
        }
    }
}


data class SessionResponse(
    val userUUID: String,
    val token: String,
    val imagesHashList: List<String>?,
    val sessionID: String,
    val fps: Int,
    val quality: String,
    val projectID: String
) : Serializable

fun getTimezone(): String {
    val offset = java.util.TimeZone.getDefault().getOffset(Date().time) / 1000
    val sign = if (offset >= 0) "+" else "-"
    val hours = abs(offset) / 3600
    val minutes = (abs(offset) % 3600) / 60
    return String.format("UTC%s%02d:%02d", sign, hours, minutes)
}
