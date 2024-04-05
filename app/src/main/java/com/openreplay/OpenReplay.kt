package com.openreplay

object OpenReplay {
    var options: Options = Options()

    data class Options(
        val debugLogs: Boolean = true,
        val pkgVersion: String = "1.0.10",
        val projectKey: String = "34LtpOwyUI2ELFUNVkMn",
        val bufferingMode: Boolean = true,
        var sessionStartTs: Long = 0,
        var screenshotQuality: Int = 10,
        var fps: Int = 3
    )

    fun shared(): OpenReplay {
        return this
    }
}
