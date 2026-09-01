package com.openreplay.tracker

import com.openreplay.tracker.managers.ImageUploadBackoff
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageUploadBackoffTest {

    @Test
    fun `backoff grows exponentially and is capped`() {
        val backoff = ImageUploadBackoff()
        assertEquals(1_000L, backoff.onFailure(0L, networkAvailable = true))
        assertEquals(2_000L, backoff.onFailure(0L, networkAvailable = true))
        assertEquals(4_000L, backoff.onFailure(0L, networkAvailable = true))
        assertEquals(8_000L, backoff.onFailure(0L, networkAvailable = true))
        assertEquals(16_000L, backoff.onFailure(0L, networkAvailable = true))
        assertEquals(30_000L, backoff.onFailure(0L, networkAvailable = true))
        assertEquals(30_000L, backoff.onFailure(0L, networkAvailable = true))
    }

    @Test
    fun `success clears the delay`() {
        val backoff = ImageUploadBackoff()
        repeat(6) { backoff.onFailure(0L, networkAvailable = true) }
        backoff.onSuccess()
        assertEquals(0L, backoff.waitMsFrom(0L))
        assertEquals(0, backoff.failureCount())
    }

    // Regression: app backgrounded -> the queued archive uploads all fail because the
    // transport is gone. Those offline failures used to ratchet the shared backoff to
    // its 30s cap, so the app came back to the foreground throttled to ~2 uploads per
    // 30s while capture produced an archive every ~10s. The backlog then only grew.
    @Test
    fun `offline failures do not ratchet the backoff`() {
        val backoff = ImageUploadBackoff()
        repeat(16) { backoff.onFailure(nowMs = 0L, networkAvailable = false) }
        assertEquals(0, backoff.failureCount())
        assertEquals(0L, backoff.waitMsFrom(0L))
    }

    // Regression: whatever throttle was in force when the app went away must not
    // survive the trip to the foreground.
    @Test
    fun `reset on resume clears a capped backoff`() {
        val backoff = ImageUploadBackoff()
        repeat(6) { backoff.onFailure(nowMs = 0L, networkAvailable = true) }
        assertTrue(backoff.waitMsFrom(0L) > 0L)

        backoff.reset() // what OpenReplay.resume() now does

        assertEquals(0L, backoff.waitMsFrom(0L))
        assertEquals(1_000L, backoff.onFailure(0L, networkAvailable = true))
    }
}
