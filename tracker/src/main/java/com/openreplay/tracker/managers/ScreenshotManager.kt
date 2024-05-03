package com.openreplay.tracker.managers

import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import NetworkManager
import android.app.Activity
import android.content.Context
import android.graphics.*
import android.util.Log
import android.view.View
import com.openreplay.tracker.OpenReplay
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.*
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
    private lateinit var appContext: Context
    private var sanitizedElements: MutableList<View> = mutableListOf()
    private var quality: Int = 10

    fun setSettings(settings: Pair<Int, Int>) {
        val (_, quality) = settings
        this.quality = quality
    }

    fun start(context: Context, startTs: Long) {
        this.appContext = context
        firstTs = startTs
        startCapturing(1000 / OpenReplay.options.fps.toLong())
    }


    private fun startCapturing(intervalMillis: Long = 1000) {
        stopCapturing()
        timer = fixedRateTimer("screenshotTimer", false, 0L, intervalMillis) {
            captureScreenshot()
        }
    }

    fun stopCapturing() {
        timer?.cancel()
        timer = null
        stopCycleBuffer()
    }

    private fun captureScreenshot() {
        val view = (this.appContext as Activity).window.decorView.rootView

        (this.appContext as Activity).runOnUiThread {
            val bitmap = viewToBitmap(view, view.width, view.height)
            compressAndSend(bitmap)
        }
    }

    private val maskPaint = Paint().apply {
        style = Paint.Style.FILL
        val patternBitmap = createCrossStripedPatternBitmap()
        shader = BitmapShader(patternBitmap, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
    }

    private fun viewToBitmap(view: View, width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        view.draw(canvas)


        // Draw masks over sanitized elements
        sanitizedElements.forEach { sanitizedView ->
//            Log.d("ScreenshotManager", "Sanitized view: ${sanitizedView.visibility}")
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
                canvas.drawRect(0f, 0f, sanitizedView.width.toFloat(), sanitizedView.height.toFloat(), maskPaint)
                canvas.restore()
            }
        }
        return bitmap
    }

    private fun createCrossStripedPatternBitmap(): Bitmap {
        val patternSize = 80 // This is the size of the square pattern
        val patternBitmap = Bitmap.createBitmap(patternSize, patternSize, Bitmap.Config.ARGB_8888)
        val patternCanvas = Canvas(patternBitmap)
        val paint = Paint().apply {
            color = Color.DKGRAY // Color of the stripes
            style = Paint.Style.FILL
        }

        patternCanvas.drawColor(Color.WHITE)

        val stripeWidth = 20f // Width of the stripes
        val gap = stripeWidth / 4 // Gap between stripes
        for (i in -patternSize until patternSize * 2 step (stripeWidth + gap).toInt()) {
            patternCanvas.drawLine(i.toFloat(), -gap, i.toFloat() + patternSize, patternSize.toFloat() + gap, paint)
        }

        patternCanvas.rotate(90f, patternSize / 2f, patternSize / 2f)

        for (i in -patternSize until patternSize * 2 step (stripeWidth + gap).toInt()) {
            patternCanvas.drawLine(i.toFloat(), -gap, i.toFloat() + patternSize, patternSize.toFloat() + gap, paint)
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
    private fun compressAndSend(bitmap: Bitmap) = GlobalScope.launch {
        ByteArrayOutputStream().use { outputStream ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)
            val screenshotData = outputStream.toByteArray()

            // saveToLocalFilesystem(appContext, screenshotData, "screenshot-${System.currentTimeMillis()}.jpg")
            screenshots.add(Pair(screenshotData, System.currentTimeMillis()))
            sendScreenshots()
        }
    }

    private fun saveToLocalFilesystem(context: Context, imageData: ByteArray, filename: String) {
        val file = File(context.filesDir, filename)
        FileOutputStream(file).use { out ->
            out.write(imageData)
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

    @OptIn(DelicateCoroutinesApi::class)
    private fun sendScreenshots() {
        val sessionId = NetworkManager.sessionId ?: return
        val archiveName = "$sessionId-$lastTs.tar.gz"

        GlobalScope.launch(Dispatchers.IO) {
            val entries = mutableListOf<Pair<String, ByteArray>>()
            val images = synchronized(screenshots) { ArrayList(screenshots) }
            screenshots.clear()

            images.forEach { (imageData, timestamp) ->
                val filename = "${firstTs}_1_$timestamp.jpeg"
                entries.add(filename to imageData)
                lastTs = timestamp
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
