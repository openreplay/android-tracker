package com.openreplay.tracker

import com.openreplay.tracker.models.OROptions
import com.openreplay.tracker.models.RecordingQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class CaptureSettingsTest {

    // JPEG quality aligned with the iOS tracker (0.4 / 0.5 / 0.6) so a session
    // weighs roughly the same on both platforms.
    @Test
    fun `jpeg quality matches iOS per recording quality`() {
        assertEquals(40, getCaptureSettings(fps = 1, quality = RecordingQuality.Low).second)
        assertEquals(50, getCaptureSettings(fps = 1, quality = RecordingQuality.Standard).second)
        assertEquals(60, getCaptureSettings(fps = 1, quality = RecordingQuality.High).second)
    }

    @Test
    fun `resolution per recording quality is unchanged`() {
        assertEquals(480, getCaptureSettings(fps = 1, quality = RecordingQuality.Low).third)
        assertEquals(720, getCaptureSettings(fps = 1, quality = RecordingQuality.Standard).third)
        assertEquals(1080, getCaptureSettings(fps = 1, quality = RecordingQuality.High).third)
    }

    @Test
    fun `wifiOnly defaults to false so cellular sessions record out of the box`() {
        assertFalse(OROptions().wifiOnly)
        assertFalse(OROptions.defaults.wifiOnly)
        assertFalse(OROptions.builder().build().wifiOnly)
    }
}
