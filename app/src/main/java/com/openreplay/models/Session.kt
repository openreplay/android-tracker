package com.openreplay.models

import android.os.Build
import android.os.SystemClock
import com.openreplay.OpenReplay
import java.util.Date
import java.io.Serializable
import android.os.Handler
import android.os.Looper
import com.openreplay.managers.DebugUtils
import com.openreplay.managers.UserDefaults
import kotlin.math.abs

object SessionRequest {
    private var params = mutableMapOf<String, Any>()

    fun create(doNotRecord: Boolean, completion: (SessionResponse?) -> Unit) {
        val projectKey = OpenReplay.options.projectKey

        val performances = mapOf(
            "physicalMemory" to Runtime.getRuntime().maxMemory(),
            "processorCount" to Runtime.getRuntime().availableProcessors().toLong(),
            "systemUptime" to SystemClock.uptimeMillis(),
            "isLowPowerModeEnabled" to 1L,
            "batteryState" to 1L,
            "orientation" to 0L
        )

        val deviceName = Build.MODEL ?: "Unknown"
        val deviceModel = Build.DEVICE ?: "Unknown"

        params = mutableMapOf(
            "doNotRecord" to doNotRecord,
            "projectKey" to projectKey,
            "trackerVersion" to OpenReplay.options.pkgVersion, // Assuming OpenReplay.options.pkgVersion is not needed or is the same
            "revID" to "N/A",
            "userUUID" to UserDefaults.userUUID, // Replace with dynamic retrieval
            "userOSVersion" to Build.VERSION.RELEASE,
            "userDevice" to deviceModel,
            "userDeviceType" to deviceName,
            "timestamp" to Date().time,
            "performances" to performances,
            "deviceMemory" to Runtime.getRuntime().maxMemory() / 1024,
            "timezone" to getTimezone()
        )
        callAPI(completion)
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
    val quality: String
) : Serializable

fun getTimezone(): String {
    val offset = java.util.TimeZone.getDefault().getOffset(Date().time) / 1000
    val sign = if (offset >= 0) "+" else "-"
    val hours = abs(offset) / 3600
    val minutes = (abs(offset) % 3600) / 60
    return String.format("UTC%s%02d:%02d", sign, hours, minutes)
}
