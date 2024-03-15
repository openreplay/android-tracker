package com.openreplay

import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

object OpenReplay {
    var options: Options = Options()

    data class Options(
        val debugLogs: Boolean = true,
        val pkgVersion: String = "1.0.10",
        val projectKey: String = "UILXBAOleQKLJssbgeSw",
        val bufferingMode: Boolean = false,
        var sessionStartTs: Long = 0,
        // Add other options as needed
    )

    fun shared(): OpenReplay {
        return this
    }
}
