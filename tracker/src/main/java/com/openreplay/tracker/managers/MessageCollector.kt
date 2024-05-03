package com.openreplay.tracker.managers

import android.os.Handler
import com.openreplay.tracker.OpenReplay
import com.openreplay.tracker.models.ORMessage
import com.openreplay.tracker.models.script.ORMobileBatchMeta
import com.openreplay.tracker.models.script.ORMobileNetworkCall
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.collections.ArrayList

data class BatchArch(
    var name: String,
    var data: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as BatchArch

        if (name != other.name) return false
        if (!data.contentEquals(other.data)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = name.hashCode()
        result = 31 * result + data.contentHashCode()
        return result
    }
}

object MessageCollector {
    private val imagesWaiting = mutableListOf<BatchArch>()
    private val imagesSending = mutableListOf<BatchArch>()
    private var messagesWaiting = mutableListOf<ByteArray>()
    private val messagesWaitingBackup = mutableListOf<ByteArray>()
    private var nextMessageIndex = 0
    private var sendingLastMessages = false
    private val maxMessagesSize = 500_000
    private var lateMessagesFile: File? = null
    private var sendInterval: Handler? = null
    private var bufferTimer: Handler? = null
    private var catchUpTimer: Handler? = null
    private var tick = 0
    private var sendIntervalFuture: ScheduledFuture<*>? = null
    private val executorService = Executors.newScheduledThreadPool(1)
    private var debounceTimer: Handler? = null
    private var debouncedMessage: ORMessage? = null
    private val bufferRunnable: Runnable = Runnable {
        cycleBuffer()
    }

//    init {
////        startCycleBuffer()
////        this.lateMessagesFile = File(context.cacheDir, "lateMessages.dat")
//    }

    private fun startCycleBuffer() {
        if (bufferTimer == null) bufferTimer = Handler()

        bufferTimer?.postDelayed(bufferRunnable, 30_000L) // Schedule for 30 seconds
    }


    fun start() {
        this.lateMessagesFile = OpenReplay.getLateMessagesFile()

        sendIntervalFuture = executorService.scheduleAtFixedRate({
            flush()
        }, 0, 5, TimeUnit.SECONDS)

        if (lateMessagesFile?.exists() == true) {
            val lateData = lateMessagesFile!!.readBytes()
            NetworkManager.sendLateMessage(lateData) { success ->
                if (success) {
                    lateMessagesFile!!.delete()
                }
            }
        }
    }

    private fun flush() {
        executorService.execute {
            flushMessages()
            flushImages()
        }
    }

    fun stop() {
        sendIntervalFuture?.cancel(true)
        sendInterval?.removeCallbacksAndMessages(null)
        bufferTimer?.removeCallbacksAndMessages(null)
        catchUpTimer?.removeCallbacksAndMessages(null)

        terminate()
    }

    private fun flushMessages() {
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

        if (sendingLastMessages && lateMessagesFile?.exists() == true) {
            try {
                lateMessagesFile?.writeBytes(content.toByteArray())
            } catch (e: IOException) {
                e.printStackTrace()
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
                if (lateMessagesFile?.exists() == true) {
                    lateMessagesFile!!.delete()
                }
            }
        }
    }

    private fun flushImages() {
        if (imagesWaiting.isNotEmpty()) {
            val images = imagesWaiting.removeAt(0)
            imagesSending.add(images)

            DebugUtils.log("Sending images ${images.name} ${images.data.size}")
            NetworkManager.sendImages(OpenReplay.projectKey!!, images.data, images.name) { success ->
                imagesSending.removeAll { it.name == images.name }
                if (!success) {
                    imagesWaiting.add(0, images) // Re-add to the start of the queue if not successful
                }
            }
        }
    }

    fun sendMessage(message: ORMessage) {
        if (OpenReplay.bufferingMode) {
            ConditionsManager.processMessage(message)?.let { trigger ->
                OpenReplay.triggerRecording(trigger)
            }
        }
        val data = message.contentData()
        if (OpenReplay.options.debugLogs) {
            if (!message.toString().contains("IOSLog") && !message.toString().contains("IOSNetworkCall")) {
                DebugUtils.log(message.toString())
            }
            (message as? ORMobileNetworkCall)?.let { networkCallMessage ->
                DebugUtils.log("-->> IOSNetworkCall(105): ${networkCallMessage.method} ${networkCallMessage.URL}")
            }
        }
        sendRawMessage(data)
    }

    fun syncBuffers() {
        val buf1 = messagesWaiting.size
        val buf2 = messagesWaitingBackup.size
        tick = 0
        bufferTimer?.removeCallbacksAndMessages(null)
        bufferTimer = null

        if (buf1 > buf2) {
            messagesWaitingBackup.clear()
        } else {
            messagesWaiting = ArrayList(messagesWaitingBackup)
            messagesWaitingBackup.clear()
        }

        flushMessages()
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
        // Cancel any existing callbacks
        debounceTimer?.removeCallbacksAndMessages(null)

        debouncedMessage = message
        // Initialize the handler if it hasn't been already
        if (debounceTimer == null) debounceTimer = Handler()

        debounceTimer?.postDelayed({
            debouncedMessage?.let {
                sendMessage(it)
                debouncedMessage = null
            }
        }, 2000) // 2.0 seconds delay
    }

    fun cycleBuffer() {
        OpenReplay.sessionStartTs = System.currentTimeMillis()

        if (OpenReplay.bufferingMode) {
            if (tick % 2 == 0) {
                messagesWaiting.clear()
            } else {
                messagesWaitingBackup.clear()
            }
            tick += 1
        }

        // Reschedule the runnable for the next cycle
        bufferTimer?.postDelayed(bufferRunnable, 30_000L)
    }

    private fun terminate() {
        if (sendingLastMessages) return

        executorService.execute {
            sendingLastMessages = true
            flushMessages()
        }
    }

    fun sendImagesBatch(batch: ByteArray, fileName: String) {
        imagesWaiting.add(BatchArch(name = fileName, data = batch))
        executorService.execute {
            flushImages()
        }
    }
}