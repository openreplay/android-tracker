package com.openreplay

import android.view.View

object OpenReplay {
    var options: Options = Options()

    data class Options(
        val debugLogs: Boolean = true,
        val pkgVersion: String = "1.0.10",
        val projectKey: String = "34LtpOwyUI2ELFUNVkMn",
        val bufferingMode: Boolean = true,
        var sessionStartTs: Long = 0,
    )

    fun shared(): OpenReplay {
        return this
    }
}
