package com.openreplay.tracker.managers

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Admits one screenshot capture at a time.
 *
 * Every capture holds a full-screen ARGB_8888 bitmap (13.9 MB at 1280x2772) from
 * [android.graphics.Bitmap.createBitmap] until its PixelCopy callback and the JPEG
 * compression finish. The capture loop fires once per interval whether or not the
 * previous capture has finished, so a capture that outlives the interval (a slow
 * PixelCopy, a window without a backing surface, a busy main thread) used to be joined
 * by the next one, and the next, until the heap held a dozen full-screen bitmaps.
 */
internal class CaptureGate {
    private val busy = AtomicBoolean(false)

    /** True when the caller may capture now; it must call [release] when done. */
    fun tryAcquire(): Boolean = busy.compareAndSet(false, true)

    fun release() {
        busy.set(false)
    }

    val isBusy: Boolean
        get() = busy.get()
}
