package com.openreplay.tracker.managers

import NetworkManager
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
import com.openreplay.tracker.models.RecordingFrequency
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.lang.ref.WeakReference
import java.util.Timer
import java.util.zip.GZIPOutputStream
import kotlin.concurrent.fixedRateTimer

object ScreenshotManager {

    private var timer: Timer? = null
    private var screenshots: MutableList<Pair<ByteArray, Long>> = mutableListOf()
    private var screenshotsBackup: MutableList<Pair<ByteArray, Long>> = mutableListOf()
    private var tick: Long = 0
    private var lastTs: Long = 0
    private var firstTs: Long = 0
    private var bufferTimer: Timer? = null
    private var sanitizedElements: MutableList<View> = mutableListOf()
    private lateinit var uiContext: WeakReference<Context>
    private var quality: Int = 10
    private var minResolution: Int = 320

    fun setSettings(settings: Triple<Int, Int, Int>) {
        val (_, quality, resolution) = settings
        ScreenshotManager.quality = quality
        ScreenshotManager.minResolution = resolution
    }

    fun start(context: Context, startTs: Long) {
        uiContext = WeakReference(context)
        firstTs = startTs
        startCapturing(OpenReplay.options.screenshotFrequency.millis / OpenReplay.options.fps.toLong())
    }

    private fun startCapturing(intervalMillis: Long = RecordingFrequency.Low.millis) {
        stopCapturing()
        timer = fixedRateTimer("screenshotTimer", false, 0L, intervalMillis) {
            captureScreenshot()
        }
    }

    fun stopCapturing() {
        stopCycleBuffer()
        timer?.cancel()
        timer = null
    }

    private fun captureScreenshot() {
        val activity = uiContext.get() as? Activity ?: return
        try {
            activity.screenShot { compressAndSend(it) }
        } catch (e: IllegalStateException) {
            DebugUtils.error(e.localizedMessage)
        } catch (e: IllegalArgumentException) {
            DebugUtils.error(e.localizedMessage)
        }
    }

    private fun Activity.screenShot(result: (Bitmap) -> Unit) {
        val activity = this
        val view = window.decorView.rootView
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // New version of Android, should use PixelCopy
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            val location = IntArray(2)
            view.getLocationInWindow(location)


            if (!activity.isFinishing)
                PixelCopy.request(
                    activity.window,
                    Rect(
                        location[0],
                        location[1],
                        location[0] + view.width,
                        location[1] + view.height
                    ),
                    bitmap, {
                        if (it == PixelCopy.SUCCESS) {
                            result(bitmap)
                        }
                    },
                    Handler(mainLooper)
                )
        } else {
            // Old version can keep using view.draw
            result(oldViewToBitmap(view))
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
        sanitizedElements.forEach { sanitizedView ->
            if (sanitizedView.visibility == View.VISIBLE && sanitizedView.isAttachedToWindow) {
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

        fun iterateComposeView(vv: View) {
            if (vv is ViewGroup) {
                for (i in 0 until vv.childCount) {
                    val child = vv.getChildAt(i)
                    println("iterateComposeView child: ${child::class.java.name}")

                    if (child is SanitizableViewGroup) {
                        println("SanitizableViewGroup")
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

        iterateViewGroup(view as ViewGroup)

        return bitmap
    }

    private val maskPaint = Paint().apply {
        style = Paint.Style.FILL
        val patternBitmap = createCrossStripedPatternBitmap()
        shader = BitmapShader(patternBitmap, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
    }

    private fun createCrossStripedPatternBitmap(): Bitmap {
        val patternSize = 80
        val patternBitmap = Bitmap.createBitmap(patternSize, patternSize, Bitmap.Config.ARGB_8888)
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

    private fun gzipCompress(data: ByteArray): ByteArray {
        ByteArrayOutputStream().use { byteArrayOutputStream ->
            GZIPOutputStream(byteArrayOutputStream).use { gzipOutputStream ->
                gzipOutputStream.write(data)
            }
            return byteArrayOutputStream.toByteArray()
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    private fun compressAndSend(originalBitmap: Bitmap) = GlobalScope.launch {
        ByteArrayOutputStream().use { outputStream ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                originalBitmap.compress(Bitmap.CompressFormat.WEBP_LOSSLESS, quality, outputStream)
            } else {
                originalBitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)
            }
            val aspectRatio = originalBitmap.height.toFloat() / originalBitmap.width.toFloat()
            val newHeight = (minResolution * aspectRatio).toInt()

            Bitmap.createScaledBitmap(originalBitmap, minResolution, newHeight, true)

            val screenshotData = outputStream.toByteArray()
//            saveToLocalFilesystem(appContext, screenshotData, "screenshot-${System.currentTimeMillis()}.jpg")
            screenshots.add(screenshotData to System.currentTimeMillis())
            sendScreenshots()
        }
    }


    private fun saveToLocalFilesystem(context: Context, imageData: ByteArray, filename: String) {
        val file = File(context.filesDir, filename)
        FileOutputStream(file).use { out ->
            out.write(imageData)
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    private fun sendScreenshots() {
        val sessionId = NetworkManager.sessionId ?: return
        val archiveName = "$sessionId-$lastTs.tar.gz"

        GlobalScope.launch(Dispatchers.IO) {
            val images = synchronized(screenshots) { ArrayList(screenshots) }
            screenshots.clear()

            val entries = images.map { (imageData, timestamp) ->
                lastTs = timestamp
                val filename = "${firstTs}_1_$timestamp.jpeg"
                filename to imageData
            }

            val combinedData = ByteArrayOutputStream()
            GzipCompressorOutputStream(combinedData).use { gzos ->
                TarArchiveOutputStream(gzos).use { tarOs ->
                    entries.forEach { (filename, imageData) ->
                        val tarEntry = TarArchiveEntry(filename)
                        tarEntry.size = imageData.size.toLong()
                        tarOs.putArchiveEntry(tarEntry)
                        ByteArrayInputStream(imageData).copyTo(tarOs)
                        tarOs.closeArchiveEntry()
                    }
                }
            }

            val gzData = combinedData.toByteArray()

            try {
                MessageCollector.sendImagesBatch(gzData, archiveName)
                screenshots.clear()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun syncBuffers() {
        val buf1 = screenshots.size
        val buf2 = screenshotsBackup.size
        tick = 0

        if (buf1 > buf2) {
            screenshotsBackup.clear()
        } else {
            screenshots = ArrayList(screenshotsBackup)
            screenshotsBackup.clear()
        }

        sendScreenshots()
    }

    fun startCycleBuffer() {
        bufferTimer = fixedRateTimer("cycleBuffer", false, 0L, 30_000) {
            cycleBuffer()
        }
    }

    fun stopCycleBuffer() {
        bufferTimer?.cancel()
        bufferTimer = null
    }

    fun cycleBuffer() {
        bufferTimer = fixedRateTimer("cycleBuffer", false, 0L, 30_000) {
            if (OpenReplay.bufferingMode) {
                if ((tick % 2).toInt() == 0) {
                    screenshots.clear()
                } else {
                    screenshotsBackup.clear()
                }
                tick++
            }
        }
    }

    fun addSanitizedElement(view: View) {
        if (OpenReplay.options.debugLogs) {
            DebugUtils.log("Sanitizing view: $view")
        }
        sanitizedElements.add(view)
    }

    fun removeSanitizedElement(view: View) {
        if (OpenReplay.options.debugLogs) {
            DebugUtils.log("Removing sanitized view: $view")
        }
        sanitizedElements.remove(view)
    }
}
