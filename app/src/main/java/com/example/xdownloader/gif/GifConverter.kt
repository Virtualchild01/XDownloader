package com.example.xdownloader.gif

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.media.Image
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.view.Surface
import com.bumptech.glide.Glide
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream
import kotlin.math.min

object GifConverter {

    /**
     * Converts a local MP4 video file into an animated GIF.
     * Uses 4 multi-tier decoding strategies (ImageReader Surface -> Software MediaCodec -> Retriever -> Glide).
     * @return Pair of (Uri?, errorMessage?)
     */
    suspend fun convertVideoToGif(
        context: Context,
        videoFile: File,
        targetTitle: String,
        maxDurationSec: Int = 10,
        fps: Int = 10,
        maxDimension: Int = 320,
        onProgress: (Int) -> Unit
    ): Pair<Uri?, String?> = withContext(Dispatchers.IO) {
        if (!videoFile.exists() || !videoFile.canRead() || videoFile.length() < 1000L) {
            return@withContext Pair(null, "Видеофайл пуст или не прочитан (" + videoFile.length() + " байт)")
        }

        withContext(Dispatchers.Main) {
            onProgress(35)
        }

        val safeTitle = targetTitle
            .filter { it.isLetterOrDigit() || it == '_' || it == '-' || it == '.' || it == ' ' }
            .trim()
            .take(45)
            .ifEmpty { "X_Post" }
        val fileName = safeTitle + "_anim.gif"

        var outputUri: Uri? = null
        var outStream: OutputStream? = null
        var targetFile: File? = null

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/gif")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                outputUri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (outputUri != null) {
                    outStream = context.contentResolver.openOutputStream(outputUri)
                }
            } else {
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!downloadsDir.exists()) downloadsDir.mkdirs()
                targetFile = File(downloadsDir, fileName)
                outStream = FileOutputStream(targetFile)
                outputUri = Uri.fromFile(targetFile)
            }

            if (outStream == null) {
                return@withContext Pair(null, "Не удалось создать файл в Загрузках")
            }

            val encoder = AnimatedGifEncoder()
            encoder.setDelay(1000 / fps)
            encoder.setRepeat(0) // 0 = бесконечный цикл
            encoder.setQuality(10)
            encoder.start(outStream)

            var framesAdded = 0
            val totalTargetFrames = maxDurationSec * fps
            val diag = StringBuilder()

            // СТРАТЕГИЯ 1 (Основная): Аппаратный декодер с рендерингом на Surface (ImageReader)
            // Поддерживается 100% чипов Qualcomm, MediaTek, Exynos
            try {
                framesAdded = extractWithImageReaderSurface(
                    videoFile = videoFile,
                    maxFrames = totalTargetFrames,
                    frameStep = 1,
                    targetWidth = maxDimension
                ) { bitmap ->
                    encoder.addFrame(bitmap)
                    val p = (35 + ((framesAdded + 1) * 60 / totalTargetFrames)).coerceAtMost(95)
                    kotlinx.coroutines.runBlocking(Dispatchers.Main) {
                        onProgress(p)
                    }
                }
            } catch (e: Throwable) {
                diag.append("S1: ").append(e.message ?: e.javaClass.simpleName).append("; ")
            }

            // СТРАТЕГИЯ 2: Программный MediaCodec CPU без Surface
            if (framesAdded == 0) {
                try {
                    framesAdded = extractWithSoftwareMediaCodec(
                        videoFile = videoFile,
                        maxFrames = totalTargetFrames,
                        frameStep = 1,
                        targetWidth = maxDimension
                    ) { bitmap ->
                        encoder.addFrame(bitmap)
                        val p = (35 + ((framesAdded + 1) * 60 / totalTargetFrames)).coerceAtMost(95)
                        kotlinx.coroutines.runBlocking(Dispatchers.Main) {
                            onProgress(p)
                        }
                    }
                } catch (e: Throwable) {
                    diag.append("S2: ").append(e.message ?: e.javaClass.simpleName).append("; ")
                }
            }

            // СТРАТЕГИЯ 3: Резервное извлечение через MediaMetadataRetriever
            if (framesAdded == 0) {
                try {
                    framesAdded = extractWithContextRetriever(
                        videoFile = videoFile,
                        maxDurationSec = maxDurationSec,
                        fps = fps,
                        maxDimension = maxDimension,
                        encoder = encoder
                    ) { p ->
                        kotlinx.coroutines.runBlocking(Dispatchers.Main) {
                            onProgress(p)
                        }
                    }
                } catch (e: Throwable) {
                    diag.append("S3: ").append(e.message ?: e.javaClass.simpleName).append("; ")
                }
            }

            // СТРАТЕГИЯ 4: Резервное извлечение через Glide
            if (framesAdded == 0) {
                try {
                    framesAdded = extractWithGlide(
                        context = context,
                        videoFile = videoFile,
                        maxDurationSec = maxDurationSec,
                        fps = fps,
                        maxDimension = maxDimension,
                        encoder = encoder
                    ) { p ->
                        kotlinx.coroutines.runBlocking(Dispatchers.Main) {
                            onProgress(p)
                        }
                    }
                } catch (e: Throwable) {
                    diag.append("S4: ").append(e.message ?: e.javaClass.simpleName).append("; ")
                }
            }

            if (framesAdded == 0) {
                try { outStream.close() } catch (ignored: Throwable) {}
                if (outputUri != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    try { context.contentResolver.delete(outputUri, null, null) } catch (ignored: Throwable) {}
                } else if (targetFile != null && targetFile.exists()) {
                    targetFile.delete()
                }
                val msg = if (diag.isNotBlank()) "Не удалось извлечь кадры (" + diag.toString().trim() + ")" else "Не удалось декодировать кадры из видеофайла"
                return@withContext Pair(null, msg)
            }

            encoder.finish()
            outStream.flush()
            outStream.close()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && outputUri != null) {
                val updateValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }
                context.contentResolver.update(outputUri, updateValues, null, null)
            }

            withContext(Dispatchers.Main) {
                onProgress(100)
            }

            Pair(outputUri, null)
        } catch (e: Exception) {
            e.printStackTrace()
            try { outStream?.close() } catch (ignored: Throwable) {}
            if (outputUri != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try { context.contentResolver.delete(outputUri, null, null) } catch (ignored: Throwable) {}
            } else if (targetFile != null && targetFile.exists()) {
                targetFile.delete()
            }
            Pair(null, e.message ?: "Сбой при конвертации")
        }
    }

    /**
     * Декодирование через аппаратный MediaCodec с выводом на Surface (ImageReader).
     * Аппаратные декодеры Qualcomm / MediaTek требуют Surface и безотказно декодируют на него.
     */
    private fun extractWithImageReaderSurface(
        videoFile: File,
        maxFrames: Int,
        frameStep: Int,
        targetWidth: Int,
        onFrame: (Bitmap) -> Unit
    ): Int {
        val extractor = MediaExtractor()
        var fis: FileInputStream? = null
        var decoder: MediaCodec? = null
        var imageReader: ImageReader? = null
        var surface: Surface? = null
        var decodedCount = 0
        var acceptedCount = 0

        try {
            fis = FileInputStream(videoFile)
            extractor.setDataSource(fis.fd, 0L, videoFile.length())

            var videoTrack = -1
            var format: MediaFormat? = null

            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("video/")) {
                    videoTrack = i
                    format = f
                    break
                }
            }

            if (videoTrack < 0 || format == null) return 0
            extractor.selectTrack(videoTrack)

            val mime = format.getString(MediaFormat.KEY_MIME) ?: "video/avc"
            val width = format.getInteger(MediaFormat.KEY_WIDTH)
            val height = format.getInteger(MediaFormat.KEY_HEIGHT)

            imageReader = ImageReader.newInstance(width, height, ImageFormat.YUV_420_888, 5)
            surface = imageReader.surface

            decoder = MediaCodec.createDecoderByType(mime)
            decoder.configure(format, surface, null, 0)
            decoder.start()

            val info = MediaCodec.BufferInfo()
            var sawInputEOS = false
            var sawOutputEOS = false
            var noOutputCounter = 0
            val timeoutUs = 10000L

            while (!sawOutputEOS && acceptedCount < maxFrames && noOutputCounter < 150) {
                if (!sawInputEOS) {
                    val inputIndex = decoder.dequeueInputBuffer(timeoutUs)
                    if (inputIndex >= 0) {
                        val inputBuffer = decoder.getInputBuffer(inputIndex)
                        if (inputBuffer != null) {
                            val sampleSize = extractor.readSampleData(inputBuffer, 0)
                            if (sampleSize < 0) {
                                decoder.queueInputBuffer(inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                sawInputEOS = true
                            } else {
                                val timeUs = extractor.sampleTime
                                decoder.queueInputBuffer(inputIndex, 0, sampleSize, timeUs, 0)
                                extractor.advance()
                            }
                        }
                    }
                }

                val outputIndex = decoder.dequeueOutputBuffer(info, timeoutUs)
                if (outputIndex >= 0) {
                    noOutputCounter = 0

                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        sawOutputEOS = true
                    }

                    // Рендерим готовый декодированный кадр на Surface ImageReader
                    decoder.releaseOutputBuffer(outputIndex, true)

                    // Считываем готовое изображение из ImageReader
                    var image: Image? = null
                    for (attempt in 0 until 5) {
                        image = imageReader.acquireNextImage()
                        if (image != null) break
                        try { Thread.sleep(3) } catch (ignored: Throwable) {}
                    }

                    if (image != null) {
                        if (decodedCount % frameStep == 0) {
                            val bitmap = yuvImageToBitmap(image)
                            if (bitmap != null) {
                                val scaled = if (bitmap.width != targetWidth && targetWidth > 0) {
                                    val aspect = bitmap.height.toFloat() / bitmap.width.toFloat()
                                    val targetHeight = ((targetWidth * aspect).toInt() / 2) * 2
                                    Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight.coerceAtLeast(2), true)
                                } else bitmap

                                onFrame(scaled)
                                acceptedCount++
                            }
                        }
                        try { image.close() } catch (ignored: Throwable) {}
                        decodedCount++
                    }
                } else if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED || outputIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                    noOutputCounter = 0
                } else {
                    if (sawInputEOS) {
                        noOutputCounter++
                    }
                }
            }
        } finally {
            try { decoder?.stop() } catch (ignored: Throwable) {}
            try { decoder?.release() } catch (ignored: Throwable) {}
            try { surface?.release() } catch (ignored: Throwable) {}
            try { imageReader?.close() } catch (ignored: Throwable) {}
            try { extractor.release() } catch (ignored: Throwable) {}
            try { fis?.close() } catch (ignored: Throwable) {}
        }

        return acceptedCount
    }

    /**
     * Полноценное декодирование через MediaExtractor + MediaCodec с программным буфером.
     */
    private fun extractWithSoftwareMediaCodec(
        videoFile: File,
        maxFrames: Int,
        frameStep: Int,
        targetWidth: Int,
        onFrame: (Bitmap) -> Unit
    ): Int {
        val extractor = MediaExtractor()
        var fis: FileInputStream? = null
        var codec: MediaCodec? = null
        var decodedCount = 0
        var acceptedCount = 0

        try {
            fis = FileInputStream(videoFile)
            extractor.setDataSource(fis.fd, 0L, videoFile.length())

            var videoTrack = -1
            var format: MediaFormat? = null

            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("video/")) {
                    videoTrack = i
                    format = f
                    break
                }
            }

            if (videoTrack < 0 || format == null) return 0
            extractor.selectTrack(videoTrack)

            val mime = format.getString(MediaFormat.KEY_MIME) ?: "video/avc"

            format.setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
            )

            val candidates = listOf("c2.android.avc.decoder", "OMX.google.h264.decoder", null)
            var activeDecoder: MediaCodec? = null

            for (name in candidates) {
                try {
                    val dec = if (name != null) {
                        MediaCodec.createByCodecName(name)
                    } else {
                        MediaCodec.createDecoderByType(mime)
                    }
                    dec.configure(format, null, null, 0)
                    dec.start()
                    activeDecoder = dec
                    break
                } catch (ignored: Throwable) {}
            }

            val decoder = activeDecoder ?: return 0
            codec = decoder

            val info = MediaCodec.BufferInfo()
            var sawInputEOS = false
            var sawOutputEOS = false
            var noOutputCounter = 0
            val timeoutUs = 10000L

            while (!sawOutputEOS && acceptedCount < maxFrames && noOutputCounter < 150) {
                if (!sawInputEOS) {
                    val inputIndex = decoder.dequeueInputBuffer(timeoutUs)
                    if (inputIndex >= 0) {
                        val inputBuffer = decoder.getInputBuffer(inputIndex)
                        if (inputBuffer != null) {
                            val sampleSize = extractor.readSampleData(inputBuffer, 0)
                            if (sampleSize < 0) {
                                decoder.queueInputBuffer(inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                sawInputEOS = true
                            } else {
                                val timeUs = extractor.sampleTime
                                decoder.queueInputBuffer(inputIndex, 0, sampleSize, timeUs, 0)
                                extractor.advance()
                            }
                        }
                    }
                }

                val outputIndex = decoder.dequeueOutputBuffer(info, timeoutUs)
                if (outputIndex >= 0) {
                    noOutputCounter = 0

                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        sawOutputEOS = true
                    }

                    if (info.size > 0) {
                        val image = try { decoder.getOutputImage(outputIndex) } catch (ignored: Throwable) { null }
                        if (image != null) {
                            if (decodedCount % frameStep == 0) {
                                val bitmap = yuvImageToBitmap(image)
                                if (bitmap != null) {
                                    val scaled = if (bitmap.width != targetWidth && targetWidth > 0) {
                                        val aspect = bitmap.height.toFloat() / bitmap.width.toFloat()
                                        val targetHeight = ((targetWidth * aspect).toInt() / 2) * 2
                                        Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight.coerceAtLeast(2), true)
                                    } else bitmap

                                    onFrame(scaled)
                                    acceptedCount++
                                }
                            }
                            try { image.close() } catch (ignored: Throwable) {}
                            decodedCount++
                        }
                    }
                    decoder.releaseOutputBuffer(outputIndex, false)
                } else if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED || outputIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                    noOutputCounter = 0
                } else {
                    if (sawInputEOS) {
                        noOutputCounter++
                    }
                }
            }
        } finally {
            try { codec?.stop() } catch (ignored: Throwable) {}
            try { codec?.release() } catch (ignored: Throwable) {}
            try { extractor.release() } catch (ignored: Throwable) {}
            try { fis?.close() } catch (ignored: Throwable) {}
        }

        return acceptedCount
    }

    /**
     * Конвертация YUV_420_888 в RGB Bitmap (ITU-R BT.601) с защитой от выхода за границы буферов.
     */
    private fun yuvImageToBitmap(image: Image): Bitmap? {
        return try {
            val crop = image.cropRect
            val width = crop.width()
            val height = crop.height()

            val yPlane = image.planes[0]
            val uPlane = image.planes[1]
            val vPlane = image.planes[2]

            val yBuffer = yPlane.buffer
            val uBuffer = uPlane.buffer
            val vBuffer = vPlane.buffer

            val yRowStride = yPlane.rowStride
            val yPixelStride = yPlane.pixelStride
            val uRowStride = uPlane.rowStride
            val uPixelStride = uPlane.pixelStride
            val vRowStride = vPlane.rowStride
            val vPixelStride = vPlane.pixelStride

            val yLimit = yBuffer.limit()
            val uLimit = uBuffer.limit()
            val vLimit = vBuffer.limit()

            val pixels = IntArray(width * height)
            var pixelIdx = 0

            for (y in 0 until height) {
                val yRowOffset = (y + crop.top) * yRowStride
                val uvRowIndex = (y + crop.top) shr 1

                for (x in 0 until width) {
                    val yOff = yRowOffset + (x + crop.left) * yPixelStride
                    val uvColIndex = (x + crop.left) shr 1

                    val uOff = uvRowIndex * uRowStride + uvColIndex * uPixelStride
                    val vOff = uvRowIndex * vRowStride + uvColIndex * vPixelStride

                    val yVal = if (yOff in 0 until yLimit) (yBuffer.get(yOff).toInt() and 0xFF) else 0
                    val uVal = (if (uOff in 0 until uLimit) (uBuffer.get(uOff).toInt() and 0xFF) else 128) - 128
                    val vVal = (if (vOff in 0 until vLimit) (vBuffer.get(vOff).toInt() and 0xFF) else 128) - 128

                    var r = (yVal + 1.402f * vVal).toInt()
                    var g = (yVal - 0.344136f * uVal - 0.714136f * vVal).toInt()
                    var b = (yVal + 1.772f * uVal).toInt()

                    if (r < 0) r = 0 else if (r > 255) r = 255
                    if (g < 0) g = 0 else if (g > 255) g = 255
                    if (b < 0) b = 0 else if (b > 255) b = 255

                    pixels[pixelIdx++] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
            }

            Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        } catch (e: Throwable) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Извлечение кадров через MediaMetadataRetriever с использованием FileDescriptor
     */
    private fun extractWithContextRetriever(
        videoFile: File,
        maxDurationSec: Int,
        fps: Int,
        maxDimension: Int,
        encoder: AnimatedGifEncoder,
        onProgress: (Int) -> Unit
    ): Int {
        val retriever = MediaMetadataRetriever()
        var added = 0
        var fis: FileInputStream? = null

        try {
            try {
                fis = FileInputStream(videoFile)
                retriever.setDataSource(fis.fd, 0L, videoFile.length())
            } catch (e: Throwable) {
                retriever.setDataSource(videoFile.absolutePath)
            }

            val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            val durationMs = durationStr?.toLongOrNull() ?: 2000L
            val actualDurationMs = min(durationMs, maxDurationSec * 1000L).coerceAtLeast(300L)

            val frameIntervalMs = 1000L / fps
            val totalFrames = (actualDurationMs / frameIntervalMs).toInt().coerceIn(2, 60)

            var lastValid: Bitmap? = null

            for (i in 0 until totalFrames) {
                val timeUs = i * frameIntervalMs * 1000L
                var frame: Bitmap? = null

                try {
                    frame = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
                } catch (ignored: Throwable) {}

                if (frame == null) {
                    try {
                        frame = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    } catch (ignored: Throwable) {}
                }

                if (frame == null) {
                    try {
                        frame = retriever.getFrameAtTime(timeUs)
                    } catch (ignored: Throwable) {}
                }

                if (frame == null && i == 0) {
                    try {
                        frame = retriever.frameAtTime
                    } catch (ignored: Throwable) {}
                }

                val toUse = frame ?: lastValid
                if (toUse != null) {
                    val scaled = if (toUse.width != maxDimension && maxDimension > 0) {
                        val aspect = toUse.height.toFloat() / toUse.width.toFloat()
                        val targetHeight = ((maxDimension * aspect).toInt() / 2) * 2
                        Bitmap.createScaledBitmap(toUse, maxDimension, targetHeight.coerceAtLeast(2), true)
                    } else toUse

                    encoder.addFrame(scaled)
                    added++

                    if (frame != null) {
                        if (lastValid != null && lastValid != frame) {
                            lastValid.recycle()
                        }
                        lastValid = frame
                    }
                }

                val p = 35 + ((i + 1) * 60 / totalFrames)
                onProgress(p)
            }
            lastValid?.recycle()
        } finally {
            try { fis?.close() } catch (ignored: Throwable) {}
            try { retriever.release() } catch (ignored: Throwable) {}
        }
        return added
    }

    /**
     * Извлечение кадров через Glide
     */
    private fun extractWithGlide(
        context: Context,
        videoFile: File,
        maxDurationSec: Int,
        fps: Int,
        maxDimension: Int,
        encoder: AnimatedGifEncoder,
        onProgress: (Int) -> Unit
    ): Int {
        var added = 0
        try {
            val totalFrames = maxDurationSec * fps
            val frameIntervalMs = 1000L / fps
            var lastValid: Bitmap? = null

            for (i in 0 until totalFrames) {
                val timeUs = i * frameIntervalMs * 1000L
                var frame: Bitmap? = null
                try {
                    frame = Glide.with(context.applicationContext)
                        .asBitmap()
                        .load(videoFile)
                        .frame(timeUs)
                        .submit(maxDimension, maxDimension)
                        .get()
                } catch (ignored: Throwable) {}

                val toUse = frame ?: lastValid
                if (toUse != null) {
                    encoder.addFrame(toUse)
                    added++
                    if (frame != null) {
                        if (lastValid != null && lastValid != frame) {
                            lastValid.recycle()
                        }
                        lastValid = frame
                    }
                }

                val p = 35 + ((i + 1) * 60 / totalFrames)
                onProgress(p)
            }
            lastValid?.recycle()
        } catch (e: Throwable) {
            e.printStackTrace()
        }
        return added
    }
}
