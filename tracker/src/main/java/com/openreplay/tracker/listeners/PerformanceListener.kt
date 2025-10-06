package com.openreplay.tracker.listeners

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
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
    private val cpuHandler = Handler(Looper.getMainLooper())
    private val memoryHandler = Handler(Looper.getMainLooper())
    private var lifecycleOwnerRef: WeakReference<LifecycleOwner>? = null
    
    @Volatile
    private var isActive: Boolean = false
    private val startLock = Any()
    
    private var cpuRunnable: Runnable? = null
    private var memoryRunnable: Runnable? = null

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
        scheduleCpuUsageTask()
        scheduleMemoryUsageTask()
        isActive = true
    }

    private fun registerBatteryLevelReceiver() {
        // Unregister previous receiver if exists
        unregisterBatteryLevelReceiver()
        
        batteryLevelReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                if (scale > 0) {
                    val batteryPct = (level * 100 / scale.toFloat()).toInt()
                    if (OpenReplay.options.debugLogs) {
                        DebugUtils.log("Battery level: $batteryPct%")
                    }
                    MessageCollector.sendMessage(
                        ORMobilePerformanceEvent(name = "batteryLevel", value = batteryPct.toULong())
                    )
                }
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

    @Synchronized
    fun stop() {
        if (!isActive) {
            if (OpenReplay.options.debugLogs) {
                DebugUtils.log("PerformanceListener already stopped")
            }
            return
        }
        
        // Remove all callbacks first
        cpuRunnable?.let { cpuHandler.removeCallbacks(it) }
        memoryRunnable?.let { memoryHandler.removeCallbacks(it) }
        cpuHandler.removeCallbacksAndMessages(null)
        memoryHandler.removeCallbacksAndMessages(null)
        
        // Unregister battery receiver
        unregisterBatteryLevelReceiver()
        
        // Clear runnable references
        cpuRunnable = null
        memoryRunnable = null
        
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
}
