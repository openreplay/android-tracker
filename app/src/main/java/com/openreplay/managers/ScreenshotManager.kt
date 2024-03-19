package com.openreplay.managers

import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import NetworkManager
import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import com.openreplay.OpenReplay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

    fun setSettings(settings: Pair<Double, Double>) {
        // Set up the screenshot manager
    }

    fun start(context: Context, startTs: Long) {
        this.appContext = context
//        firstTs = startTs
        startCapturing()
//        startCycleBuffer()
    }


    private fun startCapturing(intervalMillis: Long = 1000) {
        stopCapturing()
        timer = fixedRateTimer("screenshotTimer", false, 0L, intervalMillis) {
            captureScreenshot()
        }
    }

    // Placeholder for stopping the screenshot capture process
    private fun stopCapturing() {
        timer?.cancel()
        timer = null
    }

    // Simplified screenshot capturing (for the current app only)
    private fun captureScreenshot() {
        // Assuming this function is called within an Activity context
        val view = (this.appContext as Activity).window.decorView.rootView

        // Make sure to call this on the UI thread
        (this.appContext as Activity).runOnUiThread {
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            view.draw(canvas)

            compressAndSend(bitmap)
        }
    }


    private fun viewToBitmap(view: View, width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        view.draw(canvas)
        return bitmap
    }

    private fun gzipCompress(data: ByteArray): ByteArray {
        ByteArrayOutputStream().use { byteArrayOutputStream ->
            GZIPOutputStream(byteArrayOutputStream).use { gzipOutputStream ->
                gzipOutputStream.write(data)
            }
            return byteArrayOutputStream.toByteArray()
        }
    }

    private fun compressAndSend(bitmap: Bitmap) = GlobalScope.launch {
        ByteArrayOutputStream().use { outputStream ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 50, outputStream)
            val screenshotData = outputStream.toByteArray()
            val gzippedData = gzipCompress(screenshotData)

            saveToLocalFilesystem(appContext, screenshotData, "screenshot-${System.currentTimeMillis()}.jpg")
            screenshots.add(Pair(gzippedData, System.currentTimeMillis()))
            sendScreenshots()
        }
    }

    private fun saveToLocalFilesystem(context: Context, imageData: ByteArray, filename: String) {
        val file = File(context.filesDir, filename)
        FileOutputStream(file).use { out ->
            out.write(imageData)
        }
    }

    private fun sendScreenshotData(data: ByteArray) {
        // Implementation depends on your backend and network library
    }

    suspend fun syncBuffers() {
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

    private suspend fun sendScreenshots() {
        val sessionId = NetworkManager.sessionId ?: return
        val archiveName = "$sessionId-$lastTs.tar.gz"
        val combinedData = ByteArrayOutputStream()

        // Compress images into a single GZIP file (simplified)
        withContext(Dispatchers.IO) {
            GZIPOutputStream(combinedData).use { gzipOutputStream ->
                screenshots.forEach { (imageData, _) ->
                    gzipOutputStream.write(imageData)
                }
            }
        }

        val gzData = combinedData.toByteArray()

        // Assuming sendImagesBatch is a suspend function that sends the compressed data
        withContext(Dispatchers.IO) {
            try {
                NetworkManager.sendImagesBatch(gzData, archiveName)
                screenshots.clear()
            } catch (e: Exception) {
                e.printStackTrace() // Handle error appropriately
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

    private fun cycleBuffer() {
        if (OpenReplay.options.bufferingMode) {
            if ((tick % 2).toInt() == 0) {
                screenshots.clear()
            } else {
                screenshotsBackup.clear()
            }
            tick++
        }
        // Note: This code runs on a background thread, ensure any UI updates are posted to the main thread
    }
}

//private fun <E> MutableList<E>.add(element: ByteArray): Boolean {
//    return add(element)
//}
