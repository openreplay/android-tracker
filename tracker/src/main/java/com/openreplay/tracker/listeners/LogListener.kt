package com.openreplay.tracker.listeners

import com.openreplay.tracker.managers.MessageCollector
import com.openreplay.tracker.models.script.ORMobileLog
import java.io.*
import kotlin.concurrent.thread

object LogsListener {
    private val outputListener = Listener(System.out, "info")
    private val errorListener = Listener(System.err, "error")

    fun start() {
        outputListener.start()
        errorListener.start()
    }

    class Listener(
        private val originalStream: PrintStream,
        private val severity: String
    ) {
        private val inputPipe = PipedInputStream()
        private val outputPipe = PipedOutputStream()

        init {
            try {
                inputPipe.connect(outputPipe)
            } catch (e: IOException) {
                e.printStackTrace()
            }

            thread {
                try {
                    val buffer = ByteArray(1024)
                    while (true) {
                        val bytesRead = inputPipe.read(buffer)
                        if (bytesRead != -1) {
                            val data = String(buffer, 0, bytesRead, Charsets.UTF_8)
                            val message = ORMobileLog(severity = severity, content = data)
                            MessageCollector.sendMessage(message)
                            originalStream.write(buffer, 0, bytesRead)
                        }
                    }
                } catch (e: IOException) {
                    e.printStackTrace()
                }
            }
        }

        fun start() {
            val printStream = PrintStream(outputPipe, true)
            if (severity == "info") {
                System.setOut(printStream)
            } else {
                System.setErr(printStream)
            }
        }

        fun stop() {
            if (severity == "info") {
                System.setOut(originalStream)
            } else {
                System.setErr(originalStream)
            }
            try {
                inputPipe.close()
                outputPipe.close()
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }
    }
}
