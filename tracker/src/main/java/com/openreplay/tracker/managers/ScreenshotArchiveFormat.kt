package com.openreplay.tracker.managers

import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * On-disk / on-the-wire naming and encoding for screenshot batches. Pure JVM so it can
 * be unit-tested; [ScreenshotManager] does the file and coroutine work around it.
 *
 * Two formats, selected by `framesSupport` from the `/v1/mobile/start` response:
 *  - **frames** (`framesSupport == true`): one length-prefixed record per screenshot,
 *    concatenated and gzipped. Sent with a `type=frames` form field. Named `<sid>-<lastTs>.gz`.
 *  - **tar** (`framesSupport == false`): a gzipped tar of `<firstTs>_1_<ts>.jpeg` entries.
 *    No `type` field, the server default. Named `<sid>-<lastTs>.tar.gz`.
 */
internal object ScreenshotArchiveFormat {
    private const val FRAMES_SUFFIX = ".gz"
    private const val TAR_SUFFIX = ".tar.gz"
    private const val TMP_SUFFIX = ".tmp"

    /** Writes `[ timestamp : uint64 LE ][ size : uint32 LE ][ jpeg bytes ]` to [out]. */
    fun writeFrameRecord(out: OutputStream, timestamp: Long, jpeg: ByteArray) {
        val header = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
        header.putLong(timestamp)
        header.putInt(jpeg.size)
        out.write(header.array())
        out.write(jpeg)
    }

    fun archiveName(sessionId: String, lastTs: String, frames: Boolean): String =
        "$sessionId-$lastTs" + if (frames) FRAMES_SUFFIX else TAR_SUFFIX

    fun tmpName(archiveName: String): String = archiveName + TMP_SUFFIX

    /** In-progress writes carry a `.tmp` suffix; only renamed-into-place files are finished. */
    fun isFinishedArchive(name: String): Boolean =
        !name.endsWith(TMP_SUFFIX) && (name.endsWith(TAR_SUFFIX) || name.endsWith(FRAMES_SUFFIX))

    /** `<sessionId>-<lastTs>.gz` / `.tar.gz` -> lastTs, for oldest-first ordering. */
    fun archiveTimestamp(name: String): Long =
        name.removeSuffix(TAR_SUFFIX).removeSuffix(FRAMES_SUFFIX)
            .substringAfterLast('-').toLongOrNull() ?: Long.MAX_VALUE
}
