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
    private var fileUrl: File? = null
    private var isActive = false
    private var contextRef: WeakReference<Context>? = null

    fun init(context: Context) {
        contextRef = WeakReference(context.applicationContext) // Use application context

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val cacheDir = context.cacheDir // Access cacheDir on background thread
                fileUrl = File(cacheDir, "ASCrash.dat")

                if (fileUrl!!.exists()) {
                    val crashData = fileUrl!!.readBytes()
                    NetworkManager.sendLateMessage(crashData) { success ->
                        if (success) {
                            fileUrl!!.delete()
                        }
                    }
                }
            } catch (e: Exception) {
                DebugUtils.log("Error in Crash.init: ${e.message}")
            }
        }
    }

    fun start() {
        Thread.setDefaultUncaughtExceptionHandler { _, e ->
            DebugUtils.log("Captured crash: ${e.localizedMessage}")
            val message = ORMobileCrash(
                name = e.javaClass.name,
                reason = e.localizedMessage ?: "",
                stacktrace = e.stackTrace.joinToString(separator = "\n") { it.toString() }
            )
            val messageData = message.contentData()

            CoroutineScope(Dispatchers.IO).launch {
                try {
                    fileUrl?.writeBytes(messageData)
                    NetworkManager.sendMessage(messageData) { success ->
                        if (success) {
                            deleteFile(fileUrl!!)
                        }
                    }
                } catch (ex: Exception) {
                    DebugUtils.log("Error saving or sending crash data: ${ex.message}")
                }
            }
        }
        isActive = true
    }

    fun sendLateError(exception: Exception) {
        val message = ORMobileCrash(
            name = exception.javaClass.name,
            reason = exception.localizedMessage ?: "",
            stacktrace = exception.stackTrace.joinToString(separator = "\n") { it.toString() }
        )

        CoroutineScope(Dispatchers.IO).launch {
            try {
                NetworkManager.sendLateMessage(message.contentData()) { success ->
                    if (success) {
                        deleteFile(fileUrl!!)
                    }
                }
            } catch (e: Exception) {
                DebugUtils.log("Error sending late error: ${e.message}")
            }
        }
    }

    fun stop() {
        if (isActive) {
            Thread.setDefaultUncaughtExceptionHandler(null)
            isActive = false
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
