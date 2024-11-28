package com.openreplay.tracker.managers

import NetworkManager
import NetworkManager.sessionId
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
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
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
import java.util.concurrent.Executors
import java.util.zip.GZIPOutputStream
import kotlin.coroutines.suspendCoroutine

object ScreenshotManager {

    private var lastTs: String = ""
    private var firstTs: String = ""
    private var sanitizedElements: MutableList<View> = mutableListOf()
    private var quality: Int = 10
    private var minResolution: Int = 320
    private lateinit var uiContext: WeakReference<Context>

    private var screenShotJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob()
            + Dispatchers.IO
            + CoroutineExceptionHandler { _, throwable ->
        DebugUtils.error(throwable)
    })

    private var isStoping = false
    fun setSettings(settings: Triple<Int, Int, Int>) {
        val (_, quality, resolution) = settings
        this.quality = quality
        this.minResolution = resolution
    }

    fun start(context: Context, startTs: Long) {
        uiContext = WeakReference(context)
        firstTs = startTs.toString()
        isStoping = false
        val intervalMillis =
            OpenReplay.options.screenshotFrequency.millis / OpenReplay.options.fps.toLong()

        // endless job to perform screen shot managing
        screenShotJob = scope.launch {
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

    private suspend fun sendScreenshotArchives() {
        // while we are doing  our job we  send data
        coroutineScope {
            try {
                val archives = getArchiveFolder().listFiles().orEmpty()
                if (archives.isEmpty()) return@coroutineScope
                DebugUtils.log("send archives size:${archives.size}")
                archives.forEach { archive ->
                    NetworkManager.sendImages(
                        projectKey = OpenReplay.projectKey!!,
                        images = archive.readBytes(),
                        name = archive.name
                    ) { success ->
                        if (success) {
                            archive.delete()
                        }
                    }
                }

            } catch (e: Exception) {
                DebugUtils.error(e)
            }
        }
    }

    private suspend fun makeScreenshotAndSaveWithArchive(chunk: Int = 10) {
        // compress add screen shot to storage and archive
        // create picture
        coroutineScope {
            try {
                DebugUtils.log("make screenshot")
                val screenShotBitmap = withContext(Dispatchers.Main) { captureScreenshot() }
                // get or create folder
                val screenShotFolder = getScreenshotFolder()
                val screenShotFile = File(screenShotFolder, "${System.currentTimeMillis()}.jpeg")
                DebugUtils.log("save screenshot")
                // save screen shot
                FileOutputStream(screenShotFile).use { out -> out.write(compress(screenShotBitmap)) }
                // make archive for $chunk pictures
                //  for example archivate folder for 10 pictures minimum
                if (screenShotFolder.listFiles().orEmpty().size >= chunk) {
                    archivateFolder(folder = screenShotFolder)
                }
            } catch (e: Exception) {
                DebugUtils.error(e)
            }
        }
    }

    private fun terminate() {
        // lives more than scope, uses only for termination
        // todo use work manager to be sure
        Executors.newSingleThreadExecutor()
            .asCoroutineDispatcher()
            .use { dispatcher ->
                scope.launch(dispatcher) {
                    try {
                        DebugUtils.log("terminate")
                        // get or create folder
                        val screenShotFolder = getScreenshotFolder()
                        // archive whole folder
                        archivateFolder(folder = screenShotFolder)
                        // send screen shots
                        sendScreenshotArchives()
                        DebugUtils.log("terminate done")
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
    }


    private fun archivateFolder(folder: File) {
        // now we have to add it to archive
        // prepare data
        val screenshots = folder.listFiles().orEmpty()
        screenshots.sortBy { it.lastModified() }

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
        // get archive
        val archiveFolder = getArchiveFolder()
        // create file
        val achiveFile = File(archiveFolder, "$sessionId-$lastTs.tar.gz")
        FileOutputStream(achiveFile).use { out -> out.write(combinedData.toByteArray()) }

        // when done achive delete all screenshots
        screenshots.forEach { it.delete() }
    }

    private fun getArchiveFolder(): File {
        val context = uiContext.get() ?: throw IllegalStateException("No context")
        val archiveFolder = File(context.filesDir, "archives")
        if (!archiveFolder.exists()) {
            archiveFolder.mkdir()
        }
        return archiveFolder
    }

    private fun getScreenshotFolder(): File {
        val context = uiContext.get() ?: throw IllegalStateException("No context")
        val screenShotFolder = File(context.filesDir, "screenshots")
        if (!screenShotFolder.exists()) {
            screenShotFolder.mkdir()
        }
        return screenShotFolder
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
}
