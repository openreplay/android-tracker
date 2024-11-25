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
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.lang.ref.WeakReference
import java.util.concurrent.Executors
import java.util.zip.GZIPOutputStream
import kotlin.coroutines.suspendCoroutine

object ScreenshotManager {
    private var screenShotJob: Job? = null

    private var screenshots: MutableList<Pair<File, Long>> = mutableListOf()
    private var lastTs: Long = 0
    private var firstTs: Long = 0
    private var sanitizedElements: MutableList<View> = mutableListOf()
    private lateinit var uiContext: WeakReference<Context>
    private var quality: Int = 10
    private var minResolution: Int = 320


    private var isStoping = false
    fun setSettings(settings: Triple<Int, Int, Int>) {
        val (_, quality, resolution) = settings
        this.quality = quality
        this.minResolution = resolution
    }

    @OptIn(DelicateCoroutinesApi::class)
    fun start(context: Context, startTs: Long) {
        uiContext = WeakReference(context)
        firstTs = startTs
        isStoping = false
        // endless job to perform capturing

        screenShotJob = GlobalScope.launch {
            val intervalMillis =
                OpenReplay.options.screenshotFrequency.millis / OpenReplay.options.fps.toLong()
            while (true) {
                delay(intervalMillis)
                val screenShot = withContext(Dispatchers.Main) { captureScreenshot() }
                withContext(Dispatchers.IO) {
                    try {
                        val byteScreenShot = compress(screenShot)
                        // add screen shot to storage
                        addToScreenShots(byteScreenShot)
                        // while we are doing  our job we  gather and send data
                        sendScreenshots(chunkSize = 10)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        }
    }

    fun stop() {
        screenShotJob?.cancel()
        terminate()
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

    private suspend fun captureScreenshot(): Bitmap {
        val activity =
            uiContext.get() as? Activity ?: throw IllegalStateException("No Activity")
        return suspendCoroutine { coroutine ->
            activity.screenShot { shot ->
                coroutine.resumeWith(Result.success(shot))
            }
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

    private fun gzipCompress(data: ByteArray): ByteArray {
        ByteArrayOutputStream().use { byteArrayOutputStream ->
            GZIPOutputStream(byteArrayOutputStream).use { gzipOutputStream ->
                gzipOutputStream.write(data)
            }
            return byteArrayOutputStream.toByteArray()
        }
    }

    private suspend fun compress(originalBitmap: Bitmap): ByteArray = suspendCoroutine {
        ByteArrayOutputStream().use { outputStream ->
            val aspectRatio = originalBitmap.height.toFloat() / originalBitmap.width.toFloat()
            val newHeight = (minResolution * aspectRatio).toInt()

            val updated =
                Bitmap.createScaledBitmap(originalBitmap, minResolution, newHeight, true)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                updated.compress(Bitmap.CompressFormat.WEBP_LOSSLESS, quality, outputStream)
            } else {
                updated.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)
            }
            it.resumeWith(Result.success(outputStream.toByteArray()))
        }
    }

    private suspend fun sendScreenshots(chunkSize: Int) {
        coroutineScope {
            val sessionId =
                NetworkManager.sessionId ?: throw IllegalStateException("No session")
            if (screenshots.size < chunkSize) {
                DebugUtils.log("buffering screenshots ${screenshots.size}")
            } else {
                DebugUtils.log("archiving screenshots ${screenshots.size}")

                val archiveName = "$sessionId-$lastTs.tar.gz"
                // prepare data
                val entries = screenshots.map { (imageFile, timestamp) ->
                    lastTs = timestamp
                    val filename = "${firstTs}_1_$timestamp.jpeg"
                    filename to imageFile.readBytes()
                }
                // delete real picture from device
                screenshots.forEach { (file, _) -> file.delete() }
                screenshots.clear()

                // zip all pictures
                val combinedData = ByteArrayOutputStream()
                GzipCompressorOutputStream(combinedData).use { gzos ->
                    TarArchiveOutputStream(gzos).use { tarOs ->
                        entries.forEach { (filename, readBytes) ->
                            val tarEntry = TarArchiveEntry(filename)
                            tarEntry.size = readBytes.size.toLong()
                            tarOs.putArchiveEntry(tarEntry)
                            ByteArrayInputStream(readBytes).copyTo(tarOs)
                            tarOs.closeArchiveEntry()
                        }
                    }
                }

                val archive = saveToLocalFilesystem(
                    bytes = combinedData.toByteArray(),
                    filename = archiveName
                )
                NetworkManager.sendImages(
                    projectKey = OpenReplay.projectKey!!,
                    images = archive.readBytes(),
                    name = archive.name
                ) { success ->
                    archive.delete()
                }
            }
        }
    }

    private fun addToScreenShots(byteScreenShot: ByteArray) {
        val millis = System.currentTimeMillis()
        val file = saveToLocalFilesystem(
            bytes = byteScreenShot,
            filename = "screenshot-${System.currentTimeMillis()}.jpg"
        )
        screenshots.add(file to millis)
    }

    private fun saveToLocalFilesystem(bytes: ByteArray, filename: String): File {
        val context = uiContext.get() ?: throw IllegalStateException("No context")
        val file = File(context.filesDir, filename)
        FileOutputStream(file).use { out ->
            out.write(bytes)
        }
        return file
    }

    private fun terminate() {
        if (screenshots.isNotEmpty()) {
            Executors.newSingleThreadExecutor()
                .asCoroutineDispatcher()
                .use { dispatcher ->
                    runBlocking {
                        launch(dispatcher) {
                            sendScreenshots(chunkSize = screenshots.size)
                        }
                    }
                }
        }
    }
}
