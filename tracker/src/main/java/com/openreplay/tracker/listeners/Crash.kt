package com.openreplay.tracker.listeners

import android.content.Context
import com.openreplay.tracker.managers.DebugUtils
import com.openreplay.tracker.models.script.ORMobileCrash
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.lang.ref.WeakReference
import com.openreplay.tracker.managers.NetworkManager

object Crash {
    private var crashFile: File? = null
    @Volatile
    private var isActive = false
    private var contextRef: WeakReference<Context>? = null
    private var previousHandler: Thread.UncaughtExceptionHandler? = null
    private val crashLock = Any()

    fun init(context: Context) {
        synchronized(crashLock) {
            contextRef = WeakReference(context.applicationContext)

            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val cacheDir = context.cacheDir
                    val file = File(cacheDir, "ASCrash.dat")
                    crashFile = file

                    if (file.exists()) {
                        val crashData = file.readBytes()
                        NetworkManager.sendLateMessage(crashData) { success ->
                            if (success) {
                                deleteFile(file)
                            } else {
                                DebugUtils.error("Failed to send late crash data")
                            }
                        }
                    }
                } catch (e: Exception) {
                    DebugUtils.error("Error in Crash.init: ${e.message}")
                }
            }
        }
    }

    fun start() {
        synchronized(crashLock) {
            if (isActive) {
                DebugUtils.log("Crash handler already active")
                return
            }

            // Save previous handler to chain to it
            previousHandler = Thread.getDefaultUncaughtExceptionHandler()

            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                handleCrash(throwable)
                
                // Chain to previous handler or terminate app
                previousHandler?.uncaughtException(thread, throwable)
                    ?: kotlin.run {
                        // No previous handler, terminate the process
                        android.os.Process.killProcess(android.os.Process.myPid())
                        System.exit(10)
                    }
            }
            isActive = true
        }
    }

    private fun handleCrash(e: Throwable) {
        try {
            DebugUtils.error("Captured crash: ${e.javaClass.name} - ${e.message}")
            
            val message = ORMobileCrash(
                name = e.javaClass.name,
                reason = e.message ?: e.localizedMessage ?: "Unknown error",
                stacktrace = e.stackTraceToString()
            )
            val messageData = message.contentData()

            // Save to file synchronously (we're about to crash)
            val file = crashFile
            if (file != null) {
                try {
                    file.writeBytes(messageData)
                    DebugUtils.log("Crash data saved to file")
                } catch (ex: Exception) {
                    DebugUtils.error("Failed to save crash to file: ${ex.message}")
                }
            }

            // Attempt to send immediately (may not complete if app crashes)
            try {
                NetworkManager.sendMessage(messageData) { success ->
                    if (success && file != null) {
                        deleteFile(file)
                    }
                }
            } catch (ex: Exception) {
                DebugUtils.error("Failed to send crash data: ${ex.message}")
            }
        } catch (ex: Exception) {
            // Last resort - don't let crash handler crash
            System.err.println("OpenReplay crash handler failed: ${ex.message}")
        }
    }

    fun sendLateError(exception: Exception) {
        val message = ORMobileCrash(
            name = exception.javaClass.name,
            reason = exception.message ?: exception.localizedMessage ?: "Unknown error",
            stacktrace = exception.stackTraceToString()
        )

        CoroutineScope(Dispatchers.IO).launch {
            try {
                NetworkManager.sendLateMessage(message.contentData()) { success ->
                    if (success) {
                        crashFile?.let { deleteFile(it) }
                    }
                }
            } catch (e: Exception) {
                DebugUtils.error("Error sending late error: ${e.message}")
            }
        }
    }

    fun stop() {
        synchronized(crashLock) {
            if (!isActive) {
                return
            }
            
            // Restore previous handler
            Thread.setDefaultUncaughtExceptionHandler(previousHandler)
            previousHandler = null
            isActive = false
            
            // Clear context reference
            contextRef?.clear()
            contextRef = null
            
            DebugUtils.log("Crash handler stopped")
        }
    }

    private fun deleteFile(file: File) {
        try {
            if (file.exists()) {
                file.delete()
            }
        } catch (e: Exception) {
            DebugUtils.log("Error deleting file: ${e.message}")
        }
    }
}
