package com.openreplay.tracker.managers

import android.content.Context
import com.openreplay.tracker.OpenReplay
import com.openreplay.tracker.OpenReplay.getLateMessagesFile
import com.openreplay.tracker.models.ORMessage
import com.openreplay.tracker.models.script.ORMobileBatchMeta
import com.openreplay.tracker.models.script.ORMobileGraphQL
import com.openreplay.tracker.models.script.ORMobileNetworkCall
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

object MessageCollector {
    private var messagesWaiting = mutableListOf<ByteArray>()
    private val messagesWaitingBackup = mutableListOf<ByteArray>()
    private var nextMessageIndex = 0
    private var sendingLastMessages = false
    private val maxMessagesSize = 500_000
    private var lateMessagesFile: File? = null
    private var tick = 0
    private var sendIntervalFuture: ScheduledFuture<*>? = null
    private var executorService = Executors.newScheduledThreadPool(2)
    private var debouncedMessage: ORMessage? = null
    private var debounceJob: ScheduledFuture<*>? = null
    private var bufferJob: ScheduledFuture<*>? = null
    
    @Volatile
    private var isPaused = false
    @Volatile
    private var isStarted = false

    private fun startCycleBuffer() {
        bufferJob?.cancel(false)
        bufferJob = executorService.scheduleWithFixedDelay({
            cycleBuffer()
        }, 30, 30, TimeUnit.SECONDS)
    }


    fun start(context: Context) {
        if (isStarted && !isPaused) {
            DebugUtils.log("MessageCollector already started")
            return
        }
        
        if (isPaused) {
            resume()
            return
        }
        
        isStarted = true
        isPaused = false
        
        CoroutineScope(Dispatchers.IO).launch {
            // Get the lateMessagesFile in a background thread
            val lateMessagesFile = getLateMessagesFile(context)

            // Check if the file exists
            if (lateMessagesFile.exists()) {
                try {
                    // Read the late messages data
                    val lateData = lateMessagesFile.readBytes()

                    // Send late messages
                    NetworkManager.sendLateMessage(lateData) { success ->
                        if (success) {
                            // Delete the file on successful sending
                            lateMessagesFile.delete()
                        }
                    }
                } catch (e: Exception) {
                    DebugUtils.log("Error processing late messages: ${e.message}")
                }
            }

            // Schedule flush messages at regular intervals
            sendIntervalFuture = executorService.scheduleWithFixedDelay({
                executorService.execute {
                    flushMessages()
                }
            }, 0, 5, TimeUnit.SECONDS)
        }
    }

    fun pause() {
        if (!isStarted || isPaused) {
            DebugUtils.log("MessageCollector not started or already paused")
            return
        }
        
        isPaused = true
        
        // Pause scheduled tasks but don't shutdown executor
        sendIntervalFuture?.cancel(false)
        bufferJob?.cancel(false)
        debounceJob?.cancel(false)
        
        // Flush remaining messages before pausing
        executorService.execute {
            flushMessages()
        }
        
        DebugUtils.log("MessageCollector paused")
    }
    
    fun resume() {
        if (!isStarted || !isPaused) {
            DebugUtils.log("MessageCollector not paused or not started")
            return
        }
        
        isPaused = false
        
        // Restart scheduled tasks
        sendIntervalFuture = executorService.scheduleWithFixedDelay({
            executorService.execute {
                flushMessages()
            }
        }, 0, 5, TimeUnit.SECONDS)
        
        if (OpenReplay.bufferingMode) {
            startCycleBuffer()
        }
        
        DebugUtils.log("MessageCollector resumed")
    }

    fun stop() {
        if (!isStarted) {
            return
        }
        
        sendIntervalFuture?.cancel(true)
        bufferJob?.cancel(true)
        debounceJob?.cancel(true)
        
        terminate()
        
        // Shutdown and recreate executor for future use
        executorService.shutdown()
        try {
            if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
                executorService.shutdownNow()
            }
        } catch (e: InterruptedException) {
            executorService.shutdownNow()
            Thread.currentThread().interrupt()
        }
        
        // Recreate executor service for potential restart
        executorService = Executors.newScheduledThreadPool(2)
        
        isStarted = false
        isPaused = false
        
        DebugUtils.log("MessageCollector stopped")
    }

    private fun flushMessages() {
        if (NetworkManager.sessionId == null && !sendingLastMessages) {
            DebugUtils.log("Session not initialized yet, skipping flush")
            return
        }
        
        val messages = mutableListOf<ByteArray>()
        var sentSize = 0
        while (messagesWaiting.isNotEmpty() && sentSize + messagesWaiting.first().size <= maxMessagesSize) {
            messagesWaiting.firstOrNull()?.let { message ->
                messages.add(message)
                messagesWaiting.removeAt(0)
                sentSize += message.size
            }
        }

        if (messages.isEmpty()) return

        val content = ByteArrayOutputStream()
        val index = ORMobileBatchMeta(nextMessageIndex.toULong())
        content.write(index.contentData())
        messages.forEach { message ->
            content.write(message)
        }

        if (sendingLastMessages) {
            lateMessagesFile?.let { file ->
                if (file.exists()) {
                    try {
                        file.writeBytes(content.toByteArray())
                    } catch (e: IOException) {
                        DebugUtils.error("Error writing late messages: ${e.message}")
                    }
                }
            }
        }

        nextMessageIndex += messages.size
        DebugUtils.log("messages batch ${content.toByteArray().size}")

        NetworkManager.sendMessage(content.toByteArray()) { success ->
            if (!success) {
                DebugUtils.log("<><>re-sending failed batch<><>")
                messagesWaiting.addAll(0, messages)
            } else if (sendingLastMessages) {
                sendingLastMessages = false
                lateMessagesFile?.let { file ->
                    if (file.exists()) {
                        try {
                            file.delete()
                        } catch (e: Exception) {
                            DebugUtils.error("Error deleting late messages file: ${e.message}")
                        }
                    }
                }
            }
        }
    }

    fun sendMessage(message: ORMessage) {
        if (isPaused) {
            DebugUtils.log("MessageCollector is paused, message dropped")
            return
        }
        
        // if (!message.isValid()) {
        //     DebugUtils.error("Attempted to send invalid message with type 0 or null: ${message::class.simpleName} (messageRaw=${message.messageRaw}, messageType=${message.message})")
        //     return
        // }
        
        if (OpenReplay.bufferingMode) {
            ConditionsManager.processMessage(message)?.let { trigger ->
                OpenReplay.triggerRecording(trigger)
            }
        }
        
        if (!message.toString().contains("Log") && !message.toString().contains("NetworkCall")) {
            DebugUtils.log(message.toString())
        }
        (message as? ORMobileNetworkCall)?.let { networkCallMessage ->
            DebugUtils.log("-->> MobileNetworkCall(105): ${networkCallMessage.method} ${networkCallMessage.URL}")
        }

        sendRawMessage(data = message.contentData())
    }

    fun syncBuffers() {
        executorService.execute {
            val buf1 = messagesWaiting.size
            val buf2 = messagesWaitingBackup.size
            tick = 0
            bufferJob?.cancel(false)
            bufferJob = null

            synchronized(messagesWaiting) {
                synchronized(messagesWaitingBackup) {
                    if (buf1 > buf2) {
                        messagesWaitingBackup.clear()
                    } else {
                        messagesWaiting = ArrayList(messagesWaitingBackup)
                        messagesWaitingBackup.clear()
                    }
                }
            }

            flushMessages()
        }
    }

    private fun sendRawMessage(data: ByteArray) {
        executorService.execute {
            if (data.size > maxMessagesSize) {
                DebugUtils.log("<><><>Single message size exceeded limit")
                return@execute
            }
            synchronized(messagesWaiting) {
                messagesWaiting.add(data)
            }
            if (OpenReplay.bufferingMode) {
                synchronized(messagesWaitingBackup) {
                    messagesWaitingBackup.add(data)
                }
            }
            var totalWaitingSize = 0
            synchronized(messagesWaiting) {
                messagesWaiting.forEach { totalWaitingSize += it.size }
            }
            if (!OpenReplay.bufferingMode && totalWaitingSize > (maxMessagesSize * 0.8).toInt()) {
                flushMessages()
            }
        }
    }

    fun sendDebouncedMessage(message: ORMessage) {
        // if (!message.isValid()) {
        //     DebugUtils.error("Attempted to debounce invalid message with type 0 or null: ${message::class.simpleName} (messageRaw=${message.messageRaw}, messageType=${message.message})")
        //     return
        // }
        
        debounceJob?.cancel(false)

        debouncedMessage = message

        debounceJob = executorService.schedule({
            debouncedMessage?.let {
                sendMessage(it)
                debouncedMessage = null
            }
        }, 0, TimeUnit.SECONDS)
    }

    fun cycleBuffer() {
        if (OpenReplay.sessionStartTs == 0L) {
            OpenReplay.sessionStartTs = System.currentTimeMillis()
        }

        if (OpenReplay.bufferingMode) {
            synchronized(messagesWaiting) {
                synchronized(messagesWaitingBackup) {
                    if (tick % 2 == 0) {
                        messagesWaiting.clear()
                    } else {
                        messagesWaitingBackup.clear()
                    }
                    tick += 1
                }
            }
        }
    }

    private fun terminate() {
        if (sendingLastMessages) return
        executorService.execute {
            sendingLastMessages = true
            flushMessages()
        }
    }
}