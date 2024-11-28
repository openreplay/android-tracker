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

    private val appContext = applicationContext.applicationContext
    private var batteryLevelReceiver: BroadcastReceiver? = null
    private val cpuHandler = Handler(Looper.getMainLooper())
    private val memoryHandler = Handler(Looper.getMainLooper())
    private var lifecycleOwnerRef: WeakReference<LifecycleOwner>? = null
    private var isActive: Boolean = false

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

    fun start() {
        if (!isActive) {
            registerBatteryLevelReceiver()
            scheduleCpuUsageTask()
            scheduleMemoryUsageTask()
            isActive = true
        }
    }

    private fun registerBatteryLevelReceiver() {
        if (batteryLevelReceiver == null) {
            batteryLevelReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                    val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                    val batteryPct = level * 100 / scale.toFloat()
                    println("Battery level: $batteryPct%")
                }
            }
            appContext.registerReceiver(
                batteryLevelReceiver,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            )
        }
    }

    private fun scheduleCpuUsageTask() {
        cpuHandler.postDelayed(object : Runnable {
            override fun run() {
                reportCpuUsage()
                cpuHandler.postDelayed(this, 10000)
            }
        }, 10000)
    }

    private fun scheduleMemoryUsageTask() {
        memoryHandler.postDelayed(object : Runnable {
            override fun run() {
                reportMemoryUsage()
                memoryHandler.postDelayed(this, 10000)
            }
        }, 10000)
    }

    private fun reportCpuUsage() {
        val cpuUsage = calculateCpuUsage()
        println("CPU Usage: $cpuUsage%")
        MessageCollector.sendMessage(
            ORMobilePerformanceEvent(name = "mainThreadCPU", value = cpuUsage.toULong())
        )
    }

    private fun reportMemoryUsage() {
        val memoryUsage = memoryUsage()
        println("Memory Usage: $memoryUsage MB")
        MessageCollector.sendMessage(
            ORMobilePerformanceEvent(name = "memoryUsage", value = memoryUsage.toULong())
        )
    }

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

    fun stop() {
        if (isActive) {
            unregisterBatteryLevelReceiver()
            cpuHandler.removeCallbacksAndMessages(null)
            memoryHandler.removeCallbacksAndMessages(null)
            isActive = false
        }
    }

    private fun unregisterBatteryLevelReceiver() {
        try {
            batteryLevelReceiver?.let {
                appContext.unregisterReceiver(it)
                batteryLevelReceiver = null
            }
        } catch (e: IllegalArgumentException) {
            println("Battery level receiver was not registered or already unregistered.")
        }
    }
}
