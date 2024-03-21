package com.openreplay.models

import java.io.Serializable

enum class RecordingQuality {
    Low, Standard, High
}

open class OROptions(
    val crashes: Boolean,
    val analytics: Boolean,
    val performances: Boolean,
    val logs: Boolean,
    val screen: Boolean,
    val wifiOnly: Boolean,
    val debugLogs: Boolean,
    val debugImages: Boolean
) {
    companion object {
        val defaults = OROptions(
            crashes = true,
            analytics = true,
            performances = true,
            logs = true,
            screen = true,
            wifiOnly = true,
            debugLogs = false,
            debugImages = false
        )
        val defaultDebug = OROptions(
            crashes = true,
            analytics = true,
            performances = true,
            logs = true,
            screen = true,
            wifiOnly = true,
            debugLogs = true,
            debugImages = false
        )
    }
}