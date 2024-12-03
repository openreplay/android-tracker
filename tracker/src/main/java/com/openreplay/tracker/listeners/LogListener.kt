package com.openreplay.tracker.listeners

import com.openreplay.tracker.managers.DebugUtils
import com.openreplay.tracker.managers.MessageCollector
import com.openreplay.tracker.models.script.ORMobileLog
import java.io.IOException
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.io.PrintStream
import java.util.concurrent.BlockingQueue
import java.util.concurrent.LinkedBlockingQueue
import kotlin.concurrent.thread

object LogsListener {
    private val outputListener = Listener(System.out, "info")
    private val errorListener = Listener(System.err, "error")

    fun start() {
        outputListener.start()
        errorListener.start()
    }

    fun stop() {
        outputListener.stop()
        errorListener.stop()
    }

    class Listener(
        private val originalStream: PrintStream,
        private val severity: String
    ) {
        private val inputPipe = PipedInputStream()
        private val outputPipe = PipedOutputStream()
        private val logQueue: BlockingQueue<String> = LinkedBlockingQueue()
        @Volatile
        private var isRunning = false

        init {
            try {
                inputPipe.connect(outputPipe)
            } catch (e: IOException) {
                DebugUtils.log("Error connecting pipes: ${e.message}")
            }
        }

        fun start() {
            isRunning = true
            val printStream = PrintStream(outputPipe, true)
            if (severity == "info") {
                System.setOut(printStream)
            } else {
                System.setErr(printStream)
            }

            // Thread to read and queue logs
            thread(name = "LogReaderThread-$severity") {
                val buffer = ByteArray(1024)
                try {
                    while (isRunning) {
                        if (inputPipe.available() > 0) {
                            val bytesRead = inputPipe.read(buffer)
                            if (bytesRead > 0) {
                                val data = String(buffer, 0, bytesRead, Charsets.UTF_8)
                                logQueue.put(data) // Enqueue logs
                            }
                        }
                    }
                } catch (e: IOException) {
                    if (isRunning) {
                        DebugUtils.log("Error reading logs: ${e.message}")
                    }
                }
            }

            // Thread to process and send logs
            thread(name = "LogProcessorThread-$severity") {
                try {
                    while (isRunning) {
                        val log = logQueue.take() // Dequeue logs
                        val message = ORMobileLog(severity = severity, content = log)
                        MessageCollector.sendMessage(message)
                        originalStream.println(log) // Forward to original stream
                    }
                } catch (e: InterruptedException) {
                    DebugUtils.log("Log processing interrupted: ${e.message}")
                }
            }
        }

        fun stop() {
            isRunning = false
            if (severity == "info") {
                System.setOut(originalStream)
            } else {
                System.setErr(originalStream)
            }
            try {
                inputPipe.close()
                outputPipe.close()
            } catch (e: IOException) {
                DebugUtils.log("Error closing pipes: ${e.message}")
            }
        }
    }
}
