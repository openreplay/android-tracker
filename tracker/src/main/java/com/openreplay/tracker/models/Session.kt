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
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import com.openreplay.tracker.managers.NetworkManager

object SessionRequest {
    private val params = mutableMapOf<String, Any>()
    private val sessionId = AtomicReference<String?>()
    private val cachedSessionResponse = AtomicReference<SessionResponse?>()
    private val retryCount = AtomicInteger(0)
    private var retryHandler: Handler? = null
    
    private const val RETRY_DELAY_MS = 5000L
    private const val MAX_RETRIES = 5

    fun create(context: Context, activityContext: Context?, doNotRecord: Boolean, completion: (SessionResponse?) -> Unit) {
        cachedSessionResponse.get()?.let {
            if (OpenReplay.options.debugLogs) {
                DebugUtils.log("Returning cached session: ${it.sessionID}")
            }
            completion(it)
            return
        }

        val projectKey = OpenReplay.projectKey
        if (projectKey == null) {
            DebugUtils.error("ProjectKey is null, cannot create session")
            completion(null)
            return
        }

        try {
            initializeParams(context, activityContext, doNotRecord, projectKey)
            callAPI(completion)
        } catch (e: Exception) {
            DebugUtils.error("Error creating session", e)
            completion(null)
        }
    }

    fun clear() {
        sessionId.set(null)
        cachedSessionResponse.set(null)
        retryCount.set(0)
        synchronized(params) {
            params.clear()
        }
        retryHandler?.removeCallbacksAndMessages(null)
        retryHandler = null
    }

    private fun initializeParams(context: Context, activityContext: Context?, doNotRecord: Boolean, projectKey: String) {
        val resolution = getDeviceResolution(activityContext ?: context)
        val deviceModel = Build.DEVICE ?: "Unknown"
        val deviceType = if (isTablet(context)) "tablet" else "mobile"
        val timestamp = Date().time

        synchronized(params) {
            params.clear()
            params["platform"] = "android"
            params["width"] = resolution.first
            params["height"] = resolution.second
            params["doNotRecord"] = doNotRecord
            params["projectKey"] = projectKey
            params["trackerVersion"] = OpenReplay.options.pkgVersion
            params["revID"] = "N/A"
            params["userUUID"] = UserDefaults.userUUID
            params["userOSVersion"] = Build.VERSION.RELEASE
            params["userDevice"] = deviceModel
            params["userDeviceType"] = deviceType
            params["timestamp"] = timestamp
            params["deviceMemory"] = Runtime.getRuntime().maxMemory() / 1024
            params["timezone"] = getTimezone()
        }
    }

    private fun getDeviceResolution(context: Context): Pair<Int, Int> {
        return try {
            if (context !is android.app.Activity && context !is android.view.ContextThemeWrapper) {
                if (OpenReplay.options.debugLogs) {
                    DebugUtils.log("Non-visual context provided for resolution, using display metrics")
                }
                val metrics = context.resources.displayMetrics
                return Pair(metrics.widthPixels, metrics.heightPixels)
            }
            
            val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            if (windowManager == null) {
                DebugUtils.error("WindowManager is null, using default resolution")
                return Pair(1080, 1920)
            }
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bounds = windowManager.currentWindowMetrics.bounds
                Pair(bounds.width(), bounds.height())
            } else {
                val metrics = DisplayMetrics()
                @Suppress("DEPRECATION")
                windowManager.defaultDisplay.getMetrics(metrics)
                Pair(metrics.widthPixels, metrics.heightPixels)
            }
        } catch (e: Exception) {
            DebugUtils.error("Error getting device resolution", e)
            val metrics = context.resources.displayMetrics
            Pair(metrics.widthPixels, metrics.heightPixels)
        }
    }

    private fun isTablet(context: Context): Boolean {
        return context.resources.configuration.smallestScreenWidthDp >= 600
    }

    private fun callAPI(completion: (SessionResponse?) -> Unit) {
        val paramsCopy = synchronized(params) {
            if (params.isEmpty()) {
                DebugUtils.error("Params are empty, cannot call API")
                completion(null)
                return
            }
            params.toMap()
        }

        NetworkManager.createSession(paramsCopy) { sessionResponse ->
            when {
                sessionResponse != null -> {
                    sessionId.set(sessionResponse.sessionID)
                    cachedSessionResponse.set(sessionResponse)
                    retryCount.set(0)
                    
                    synchronized(params) {
                        params.clear()
                    }
                    
                    if (OpenReplay.options.debugLogs) {
                        DebugUtils.log("Session created: ${sessionResponse.sessionID}")
                    }
                    completion(sessionResponse)
                }
                retryCount.incrementAndGet() <= MAX_RETRIES -> {
                    val currentRetry = retryCount.get()
                    DebugUtils.warn("Session creation failed, retrying ($currentRetry/$MAX_RETRIES)...")
                    
                    if (retryHandler == null) {
                        retryHandler = Handler(Looper.getMainLooper())
                    }
                    
                    retryHandler?.postDelayed({
                        callAPI(completion)
                    }, RETRY_DELAY_MS)
                }
                else -> {
                    DebugUtils.error("Failed to create session after ${retryCount.get()} retries")
                    retryCount.set(0)
                    completion(null)
                }
            }
        }
    }

    fun getSessionId(): String? = sessionId.get()
    
    private fun getTimezone(): String {
        return try {
            val offset = java.util.TimeZone.getDefault().getOffset(Date().time) / 1000
            val sign = if (offset >= 0) "+" else "-"
            val hours = abs(offset) / 3600
            val minutes = (abs(offset) % 3600) / 60
            String.format("UTC%s%02d:%02d", sign, hours, minutes)
        } catch (e: Exception) {
            DebugUtils.error("Error getting timezone", e)
            "UTC+00:00"
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
