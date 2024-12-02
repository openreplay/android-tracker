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
    private var sessionId: String? = null

    fun create(context: Context, doNotRecord: Boolean, completion: (SessionResponse?) -> Unit) {
        val resolution = getDeviceResolution(context)
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
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val windowMetrics = windowManager.currentWindowMetrics
            val bounds = windowMetrics.bounds
            Pair(bounds.width(), bounds.height())
        } else {
            val metrics = DisplayMetrics()

            @Suppress("DEPRECATION")
            val display = windowManager.defaultDisplay
            @Suppress("DEPRECATION")
            display.getMetrics(metrics)
            Pair(metrics.widthPixels, metrics.heightPixels)
        }
    }

    fun isTablet(context: Context): Boolean {
        val configuration = context.resources.configuration
        return configuration.smallestScreenWidthDp >= 600
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
            sessionId = sessionResponse.sessionID
            DebugUtils.log(">>>> Starting session : $sessionId")
            completion(sessionResponse)
        }
    }

    fun getSessionId(): String? {
        return sessionId
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
