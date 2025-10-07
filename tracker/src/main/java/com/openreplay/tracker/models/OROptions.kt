package com.openreplay.tracker.models

enum class RecordingFrequency(val millis: Long) {
    Low(1000), Standard(500), High(300)
}

enum class RecordingQuality {
    Low, Standard, High
}

/**
 * Configuration options for OpenReplay tracker.
 *
 * @property crashes Enable/disable crash tracking (default: true)
 * @property analytics Enable/disable analytics tracking (clicks, swipes, etc.) (default: true)
 * @property performances Enable/disable performance monitoring (CPU, memory, battery) (default: true)
 * @property logs Enable/disable log capture (default: false)
 * @property screen Enable/disable screen recording (default: true)
 * @property wifiOnly Only track when connected to WiFi (default: true)
 * @property debugLogs Enable/disable debug logging (default: false)
 * @property debugImages Enable/disable debug image logging (default: false)
 * @property fps Frames per second for screen recording (default: 1, must be > 0)
 * @property screenshotFrequency Frequency of screenshot capture (default: Low)
 * @property screenshotQuality Quality of screenshot capture (default: Low)
 * @property pkgVersion Package version for tracking (default: "1.0.10")
 */
data class OROptions(
    val crashes: Boolean = true,
    val analytics: Boolean = true,
    val performances: Boolean = true,
    val logs: Boolean = false,
    val screen: Boolean = true,
    val wifiOnly: Boolean = true,
    val debugLogs: Boolean = false,
    val debugImages: Boolean = false,
    val fps: Int = 1,
    val screenshotFrequency: RecordingFrequency = RecordingFrequency.Low,
    val screenshotQuality: RecordingQuality = RecordingQuality.Low,
    val pkgVersion: String = "1.0.10"
) {
    init {
        require(fps > 0) { "fps must be positive, got: $fps" }
        require(pkgVersion.isNotBlank()) { "pkgVersion cannot be blank" }
    }

    /**
     * Check if any tracking is enabled
     */
    fun isTrackingEnabled(): Boolean {
        return crashes || analytics || performances || logs || screen
    }

    /**
     * Check if network-dependent features are enabled
     */
    fun requiresNetwork(): Boolean {
        return isTrackingEnabled()
    }

    /**
     * Get effective screenshot interval in milliseconds
     */
    fun getScreenshotIntervalMs(): Long {
        return screenshotFrequency.millis / fps
    }

    companion object {
        val defaults = OROptions()
        
        private const val DEFAULT_PKG_VERSION = "1.0.10"
        
        fun builder(): Builder = Builder()
    }

    class Builder {
        private var crashes: Boolean = true
        private var analytics: Boolean = true
        private var performances: Boolean = true
        private var logs: Boolean = false
        private var screen: Boolean = true
        private var wifiOnly: Boolean = true
        private var debugLogs: Boolean = false
        private var debugImages: Boolean = false
        private var fps: Int = 1
        private var screenshotFrequency: RecordingFrequency = RecordingFrequency.Low
        private var screenshotQuality: RecordingQuality = RecordingQuality.Low
        private var pkgVersion: String = DEFAULT_PKG_VERSION

        fun crashes(value: Boolean) = apply { this.crashes = value }
        fun analytics(value: Boolean) = apply { this.analytics = value }
        fun performances(value: Boolean) = apply { this.performances = value }
        fun logs(value: Boolean) = apply { this.logs = value }
        fun screen(value: Boolean) = apply { this.screen = value }
        fun wifiOnly(value: Boolean) = apply { this.wifiOnly = value }
        fun debugLogs(value: Boolean) = apply { this.debugLogs = value }
        fun debugImages(value: Boolean) = apply { this.debugImages = value }
        fun fps(value: Int) = apply { this.fps = value }
        fun screenshotFrequency(value: RecordingFrequency) = apply { this.screenshotFrequency = value }
        fun screenshotQuality(value: RecordingQuality) = apply { this.screenshotQuality = value }
        fun pkgVersion(value: String) = apply { this.pkgVersion = value }

        fun build() = OROptions(
            crashes = crashes,
            analytics = analytics,
            performances = performances,
            logs = logs,
            screen = screen,
            wifiOnly = wifiOnly,
            debugLogs = debugLogs,
            debugImages = debugImages,
            fps = fps,
            screenshotFrequency = screenshotFrequency,
            screenshotQuality = screenshotQuality,
            pkgVersion = pkgVersion
        )
    }
}
