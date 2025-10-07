package com.openreplay.tracker.listeners

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.Choreographer
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.openreplay.tracker.OpenReplay
import com.openreplay.tracker.managers.DebugUtils
import com.openreplay.tracker.managers.MessageCollector
import com.openreplay.tracker.models.script.ORMobilePerformanceEvent
import java.lang.ref.WeakReference
import kotlin.math.round

class PerformanceListener private constructor(applicationContext: Context) :
    DefaultLifecycleObserver {

    companion object {
        @Volatile
        private var instance: PerformanceListener? = null

        fun getInstance(context: Context): PerformanceListener =
            instance ?: synchronized(this) {
                instance ?: PerformanceListener(context.applicationContext).also { instance = it }
            }
    }

    private val appContext = applicationContext
    private var batteryLevelReceiver: BroadcastReceiver? = null
    private var orientationReceiver: BroadcastReceiver? = null
    private val cpuHandler = Handler(Looper.getMainLooper())
    private val memoryHandler = Handler(Looper.getMainLooper())
    private val performanceHandler = Handler(Looper.getMainLooper())
    private var lifecycleOwnerRef: WeakReference<LifecycleOwner>? = null
    
    @Volatile
    private var isActive: Boolean = false
    private val startLock = Any()
    
    private var cpuRunnable: Runnable? = null
    private var memoryRunnable: Runnable? = null
    private var performanceRunnable: Runnable? = null
    
    private var lastOrientation: Int = -1
    private var frameCount = 0
    private var lastFrameTime = System.nanoTime()
    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            frameCount++
            if (isActive) {
                Choreographer.getInstance().postFrameCallback(this)
            }
        }
    }

    override fun onResume(owner: LifecycleOwner) {
        super.onResume(owner)
        lifecycleOwnerRef = WeakReference(owner)
        if (OpenReplay.options.debugLogs) {
            DebugUtils.log("Resume")
        }
        MessageCollector.sendMessage(ORMobilePerformanceEvent(name = "background", value = 0u))
        start()
    }

    override fun onPause(owner: LifecycleOwner) {
        super.onPause(owner)
        if (OpenReplay.options.debugLogs) {
            DebugUtils.log("Background")
        }
        MessageCollector.sendMessage(ORMobilePerformanceEvent(name = "background", value = 1u))
        stop()
    }

    @Synchronized
    fun start() {
        if (isActive) {
            if (OpenReplay.options.debugLogs) {
                DebugUtils.log("PerformanceListener already active, skipping start")
            }
            return
        }
        
        registerBatteryLevelReceiver()
        registerOrientationReceiver()
        scheduleCpuUsageTask()
        scheduleMemoryUsageTask()
        schedulePerformanceMetricsTask()
        startFpsTracking()
        reportSystemInfo()
        isActive = true
    }

    private fun registerBatteryLevelReceiver() {
        unregisterBatteryLevelReceiver()
        
        batteryLevelReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                
                if (scale > 0) {
                    val batteryPct = (level * 100 / scale.toFloat()).toInt()
                    if (OpenReplay.options.debugLogs) {
                        DebugUtils.log("Battery level: $batteryPct%")
                    }
                    MessageCollector.sendMessage(
                        ORMobilePerformanceEvent(name = "batteryLevel", value = batteryPct.toULong())
                    )
                }
                
                val batteryState = when (status) {
                    BatteryManager.BATTERY_STATUS_CHARGING -> 2u
                    BatteryManager.BATTERY_STATUS_FULL -> 3u
                    BatteryManager.BATTERY_STATUS_DISCHARGING, BatteryManager.BATTERY_STATUS_NOT_CHARGING -> 1u
                    else -> 0u
                }
                MessageCollector.sendMessage(
                    ORMobilePerformanceEvent(name = "batteryState", value = batteryState.toULong())
                )
            }
        }
        
        try {
            appContext.registerReceiver(
                batteryLevelReceiver,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            )
        } catch (e: Exception) {
            DebugUtils.error("Error registering battery receiver: ${e.message}")
            batteryLevelReceiver = null
        }
    }

    private fun scheduleCpuUsageTask() {
        cpuRunnable = object : Runnable {
            override fun run() {
                reportCpuUsage()
                if (isActive) {
                    cpuHandler.postDelayed(this, 10000)
                }
            }
        }
        cpuRunnable?.let { cpuHandler.postDelayed(it, 10000) }
    }

    private fun scheduleMemoryUsageTask() {
        memoryRunnable = object : Runnable {
            override fun run() {
                reportMemoryUsage()
                if (isActive) {
                    memoryHandler.postDelayed(this, 10000)
                }
            }
        }
        memoryRunnable?.let { memoryHandler.postDelayed(it, 10000) }
    }

    private fun reportCpuUsage() {
        try {
            val cpuUsage = calculateCpuUsage()
            if (OpenReplay.options.debugLogs) {
                DebugUtils.log("Main thread responsiveness: ${cpuUsage}ms")
            }
            MessageCollector.sendMessage(
                ORMobilePerformanceEvent(name = "mainThreadCPU", value = cpuUsage.toULong())
            )
        } catch (e: Exception) {
            DebugUtils.error("Error reporting CPU usage: ${e.message}")
        }
    }

    private fun reportMemoryUsage() {
        try {
            val memoryUsage = memoryUsage()
            if (OpenReplay.options.debugLogs) {
                DebugUtils.log("Memory Usage: $memoryUsage MB")
            }
            MessageCollector.sendMessage(
                ORMobilePerformanceEvent(name = "memoryUsage", value = memoryUsage.toULong())
            )
        } catch (e: Exception) {
            DebugUtils.error("Error reporting memory usage: ${e.message}")
        }
    }

    /**
     * NOTE: This measures main thread responsiveness, not actual CPU usage.
     * Actual CPU usage would require reading /proc/stat which needs permissions.
     * This benchmark measures how long a standard computation takes, which indicates
     * if the main thread is busy/slow.
     */
    private fun calculateCpuUsage(): Double {
        val startTime = System.nanoTime()
        var result = 0.0
        for (i in 0 until 1000000) {
            result += (i * i) % 1234567
        }
        val endTime = System.nanoTime()
        val durationMs = (endTime - startTime) / 1_000_000.0
        return round(durationMs * 10) / 10.0
    }

    private fun memoryUsage(): Long {
        val activityManager =
            appContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager?.getMemoryInfo(memoryInfo)

        val runtime = Runtime.getRuntime()
        return (runtime.totalMemory() - runtime.freeMemory()) / 1048576L
    }

    private fun schedulePerformanceMetricsTask() {
        performanceRunnable = object : Runnable {
            override fun run() {
                reportPerformanceMetrics()
                if (isActive) {
                    performanceHandler.postDelayed(this, 30000)
                }
            }
        }
        performanceRunnable?.let { performanceHandler.postDelayed(it, 30000) }
    }

    private fun startFpsTracking() {
        frameCount = 0
        lastFrameTime = System.nanoTime()
        try {
            Choreographer.getInstance().postFrameCallback(frameCallback)
        } catch (e: Exception) {
            DebugUtils.error("Error starting FPS tracking: ${e.message}")
        }
    }

    private fun reportSystemInfo() {
        try {
            val runtime = Runtime.getRuntime()
            val physicalMemory = runtime.maxMemory()
            MessageCollector.sendMessage(
                ORMobilePerformanceEvent(name = "physicalMemory", value = physicalMemory.toULong())
            )

            val processorCount = runtime.availableProcessors()
            MessageCollector.sendMessage(
                ORMobilePerformanceEvent(name = "processorCount", value = processorCount.toULong())
            )
            MessageCollector.sendMessage(
                ORMobilePerformanceEvent(name = "activeProcessorCount", value = processorCount.toULong())
            )

            val orientation = getOrientation()
            lastOrientation = orientation
            MessageCollector.sendMessage(
                ORMobilePerformanceEvent(name = "orientation", value = orientation.toULong())
            )

            if (OpenReplay.options.debugLogs) {
                DebugUtils.log("Physical Memory: ${physicalMemory / 1048576L} MB, Processors: $processorCount, Orientation: $orientation")
            }
        } catch (e: Exception) {
            DebugUtils.error("Error reporting system info: ${e.message}")
        }
    }

    private fun reportPerformanceMetrics() {
        try {
            val uptime = SystemClock.elapsedRealtime() / 1000
            MessageCollector.sendMessage(
                ORMobilePerformanceEvent(name = "systemUptime", value = uptime.toULong())
            )

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
                val isLowPowerMode = powerManager?.isPowerSaveMode ?: false
                MessageCollector.sendMessage(
                    ORMobilePerformanceEvent(name = "isLowPowerModeEnabled", value = if (isLowPowerMode) 1u else 0u)
                )
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
                val thermalStatus = powerManager?.currentThermalStatus ?: 0
                val thermalState = when (thermalStatus) {
                    PowerManager.THERMAL_STATUS_NONE -> 0u
                    PowerManager.THERMAL_STATUS_LIGHT -> 1u
                    PowerManager.THERMAL_STATUS_MODERATE, PowerManager.THERMAL_STATUS_SEVERE -> 2u
                    PowerManager.THERMAL_STATUS_CRITICAL, PowerManager.THERMAL_STATUS_EMERGENCY, PowerManager.THERMAL_STATUS_SHUTDOWN -> 3u
                    else -> 0u
                }
                MessageCollector.sendMessage(
                    ORMobilePerformanceEvent(name = "thermalState", value = thermalState.toULong())
                )
            }

            val currentTime = System.nanoTime()
            val elapsed = (currentTime - lastFrameTime) / 1_000_000_000.0
            if (elapsed > 0) {
                val fps = (frameCount / elapsed).toInt().coerceIn(0, 120)
                MessageCollector.sendMessage(
                    ORMobilePerformanceEvent(name = "fps", value = fps.toULong())
                )
                frameCount = 0
                lastFrameTime = currentTime

                if (OpenReplay.options.debugLogs) {
                    DebugUtils.log("FPS: $fps")
                }
            }

            if (OpenReplay.options.debugLogs) {
                DebugUtils.log("Performance metrics reported: uptime=${uptime}s")
            }
        } catch (e: Exception) {
            DebugUtils.error("Error reporting performance metrics: ${e.message}")
        }
    }

    private fun getOrientation(): Int {
        return try {
            when (appContext.resources.configuration.orientation) {
                Configuration.ORIENTATION_PORTRAIT -> 1
                Configuration.ORIENTATION_LANDSCAPE -> 3
                else -> 0
            }
        } catch (e: Exception) {
            DebugUtils.error("Error getting orientation: ${e.message}")
            0
        }
    }

    @Synchronized
    fun stop() {
        if (!isActive) {
            if (OpenReplay.options.debugLogs) {
                DebugUtils.log("PerformanceListener already stopped")
            }
            return
        }
        
        cpuRunnable?.let { cpuHandler.removeCallbacks(it) }
        memoryRunnable?.let { memoryHandler.removeCallbacks(it) }
        performanceRunnable?.let { performanceHandler.removeCallbacks(it) }
        cpuHandler.removeCallbacksAndMessages(null)
        memoryHandler.removeCallbacksAndMessages(null)
        performanceHandler.removeCallbacksAndMessages(null)
        
        try {
            Choreographer.getInstance().removeFrameCallback(frameCallback)
        } catch (e: Exception) {
            DebugUtils.error("Error stopping FPS tracking: ${e.message}")
        }
        
        unregisterBatteryLevelReceiver()
        unregisterOrientationReceiver()
        
        cpuRunnable = null
        memoryRunnable = null
        performanceRunnable = null
        
        isActive = false
        
        if (OpenReplay.options.debugLogs) {
            DebugUtils.log("PerformanceListener stopped successfully")
        }
    }

    private fun unregisterBatteryLevelReceiver() {
        batteryLevelReceiver?.let { receiver ->
            try {
                appContext.unregisterReceiver(receiver)
                if (OpenReplay.options.debugLogs) {
                    DebugUtils.log("Battery level receiver unregistered")
                }
            } catch (e: IllegalArgumentException) {
                if (OpenReplay.options.debugLogs) {
                    DebugUtils.log("Battery level receiver was not registered or already unregistered")
                }
            } catch (e: Exception) {
                DebugUtils.error("Error unregistering battery receiver: ${e.message}")
            } finally {
                batteryLevelReceiver = null
            }
        }
    }

    private fun registerOrientationReceiver() {
        unregisterOrientationReceiver()
        
        orientationReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val currentOrientation = getOrientation()
                if (currentOrientation != lastOrientation) {
                    lastOrientation = currentOrientation
                    MessageCollector.sendMessage(
                        ORMobilePerformanceEvent(name = "orientation", value = currentOrientation.toULong())
                    )
                    if (OpenReplay.options.debugLogs) {
                        DebugUtils.log("Orientation changed to: $currentOrientation")
                    }
                }
            }
        }
        
        try {
            appContext.registerReceiver(
                orientationReceiver,
                IntentFilter(Intent.ACTION_CONFIGURATION_CHANGED)
            )
        } catch (e: Exception) {
            DebugUtils.error("Error registering orientation receiver: ${e.message}")
            orientationReceiver = null
        }
    }

    private fun unregisterOrientationReceiver() {
        orientationReceiver?.let { receiver ->
            try {
                appContext.unregisterReceiver(receiver)
                if (OpenReplay.options.debugLogs) {
                    DebugUtils.log("Orientation receiver unregistered")
                }
            } catch (e: IllegalArgumentException) {
                if (OpenReplay.options.debugLogs) {
                    DebugUtils.log("Orientation receiver was not registered or already unregistered")
                }
            } catch (e: Exception) {
                DebugUtils.error("Error unregistering orientation receiver: ${e.message}")
            } finally {
                orientationReceiver = null
            }
        }
    }

    fun sendCustomPerformanceEvent(name: String, value: ULong) {
        try {
            MessageCollector.sendMessage(
                ORMobilePerformanceEvent(name = name, value = value)
            )
            if (OpenReplay.options.debugLogs) {
                DebugUtils.log("Custom performance event sent: $name = $value")
            }
        } catch (e: Exception) {
            DebugUtils.error("Error sending custom performance event: ${e.message}")
        }
    }
}
