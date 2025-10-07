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
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.lang.ref.WeakReference
import kotlin.coroutines.suspendCoroutine

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

    fun setSettings(settings: Triple<Int, Int, Int>) {
        val (_, quality, resolution) = settings
        this.quality = quality
        this.minResolution = resolution
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
        
        DebugUtils.log("Sanitizing view: $view")
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
            val archives = getArchiveFolder().listFiles().orEmpty()
            if (archives.isEmpty()) return@withContext

            val projectKey = OpenReplay.projectKey
            if (projectKey == null) {
                DebugUtils.error("Project key is null, cannot send screenshot archives")
                return@withContext
            }
            
            archives.forEach { archive ->
                NetworkManager.sendImages(
                    projectKey = projectKey,
                    images = archive.readBytes(),
                    name = archive.name
                ) { success ->
                    scope.launch {
                        if (success) {
                            archive.deleteSafely()
                        }
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
                val screenShotFolder = getScreenshotFolder()
                val screenShotFile = File(screenShotFolder, "${System.currentTimeMillis()}.jpeg")
                FileOutputStream(screenShotFile).use { out -> out.write(compress(screenShotBitmap)) }
                if (screenShotFolder.listFiles().orEmpty().size >= chunk) {
                    archivateFolder(folder = screenShotFolder)
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
                archivateFolder(screenshotFolder)
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
        val screenshots = folder.listFiles().orEmpty().sortedBy { it.lastModified() }
        
        if (screenshots.isEmpty()) {
            DebugUtils.log("No screenshots to archive")
            return
        }

        // combine chunked data to zip
        val combinedData = ByteArrayOutputStream()
        GzipCompressorOutputStream(combinedData).use { gzos ->
            TarArchiveOutputStream(gzos).use { tarOs ->
                screenshots.forEach { jpeg ->
                    lastTs = jpeg.nameWithoutExtension
                    val filename = "${firstTs}_1_${jpeg.nameWithoutExtension}.jpeg"
                    val readBytes = jpeg.readBytes()
                    val tarEntry = TarArchiveEntry(filename)
                    tarEntry.size = readBytes.size.toLong()
                    tarOs.putArchiveEntry(tarEntry)
                    ByteArrayInputStream(readBytes).copyTo(tarOs)
                    tarOs.closeArchiveEntry()
                }
            }
        }
        val archiveFolder = getArchiveFolder()
        val sessionId = NetworkManager.sessionId ?: "unknown"
        val archiveFile = File(archiveFolder, "$sessionId-$lastTs.tar.gz")
        FileOutputStream(archiveFile).use { out -> out.write(combinedData.toByteArray()) }

        scope.launch {
            screenshots.forEach { it.deleteSafely() }
        }
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
        
        return suspendCoroutine { coroutine ->
            try {
                activity.screenShot { shot ->
                    if (!coroutine.context.isActive) {
                        shot.recycle()
                        return@screenShot
                    }
                    coroutine.resumeWith(Result.success(shot))
                }
            } catch (e: Exception) {
                coroutine.resumeWith(Result.failure(e))
            }
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
            // Clean up null references
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

                    // Draw the striped mask over the sanitized view
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

    private fun Activity.screenShot(result: (Bitmap) -> Unit) {
        val activity = this
        
        if (activity.isFinishing || activity.isDestroyed) {
            DebugUtils.log("Activity is finishing or destroyed, skipping screenshot")
            return
        }
        
        val view = window?.decorView?.rootView
        if (view == null || view.width <= 0 || view.height <= 0) {
            DebugUtils.error("Invalid view for screenshot")
            return
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            val location = IntArray(2)
            view.getLocationInWindow(location)

            if (mainHandler == null) {
                mainHandler = Handler(mainLooper)
            }
            
            try {
                PixelCopy.request(
                    activity.window,
                    Rect(
                        location[0],
                        location[1],
                        location[0] + view.width,
                        location[1] + view.height
                    ),
                    bitmap, { copyResult ->
                        if (activity.isFinishing || activity.isDestroyed) {
                            bitmap.recycle()
                            return@request
                        }
                        
                        when (copyResult) {
                            PixelCopy.SUCCESS -> {
                                result(bitmap)
                            }
                            else -> {
                                DebugUtils.error("PixelCopy failed with result: $copyResult, falling back to oldViewToBitmap")
                                bitmap.recycle()
                                try {
                                    result(oldViewToBitmap(view))
                                } catch (e: Exception) {
                                    DebugUtils.error("Fallback screenshot failed: ${e.message}")
                                }
                            }
                        }
                    },
                    mainHandler!!
                )
            } catch (e: Exception) {
                DebugUtils.error("PixelCopy request failed: ${e.message}")
                bitmap.recycle()
            }
        } else {
            try {
                result(oldViewToBitmap(view))
            } catch (e: Exception) {
                DebugUtils.error("Screenshot failed: ${e.message}")
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
