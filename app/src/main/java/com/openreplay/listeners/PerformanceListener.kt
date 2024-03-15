import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Handler
import androidx.lifecycle.*
import com.openreplay.managers.MessageCollector
import com.openreplay.models.script.ORIOSPerformanceEvent
import java.util.Timer
import java.util.TimerTask
import android.os.Process
import com.openreplay.OpenReplay
import com.openreplay.managers.DebugUtils
import java.io.File

class PerformanceListener private constructor(private val context: Context) : DefaultLifecycleObserver {
    companion object {
        var isActive: Boolean = false
        private var instance: PerformanceListener? = null

        fun getInstance(applicationContext: Context): PerformanceListener =
            instance ?: synchronized(this) {
                instance ?: PerformanceListener(applicationContext.applicationContext).also { instance = it }
            }

        fun networkStateChange(i: Int) {
            // Handle network state change
            println("Network state change: $i")
        }
    }

    private var batteryLevelReceiver: BroadcastReceiver? = null
    private var cpuTimer: Timer? = null
    private var memTimer: Timer? = null

    init {
        // Example of registering to lifecycle events if the context is an Activity or Fragment
        (context as? LifecycleOwner)?.lifecycle?.addObserver(this)
    }

    override fun onResume(owner: LifecycleOwner) {
        super.onResume(owner)

        if (OpenReplay.options.debugLogs) {
            DebugUtils.log("Resume")
        }

        MessageCollector.sendMessage(ORIOSPerformanceEvent(name = "background", value = 0u))

        start()
    }

    override fun onPause(owner: LifecycleOwner) {
        super.onPause(owner)
        if (OpenReplay.options.debugLogs) {
            DebugUtils.log("Background")
        }
        MessageCollector.sendMessage(ORIOSPerformanceEvent(name = "background", value = 1u))
        stop()
    }

    fun start() {
        if (!isActive) {
//            getCpuMessage()
            getMemoryMessage()

            registerBatteryLevelReceiver()
            setupTimers()
            isActive = true
        }
    }

    fun getCpuMessage() {
        val cpu = cpuUsage()
        if (cpu != null) {
            val message = ORIOSPerformanceEvent(name = "mainThreadCPU", value = cpu.toULong())
            MessageCollector.sendMessage(message)
        }
    }

    fun getMemoryMessage() {
        val memory = memoryUsage()
        val message = ORIOSPerformanceEvent(name = "memoryUsage", value = memory.toULong())
        MessageCollector.sendMessage(message)
    }

    private fun registerBatteryLevelReceiver() {
        batteryLevelReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                val batteryPct = level * 100 / scale.toFloat()
                // Handle battery level change
                println("Battery level: $batteryPct%")
            }
        }
        IntentFilter(Intent.ACTION_BATTERY_CHANGED).also { ifilter ->
            context.registerReceiver(batteryLevelReceiver, ifilter)
        }
    }

    private fun setupTimers() {
        cpuTimer = Timer().apply {
            schedule(object : TimerTask() {
                override fun run() {
//                    getCpuMessage()
                }
            }, 0, 5000) // Every 5 seconds
        }

        memTimer = Timer().apply {
            schedule(object : TimerTask() {
                override fun run() {
                    getMemoryMessage()
                }
            }, 0, 10000) // Every 10 seconds
        }
    }

    fun sendBattery() {
        val message = ORIOSPerformanceEvent(name = "Battery", value = 20u)
        MessageCollector.sendMessage(message)
    }

    fun sendThermalEvent() {
        val message = ORIOSPerformanceEvent(name = "Thermal", value = 2u)
        MessageCollector.sendMessage(message)
    }

    private fun getCpuInfo(): CpuInfo? {
        File("/proc/stat").useLines { lines ->
            val cpuLine = lines.firstOrNull { it.startsWith("cpu ") } ?: return null
            val parts = cpuLine.split("\\s+".toRegex()).drop(1).mapNotNull { it.toLongOrNull() }
            if (parts.size >= 8) {
                val total = parts.sum()
                return CpuInfo(total)
            }
            return null
        }
    }

    private fun getAppCpuTime(pid: Int): Long {
        File("/proc/$pid/stat").useLines { lines ->
            val statLine = lines.firstOrNull() ?: return 0
            val parts = statLine.split("\\s+".toRegex())
            if (parts.size >= 17) {
                val utime = parts[13].toLongOrNull() ?: 0
                val stime = parts[14].toLongOrNull() ?: 0
                return utime + stime
            }
            return 0
        }
    }

    data class CpuInfo(val total: Long)

    fun cpuUsage(): Double? {
        try {
            val pid = Process.myPid()
            val cpuInfo1 = getCpuInfo()
            val appCpuTime1 = getAppCpuTime(pid)

            Thread.sleep(1000) // Sleep for a second

            val cpuInfo2 = getCpuInfo()
            val appCpuTime2 = getAppCpuTime(pid)

            if (cpuInfo1 == null || cpuInfo2 == null) return null

            val totalCpuTimeDelta = cpuInfo2.total - cpuInfo1.total
            val appCpuTimeDelta = appCpuTime2 - appCpuTime1

            return if (totalCpuTimeDelta > 0) 100.0 * appCpuTimeDelta / totalCpuTimeDelta else null
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    fun memoryUsage(): Long {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager?.getMemoryInfo(memoryInfo)

        val runtime = Runtime.getRuntime()
        val usedMemInMB = (runtime.totalMemory() - runtime.freeMemory()) / 1048576L
        val maxHeapSizeInMB = runtime.maxMemory() / 1048576L
        val availHeapSizeInMB = maxHeapSizeInMB - usedMemInMB
        return usedMemInMB
    }

    fun stop() {
        if (isActive) {
            context.unregisterReceiver(batteryLevelReceiver)
            batteryLevelReceiver = null
            cpuTimer?.cancel()
            cpuTimer = null
            memTimer?.cancel()
            memTimer = null
            isActive = false
        }
    }
}
