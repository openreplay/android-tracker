package com.openreplay.tracker.listeners

import android.content.Context
import com.openreplay.tracker.managers.DebugUtils
import com.openreplay.tracker.models.script.ORMobileCrash
import java.io.File
import java.lang.ref.WeakReference

object Crash {
    private var fileUrl: File? = null
    private var isActive = false
    private var contextRef: WeakReference<Context>? = null

    fun init(context: Context) {
        this.contextRef = WeakReference(context)
        fileUrl = context.cacheDir.resolve("ASCrash.dat").also { file ->
            if (file.exists()) {
                val crashData = file.readBytes()
                NetworkManager.sendLateMessage(crashData) { success ->
                    if (success && file.exists()) {
                        file.delete()
                    }
                }
            }
        }
    }

    fun start() {
        Thread.setDefaultUncaughtExceptionHandler { _, e ->
            DebugUtils.log("<><> captured crash ${e.localizedMessage}")
            val message = ORMobileCrash(
                name = e.javaClass.name,
                reason = e.localizedMessage ?: "",
                stacktrace = e.stackTrace.joinToString(separator = "\n") { it.toString() }
            )
            val messageData = message.contentData()
            fileUrl?.writeBytes(messageData)
            NetworkManager.sendMessage(messageData) { success ->
                if (success) {
                    fileUrl?.let { file ->
                        if (file.exists()) {
                            file.delete()
                        }
                    }
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
        NetworkManager.sendLateMessage(message.contentData()) { success ->
            if (success) {
                fileUrl?.let { file ->
                    if (file.exists()) {
                        file.delete()
                    }
                }
            }
        }
    }

    fun stop() {
        if (isActive) {
            Thread.setDefaultUncaughtExceptionHandler(null)
            isActive = false
        }
    }
}
