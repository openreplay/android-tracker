package com.openreplay.tracker.managers

import android.util.Log
import com.openreplay.tracker.OpenReplay
import java.text.SimpleDateFormat
import java.util.*

object DebugUtils {
    private const val TAG = "OpenReplay"
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
    
    enum class LogLevel {
        ERROR, WARN, INFO, DEBUG
    }

    /**
     * Log an error message
     */
    fun error(str: String) {
        val timestamp = dateFormat.format(Date())
        val message = "[$timestamp] ERROR: $str"
        Log.e(TAG, message)
        // Always log errors, regardless of debug mode
        println("OpenReplay Error: $str")
    }

    /**
     * Log an error with throwable
     */
    fun error(e: Throwable) {
        val timestamp = dateFormat.format(Date())
        val message = "[$timestamp] ERROR: ${e.message}"
        Log.e(TAG, message, e)
        println("OpenReplay Error: ${e.message}")
        
        // Print stack trace in debug mode
        if (OpenReplay.options.debugLogs) {
            e.printStackTrace()
        }
    }

    /**
     * Log an error with custom message and throwable
     */
    fun error(message: String, e: Throwable) {
        val timestamp = dateFormat.format(Date())
        val fullMessage = "[$timestamp] ERROR: $message - ${e.message}"
        Log.e(TAG, fullMessage, e)
        println("OpenReplay Error: $message - ${e.message}")
        
        if (OpenReplay.options.debugLogs) {
            e.printStackTrace()
        }
    }

    /**
     * Log a warning message
     */
    fun warn(str: String) {
        if (OpenReplay.options.debugLogs) {
            val timestamp = dateFormat.format(Date())
            val message = "[$timestamp] WARN: $str"
            Log.w(TAG, message)
            println("OpenReplay Warning: $str")
        }
    }

    /**
     * Log an info/debug message
     */
    fun log(str: String) {
        if (OpenReplay.options.debugLogs) {
            val timestamp = dateFormat.format(Date())
            val message = "[$timestamp] INFO: $str"
            Log.d(TAG, message)
            println("OpenReplay: $str")
        }
    }

    /**
     * Log a debug message with specific level
     */
    fun log(level: LogLevel, str: String) {
        if (OpenReplay.options.debugLogs) {
            val timestamp = dateFormat.format(Date())
            val message = "[$timestamp] ${level.name}: $str"
            
            when (level) {
                LogLevel.ERROR -> {
                    Log.e(TAG, message)
                    println("OpenReplay Error: $str")
                }
                LogLevel.WARN -> {
                    Log.w(TAG, message)
                    println("OpenReplay Warning: $str")
                }
                LogLevel.INFO -> {
                    Log.i(TAG, message)
                    println("OpenReplay Info: $str")
                }
                LogLevel.DEBUG -> {
                    Log.d(TAG, message)
                    println("OpenReplay Debug: $str")
                }
            }
        }
    }

    /**
     * Log verbose information (only in debug mode)
     */
    fun verbose(str: String) {
        if (OpenReplay.options.debugLogs) {
            val timestamp = dateFormat.format(Date())
            val message = "[$timestamp] VERBOSE: $str"
            Log.v(TAG, message)
        }
    }
}
