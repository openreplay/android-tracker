package com.openreplay.tracker.models

enum class RecordingQuality {
    Low, Standard, High
}

class OROptions(
    val crashes: Boolean = true,
    val analytics: Boolean = true,
    val performances: Boolean = true,
    val logs: Boolean = true,
    val screen: Boolean = true,
    val wifiOnly: Boolean = true,
    val debugLogs: Boolean = false,
    val debugImages: Boolean = false,
    val fps: Int = 1,
    val screenshotQuality: RecordingQuality = RecordingQuality.Low,
    val pkgVersion: String = "1.0.10"
) {
    companion object {
        val defaults = OROptions()
    }

    fun merge(newOptions: OROptions): OROptions {
        return OROptions(
            crashes = newOptions.crashes,
            analytics = newOptions.analytics,
            performances = newOptions.performances,
            logs = newOptions.logs,
            screen = newOptions.screen,
            wifiOnly = newOptions.wifiOnly,
            debugLogs = newOptions.debugLogs,
            debugImages = newOptions.debugImages,
            fps = newOptions.fps,
            screenshotQuality = newOptions.screenshotQuality,
            pkgVersion = newOptions.pkgVersion
        )
    }
}
