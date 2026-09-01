package com.openreplay.tracker.managers

import java.util.concurrent.atomic.AtomicInteger

/**
 * Backoff policy for screenshot-archive uploads.
 *
 * Deliberately free of Android APIs so the ratchet/reset rules can be unit tested —
 * this is the piece that wedged uploads after the app came back from background.
 */
internal class ImageUploadBackoff(
    private val baseMs: Long = 1_000L,
    private val capMs: Long = 30_000L,
) {
    private val consecutiveFailures = AtomicInteger(0)

    @Volatile
    private var backoffUntilMs = 0L

    fun waitMsFrom(nowMs: Long): Long = (backoffUntilMs - nowMs).coerceAtLeast(0L)

    fun failureCount(): Int = consecutiveFailures.get()

    fun onSuccess() = reset()

    /**
     * Only failures observed while the transport was usable say anything about server
     * health. Offline failures (app backgrounded, radio down) must not ratchet the
     * delay, or the app returns to the foreground already throttled to one upload
     * per [capMs]. Returns the delay now in effect, in ms.
     */
    fun onFailure(nowMs: Long, networkAvailable: Boolean): Long {
        if (!networkAvailable) return waitMsFrom(nowMs)
        val n = consecutiveFailures.incrementAndGet()
        val shift = (n - 1).coerceIn(0, 5)
        val backoff = (baseMs shl shift).coerceAtMost(capMs)
        backoffUntilMs = nowMs + backoff
        return backoff
    }

    fun reset() {
        consecutiveFailures.set(0)
        backoffUntilMs = 0L
    }
}
