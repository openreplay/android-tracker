package com.openreplay.tracker.managers

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Shader
import android.os.Build
import android.os.Handler
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.platform.ComposeView
import com.openreplay.tracker.OpenReplay
import com.openreplay.tracker.SanitizableViewGroup
import com.openreplay.tracker.listeners.PerformanceListener
import com.openreplay.tracker.models.script.ORMobilePerformanceEvent
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.suspendCancellableCoroutine

object ScreenshotManager {
    private var lastTs: String = ""
    private var firstTs: String = ""
    private val sanitizedElements: MutableList<WeakReference<View>> = mutableListOf()
    private var quality: Int = 10
    private var minResolution: Int = 320
    private lateinit var uiContext: WeakReference<Context>
    private var mainHandler: Handler? = null
    private var lastOrientation: Int = -1

    private var screenShotJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob()
            + Dispatchers.IO
            + CoroutineExceptionHandler { _, throwable ->
        DebugUtils.error(throwable)
    })
    private val archiveMutex = Mutex()

    // Names of archives currently queued/uploading, so overlapping send passes
    // don't re-dispatch (and re-stream) the same file before it's confirmed sent.
    // ConcurrentHashMap.newKeySet() is API 24+, but minSdk is 21 — use newSetFromMap.
    private val inFlightArchives: MutableSet<String> =
        Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    // The first saved frame of a session claims firstTs (the session start timestamp)
    // as its filename, so the replay has an image at t=0 instead of a blank gap
    // until the first post-/start capture lands.
    private val firstFrameCaptured = AtomicBoolean(false)

    // Cap on archives handed to the uploader at any one time. Matches its upload
    // concurrency, so a backlog drains steadily instead of piling into the upload queue:
    // a send pass runs every second, and under backoff each queued upload sleeps for up
    // to 30s, so an unbounded per-pass dispatch would queue the whole backlog anyway.
    private const val MAX_ARCHIVES_IN_FLIGHT = 2

    // "<sessionId>-<lastTs>.tar.gz" -> lastTs, for oldest-first ordering.
    private fun archiveTimestamp(name: String): Long =
        name.removeSuffix(".tar.gz").substringAfterLast('-').toLongOrNull() ?: Long.MAX_VALUE

    fun setSettings(settings: Triple<Int, Int, Int>) {
        val (_, quality, resolution) = settings
        this.quality = quality
        this.minResolution = resolution
    }

    fun hasFirstFrame(): Boolean = firstFrameCaptured.get()

    fun captureFirstFrame(context: Context, startTs: Long) {
        uiContext = WeakReference(context)
        firstTs = startTs.toString()
        firstFrameCaptured.set(false)
        scope.launch {
            // Drop leftovers from a previous run that never got archived — they
            // carry pre-session timestamps and would pollute this session's replay.
            runCatching { getScreenshotFolder().listFiles()?.forEach { it.delete() } }
            makeScreenshotAndSaveWithArchive()
        }
    }

    fun retryFirstFrameCapture(startTs: Long) {
        if (!::uiContext.isInitialized) return
        firstTs = startTs.toString()
        scope.launch { makeScreenshotAndSaveWithArchive() }
    }

    fun start(context: Context, startTs: Long) {
        uiContext = WeakReference(context)
        firstTs = startTs.toString()
        lastOrientation = -1
        val intervalMillis =
            OpenReplay.options.screenshotFrequency.millis / OpenReplay.options.fps.toLong()

        screenShotJob = scope.launch {
            checkAndReportOrientationChange()
            launch { makeScreenshotAndSaveWithArchive() }
            while (true) {
                delay(intervalMillis)
                launch { makeScreenshotAndSaveWithArchive() }
                launch { sendScreenshotArchives() }
            }
        }
    }


    fun stop() {
        screenShotJob?.cancel()
        terminate()
        synchronized(sanitizedElements) {
            sanitizedElements.clear()
        }
        mainHandler = null
        lastOrientation = -1
    }

    @Synchronized
    fun addSanitizedElement(view: View) {
        sanitizedElements.removeAll { it.get() == null }
        
        val viewInfo = "${view.javaClass.simpleName}(id=${view.id}, bounds=${view.width}x${view.height})"
        DebugUtils.log("Sanitizing view: $viewInfo - Total sanitized elements: ${sanitizedElements.size + 1}")
        sanitizedElements.add(WeakReference(view))
    }

    @Synchronized
    fun removeSanitizedElement(view: View) {
        DebugUtils.log("Removing sanitized view: $view")
        // Remove by matching the actual view and clean up null references
        sanitizedElements.removeAll { it.get() == view || it.get() == null }
    }

    private suspend fun sendScreenshotArchives() = withContext(Dispatchers.IO) {
        try {
            // Only finished archives: in-progress writes use a .tmp suffix and are
            // renamed into place atomically once complete (see archivateFolder).
            val archives = getArchiveFolder()
                .listFiles { f -> f.isFile && f.name.endsWith(".tar.gz") }
                .orEmpty()
            if (archives.isEmpty()) return@withContext

            val projectKey = OpenReplay.projectKey
            if (projectKey == null) {
                DebugUtils.error("Project key is null, cannot send screenshot archives")
                return@withContext
            }

            // Hand the uploader only as much as it can actually work on, oldest first.
            // Dispatching the whole backlog at once (which pause() used to do, right as
            // the network was going away) queues every archive behind the upload
            // semaphore, so the app returns to the foreground with a saturated queue and
            // new frames stuck behind minutes of stale, already-failing attempts.
            val freeSlots = MAX_ARCHIVES_IN_FLIGHT - inFlightArchives.size
            if (freeSlots <= 0) return@withContext

            val batch = archives
                .filterNot { it.name in inFlightArchives }
                .sortedBy { archiveTimestamp(it.name) }
                .take(freeSlots)

            batch.forEach { archive ->
                // Skip archives already queued/uploading from a previous interval —
                // re-dispatching them causes duplicate uploads and re-reads under backoff.
                if (!inFlightArchives.add(archive.name)) return@forEach

                NetworkManager.sendImages(
                    projectKey = projectKey,
                    images = archive,
                    name = archive.name
                ) { success ->
                    scope.launch {
                        if (success) {
                            archive.deleteSafely()
                        }
                        inFlightArchives.remove(archive.name)
                    }
                }
            }
        } catch (e: Exception) {
            DebugUtils.error("Error sending screenshot archives: ${e.message}")
        }
    }

    private suspend fun makeScreenshotAndSaveWithArchive(chunk: Int = 10) {
        coroutineScope {
            try {
                checkAndReportOrientationChange()
                val screenShotBitmap = withContext(Dispatchers.Main) { captureScreenshot() }
                val imageData = compress(screenShotBitmap)
                val screenShotFolder = getScreenshotFolder()
                // Claim the name only after capture+compress succeeded, so a failed
                // capture doesn't consume the session-start slot.
                val frameTs = if (firstFrameCaptured.compareAndSet(false, true)) {
                    firstTs
                } else {
                    System.currentTimeMillis().toString()
                }
                val screenShotFile = File(screenShotFolder, "$frameTs.jpeg")
                FileOutputStream(screenShotFile).use { out -> out.write(imageData) }
                archiveMutex.withLock {
                    if (screenShotFolder.listFiles().orEmpty().size >= chunk) {
                        archivateFolder(folder = screenShotFolder)
                    }
                }
            } catch (e: IllegalStateException) {
                DebugUtils.log("Screenshot skipped: ${e.message}")
            } catch (e: Exception) {
                DebugUtils.error("Screenshot error: ${e.message}")
            }
        }
    }

    private fun terminate() {
        scope.launch {
            try {
                val screenshotFolder = getScreenshotFolder()
                archiveMutex.withLock { archivateFolder(screenshotFolder) }
                sendScreenshotArchives()
            } catch (e: Exception) {
                DebugUtils.error("Error during termination: ${e.message}")
            }
        }
    }

    private fun checkAndReportOrientationChange() {
        try {
            val context = uiContext.get() ?: return
            val currentOrientation = PerformanceListener.getOrientation(context)
            
            if (currentOrientation != lastOrientation) {
                lastOrientation = currentOrientation
                MessageCollector.sendMessage(
                    ORMobilePerformanceEvent(name = "orientation", value = currentOrientation.toULong())
                )
                val orientationName = when (currentOrientation) {
                    1 -> "Portrait"
                    3 -> "Landscape"
                    else -> "Unknown"
                }
                DebugUtils.log("Orientation changed before screenshot: $orientationName ($currentOrientation)")
            }
        } catch (e: Exception) {
            DebugUtils.error("Error checking orientation: ${e.message}")
        }
    }


    private fun archivateFolder(folder: File) { 
        // Sort by the timestamp encoded in the filename, not lastModified: the
        // session-start frame is named with an earlier ts than its write time.
        val screenshots = folder.listFiles().orEmpty()
            .sortedBy { it.nameWithoutExtension.toLongOrNull() ?: Long.MAX_VALUE }
        
        if (screenshots.isEmpty()) {
            DebugUtils.log("No screenshots to archive")
            return
        }

        lastTs = screenshots.last().nameWithoutExtension
        val archiveFolder = getArchiveFolder()
        val sessionId = NetworkManager.sessionId ?: "unknown"
        val archiveFile = File(archiveFolder, "$sessionId-$lastTs.tar.gz")
        // Write to a temp file, then rename into place atomically. sendScreenshotArchives()
        // runs concurrently and streams whatever finished archives it finds; if it picked
        // this file up mid-write the streamed bytes wouldn't match the declared length.
        val tmpFile = File(archiveFolder, "$sessionId-$lastTs.tar.gz.tmp")

        // Stream each screenshot from disk straight into the gzip/tar output file;
        // never buffer the whole archive in memory.
        FileOutputStream(tmpFile).use { fos ->
            GzipCompressorOutputStream(fos).use { gzos ->
                TarArchiveOutputStream(gzos).use { tarOs ->
                    screenshots.forEach { jpeg ->
                        val filename = "${firstTs}_1_${jpeg.nameWithoutExtension}.jpeg"
                        val tarEntry = TarArchiveEntry(jpeg, filename)
                        tarOs.putArchiveEntry(tarEntry)
                        jpeg.inputStream().use { it.copyTo(tarOs) }
                        tarOs.closeArchiveEntry()
                    }
                }
            }
        }

        if (!tmpFile.renameTo(archiveFile)) {
            DebugUtils.error("Failed to finalize archive ${archiveFile.name}")
            tmpFile.deleteSafely()
            return
        }

        screenshots.forEach { it.deleteSafely() }
    }

    private fun getArchiveFolder(): File {
        val context = uiContext.get() ?: throw IllegalStateException("No context")
        return File(context.filesDir, "archives").apply { mkdirs() }
    }

    private fun getScreenshotFolder(): File {
        val context = uiContext.get() ?: throw IllegalStateException("No context")
        return File(context.filesDir, "screenshots").apply { mkdirs() }
    }

    private suspend fun captureScreenshot(): Bitmap {
        val activity = OpenReplay.getCurrentActivity()
        if (activity == null) {
            throw IllegalStateException("No Activity available for screenshot")
        }

        if (activity.isFinishing || activity.isDestroyed) {
            throw IllegalStateException("Activity is finishing or destroyed")
        }

        // suspendCancellableCoroutine, and screenShot() reports every outcome: a capture
        // that silently dropped its callback (PixelCopy on a window with no backing
        // surface, which happens on every return from background) used to suspend this
        // coroutine forever, uncancellably.
        return suspendCancellableCoroutine { coroutine ->
            try {
                activity.screenShot { outcome ->
                    if (!coroutine.isActive) {
                        outcome.getOrNull()?.recycle()
                        return@screenShot
                    }
                    coroutine.resumeWith(outcome)
                }
            } catch (e: Exception) {
                if (coroutine.isActive) coroutine.resumeWith(Result.failure(e))
            }
        }
    }

    private fun applyMaskToScreenshot(bitmap: Bitmap, rootView: View): Bitmap {
        synchronized(sanitizedElements) {
            sanitizedElements.removeAll { it.get() == null }
            
            if (sanitizedElements.isEmpty()) {
                return bitmap
            }
            
            val mutableBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
            val canvas = Canvas(mutableBitmap)
            
            val rootViewLocation = IntArray(2)
            rootView.getLocationInWindow(rootViewLocation)
            
            var maskedCount = 0
            sanitizedElements.forEach { weakRef ->
                val sanitizedView = weakRef.get()
                if (sanitizedView != null && sanitizedView.visibility == View.VISIBLE && sanitizedView.isAttachedToWindow) {
                    val location = IntArray(2)
                    sanitizedView.getLocationInWindow(location)
                    
                    val x = location[0] - rootViewLocation[0]
                    val y = location[1] - rootViewLocation[1]
                    
                    canvas.save()
                    canvas.translate(x.toFloat(), y.toFloat())
                    canvas.drawRect(
                        0f,
                        0f,
                        sanitizedView.width.toFloat(),
                        sanitizedView.height.toFloat(),
                        maskPaint
                    )
                    canvas.restore()
                    maskedCount++
                }
            }
            
            if (maskedCount > 0) {
                DebugUtils.log("Applied mask to $maskedCount sanitized element(s)")
            }
            
            if (mutableBitmap != bitmap) {
                bitmap.recycle()
            }
            return mutableBitmap
        }
    }

    private fun oldViewToBitmap(view: View): Bitmap {
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        view.draw(canvas)

        // Handle Jetpack Compose views
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                val child = view.getChildAt(i)
                if (child is AbstractComposeView) {
                    child.draw(canvas)
                }
            }
        }

        // Draw masks over sanitized elements
        synchronized(sanitizedElements) {
            sanitizedElements.removeAll { it.get() == null }
            
            sanitizedElements.forEach { weakRef ->
                val sanitizedView = weakRef.get()
                if (sanitizedView != null && sanitizedView.visibility == View.VISIBLE && sanitizedView.isAttachedToWindow) {
                    val location = IntArray(2)
                    sanitizedView.getLocationInWindow(location)
                    val rootViewLocation = IntArray(2)
                    view.getLocationInWindow(rootViewLocation)
                    val x = location[0] - rootViewLocation[0]
                    val y = location[1] - rootViewLocation[1]

                    canvas.save()
                    canvas.translate(x.toFloat(), y.toFloat())
                    canvas.drawRect(
                        0f,
                        0f,
                        sanitizedView.width.toFloat(),
                        sanitizedView.height.toFloat(),
                        maskPaint
                    )
                    canvas.restore()
                }
            }
        }

        fun iterateComposeView(vv: View) {
            if (vv is ViewGroup) {
                for (i in 0 until vv.childCount) {
                    val child = vv.getChildAt(i)
                    DebugUtils.log("iterateComposeView child: ${child::class.java.name}")

                    if (child is SanitizableViewGroup) {
                        DebugUtils.log("SanitizableViewGroup found")
                        val location = IntArray(2)
                        child.getLocationInWindow(location)
                        val rootViewLocation = IntArray(2)
                        view.getLocationInWindow(rootViewLocation)
                        val x = location[0] - rootViewLocation[0]
                        val y = location[1] - rootViewLocation[1]

                        canvas.save()
                        canvas.translate(x.toFloat(), y.toFloat())
                        canvas.drawRect(
                            0f,
                            0f,
                            child.width.toFloat(),
                            child.height.toFloat(),
                            maskPaint
                        )
                        canvas.restore()
                    } else if (child is ViewGroup) {
                        iterateComposeView(child)
                    }
                }
            }
        }

        fun iterateViewGroup(viewGroup: ViewGroup) {
            for (i in 0 until viewGroup.childCount) {
                val child = viewGroup.getChildAt(i)
                if (child is ViewGroup) {
                    iterateViewGroup(child)
                }

                if (child is ComposeView) {
                    iterateComposeView(child)
                }

                if (child is SanitizableViewGroup) {
                    iterateComposeView(child)
                }
            }
        }

        // Only iterate if it's a ViewGroup
        if (view is ViewGroup) {
            iterateViewGroup(view)
        }

        return bitmap
    }

    private val maskPaint = Paint().apply {
        style = Paint.Style.FILL
        val patternBitmap = createCrossStripedPatternBitmap()
        shader = BitmapShader(patternBitmap, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
    }

    private fun createCrossStripedPatternBitmap(): Bitmap {
        val patternSize = 80
        val patternBitmap =
            Bitmap.createBitmap(patternSize, patternSize, Bitmap.Config.ARGB_8888)
        val patternCanvas = Canvas(patternBitmap)
        val paint = Paint().apply {
            color = Color.DKGRAY
            style = Paint.Style.FILL
        }

        patternCanvas.drawColor(Color.WHITE)

        val stripeWidth = 20f
        val gap = stripeWidth / 4
        for (i in -patternSize until patternSize * 2 step (stripeWidth + gap).toInt()) {
            patternCanvas.drawLine(
                i.toFloat(),
                -gap,
                i.toFloat() + patternSize,
                patternSize.toFloat() + gap,
                paint
            )
        }

        patternCanvas.rotate(90f, patternSize / 2f, patternSize / 2f)

        for (i in -patternSize until patternSize * 2 step (stripeWidth + gap).toInt()) {
            patternCanvas.drawLine(
                i.toFloat(),
                -gap,
                i.toFloat() + patternSize,
                patternSize.toFloat() + gap,
                paint
            )
        }

        return patternBitmap
    }

    private suspend fun compress(originalBitmap: Bitmap): ByteArray = suspendCoroutine {
        ByteArrayOutputStream().use { outputStream ->
            try {
                if (originalBitmap.width <= 0 || originalBitmap.height <= 0) {
                    throw IllegalArgumentException("Invalid bitmap dimensions: ${originalBitmap.width}x${originalBitmap.height}")
                }
                
                val originalWidth = originalBitmap.width
                val originalHeight = originalBitmap.height
                val aspectRatio = originalWidth.toFloat() / originalHeight.toFloat()
                
                val newWidth: Int
                val newHeight: Int
                
                if (originalWidth < originalHeight) {
                    newWidth = minResolution.coerceAtLeast(1)
                    newHeight = (newWidth / aspectRatio).toInt().coerceAtLeast(1)
                } else {
                    newHeight = minResolution.coerceAtLeast(1)
                    newWidth = (newHeight * aspectRatio).toInt().coerceAtLeast(1)
                }
                
                val orientation = if (originalWidth < originalHeight) "Portrait" else "Landscape"
                DebugUtils.log("Screenshot scaling: $orientation ${originalWidth}x${originalHeight} -> ${newWidth}x${newHeight}")

                val updated = if (originalBitmap.width == newWidth && originalBitmap.height == newHeight) {
                    originalBitmap
                } else {
                    Bitmap.createScaledBitmap(originalBitmap, newWidth, newHeight, true)
                }

                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        updated.compress(Bitmap.CompressFormat.WEBP_LOSSLESS, quality, outputStream)
                    } else {
                        updated.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)
                    }
                    it.resumeWith(Result.success(outputStream.toByteArray()))
                } finally {
                    // Recycle scaled bitmap to free memory (only if different from original)
                    if (updated != originalBitmap) {
                        updated.recycle()
                    }
                }
            } catch (e: Exception) {
                DebugUtils.error("Error compressing bitmap: ${e.message}")
                it.resumeWith(Result.failure(e))
            } finally {
                // Always recycle original bitmap after compression
                originalBitmap.recycle()
            }
        }
    }

    /**
     * Captures the window and hands back exactly one [Result] on every path — including
     * the failure paths. A caller suspended on this callback must always be resumed.
     */
    private fun Activity.screenShot(result: (Result<Bitmap>) -> Unit) {
        val activity = this
        val deliver = AtomicBoolean(false)
        val once: (Result<Bitmap>) -> Unit = { outcome ->
            if (deliver.compareAndSet(false, true)) {
                result(outcome)
            } else {
                outcome.getOrNull()?.recycle()
            }
        }
        val fail: (String) -> Unit = { reason -> once(Result.failure(IllegalStateException(reason))) }

        if (activity.isFinishing || activity.isDestroyed) {
            DebugUtils.log("Activity is finishing or destroyed, skipping screenshot")
            fail("Activity is finishing or destroyed")
            return
        }

        val view = window?.decorView?.rootView
        if (view == null || view.width <= 0 || view.height <= 0) {
            DebugUtils.error("Invalid view for screenshot")
            fail("Invalid view for screenshot")
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val displayMetrics = resources.displayMetrics
            val bitmap = Bitmap.createBitmap(
                displayMetrics.widthPixels,
                displayMetrics.heightPixels,
                Bitmap.Config.ARGB_8888
            )

            if (mainHandler == null) {
                mainHandler = Handler(mainLooper)
            }
            
            try {
                PixelCopy.request(
                    activity.window,
                    bitmap, { copyResult ->
                        if (activity.isFinishing || activity.isDestroyed) {
                            bitmap.recycle()
                            fail("Activity is finishing or destroyed")
                            return@request
                        }

                        when (copyResult) {
                            PixelCopy.SUCCESS -> {
                                try {
                                    val maskedBitmap = applyMaskToScreenshot(bitmap, view)
                                    once(Result.success(maskedBitmap))
                                } catch (e: Exception) {
                                    DebugUtils.error("Failed to apply mask: ${e.message}")
                                    once(Result.success(bitmap))
                                }
                            }
                            else -> {
                                DebugUtils.error("PixelCopy failed with result: $copyResult, falling back to oldViewToBitmap")
                                bitmap.recycle()
                                try {
                                    once(Result.success(oldViewToBitmap(view)))
                                } catch (e: Exception) {
                                    DebugUtils.error("Fallback screenshot failed: ${e.message}")
                                    once(Result.failure(e))
                                }
                            }
                        }
                    },
                    mainHandler!!
                )
            } catch (e: Exception) {
                // Typically "Window doesn't have a backing surface!" — the window has no
                // surface yet on the first capture after returning from background.
                DebugUtils.log("PixelCopy request failed: ${e.message}")
                bitmap.recycle()
                once(Result.failure(e))
            }
        } else {
            try {
                once(Result.success(oldViewToBitmap(view)))
            } catch (e: Exception) {
                DebugUtils.error("Screenshot failed: ${e.message}")
                once(Result.failure(e))
            }
        }
    }

    private fun File.deleteSafely() {
        if (exists()) {
            try {
                delete()
            } catch (e: Exception) {
                DebugUtils.error("Error deleting file: ${e.message}")
            }
        }
    }
}
