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
        private var readerThread: Thread? = null
        private var processorThread: Thread? = null

        init {
            try {
                inputPipe.connect(outputPipe)
            } catch (e: IOException) {
                DebugUtils.log("Error connecting pipes: ${e.message}")
            }
        }

        fun start() {
            if (isRunning) {
                DebugUtils.log("LogListener $severity already running")
                return
            }
            
            isRunning = true
            val printStream = PrintStream(outputPipe, true)
            if (severity == "info") {
                System.setOut(printStream)
            } else {
                System.setErr(printStream)
            }

            // Thread to read and queue logs
            readerThread = thread(name = "LogReaderThread-$severity") {
                val buffer = ByteArray(1024)
                try {
                    while (isRunning && !Thread.currentThread().isInterrupted) {
                        if (inputPipe.available() > 0) {
                            val bytesRead = inputPipe.read(buffer)
                            if (bytesRead > 0) {
                                val data = String(buffer, 0, bytesRead, Charsets.UTF_8)
                                logQueue.put(data) // Enqueue logs
                            }
                        } else {
                            // Small sleep to prevent busy waiting
                            Thread.sleep(10)
                        }
                    }
                } catch (e: InterruptedException) {
                    DebugUtils.log("Log reader interrupted: $severity")
                } catch (e: IOException) {
                    if (isRunning) {
                        DebugUtils.error("Error reading logs: ${e.message}")
                    }
                }
            }

            // Thread to process and send logs
            processorThread = thread(name = "LogProcessorThread-$severity") {
                try {
                    while (isRunning && !Thread.currentThread().isInterrupted) {
                        val log = logQueue.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS)
                        if (log != null) {
                            val message = ORMobileLog(severity = severity, content = log)
                            MessageCollector.sendMessage(message)
                            originalStream.print(log) // Forward to original stream (no extra newline)
                        }
                    }
                } catch (e: InterruptedException) {
                    DebugUtils.log("Log processor interrupted: $severity")
                }
            }
        }

        fun stop() {
            if (!isRunning) {
                return
            }
            
            isRunning = false
            
            // Restore original streams first
            if (severity == "info") {
                System.setOut(originalStream)
            } else {
                System.setErr(originalStream)
            }
            
            // Interrupt and wait for threads to finish
            try {
                readerThread?.interrupt()
                processorThread?.interrupt()
                
                readerThread?.join(1000) // Wait up to 1 second
                processorThread?.join(1000)
                
                if (readerThread?.isAlive == true || processorThread?.isAlive == true) {
                    DebugUtils.log("LogListener threads did not terminate gracefully: $severity")
                }
            } catch (e: InterruptedException) {
                DebugUtils.error("Interrupted while stopping LogListener: ${e.message}")
            }
            
            // Process any remaining logs in queue
            try {
                var remainingLogs = 0
                while (logQueue.isNotEmpty() && remainingLogs < 100) {
                    val log = logQueue.poll()
                    if (log != null) {
                        originalStream.print(log)
                        remainingLogs++
                    }
                }
                if (remainingLogs > 0) {
                    DebugUtils.log("Flushed $remainingLogs remaining logs for $severity")
                }
            } catch (e: Exception) {
                DebugUtils.error("Error flushing remaining logs: ${e.message}")
            }
            
            // Close pipes
            try {
                outputPipe.flush()
                outputPipe.close()
                inputPipe.close()
            } catch (e: IOException) {
                DebugUtils.error("Error closing pipes: ${e.message}")
            }
            
            // Clear references
            readerThread = null
            processorThread = null
            logQueue.clear()
        }
    }
}
