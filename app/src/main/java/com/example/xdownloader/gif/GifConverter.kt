package com.example.xdownloader.gif

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.Image
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
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
     * Uses software MediaCodec as primary engine, followed by Context-based Retriever and Glide.
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
            return@withContext Pair(null, "Видеофайл пуст или не прочитан (${videoFile.length()} байт)")
        }

        withContext(Dispatchers.Main) {
            onProgress(35)
        }

        val safeTitle = targetTitle
            .replace(Regex("[^a-zA-Z0-9а-яА-ЯёЁ._\\-\\s]"), "")
            .trim()
            .take(45)
            .ifEmpty { "X_Post" }
        val fileName = "${safeTitle}_anim.gif"

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

            // СТРАТЕГИЯ 1: Программный видеодекодер MediaCodec (Google Software AVC Decoder)
            // Программный декодер работает полностью в памяти CPU, не требует Surface и отдает сырые YUV-кадры
            try {
                framesAdded = extractWithSoftwareMediaCodec(
                    videoFile = videoFile,
                    maxFrames = totalTargetFrames,
                    frameStep = 2,
                    targetWidth = maxDimension
                ) { bitmap ->
                    encoder.addFrame(bitmap)
                    val p = (35 + ((framesAdded + 1) * 60 / totalTargetFrames)).coerceAtMost(95)
                    kotlinx.coroutines.runBlocking(Dispatchers.Main) {
                        onProgress(p)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }

            // СТРАТЕГИЯ 2: Резервное извлечение через Context + Uri в MediaMetadataRetriever
            if (framesAdded == 0) {
                framesAdded = extractWithContextRetriever(
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
            }

            // СТРАТЕГИЯ 3: Резервное извлечение через Glide Video Frame Loader
            if (framesAdded == 0) {
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
            }

            if (framesAdded == 0) {
                try { outStream.close() } catch (_: Throwable) {}
                if (outputUri != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    try { context.contentResolver.delete(outputUri, null, null) } catch (_: Throwable) {}
                } else if (targetFile != null && targetFile.exists()) {
                    targetFile.delete()
                }
                return@withContext Pair(null, "Не удалось извлечь кадры из видеофайла (frames=0)")
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
            try { outStream?.close() } catch (_: Throwable) {}
            if (outputUri != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try { context.contentResolver.delete(outputUri, null, null) } catch (_: Throwable) {}
            } else if (targetFile != null && targetFile.exists()) {
                targetFile.delete()
            }
            Pair(null, e.message ?: "Сбой при конвертации")
        }
    }

    /**
     * Декодирование через стандартный программный AVC/H.264 декодер Google.
     * Преимущество: работает на CPU, не зависит от ограничений GPU/Surface производителя телефона.
     */
    private fun extractWithSoftwareMediaCodec(
        videoFile: File,
        maxFrames: Int,
        frameStep: Int,
        targetWidth: Int,
        onFrame: (Bitmap) -> Unit
    ): Int {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var decodedCount = 0
        var acceptedCount = 0

        try {
            extractor.setDataSource(videoFile.absolutePath)
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

            // Предпочитаем программный декодер Google, который всегда поддерживает вывод в сырой буфер (без Surface)
            codec = try {
                MediaCodec.createByCodecName("c2.android.avc.decoder")
            } catch (_: Throwable) {
                try {
                    MediaCodec.createByCodecName("OMX.google.h264.decoder")
                } catch (_: Throwable) {
                    MediaCodec.createDecoderByType(mime)
                }
            }

            codec.configure(format, null, null, 0)
            codec.start()

            val info = MediaCodec.BufferInfo()
            var isEOS = false
            val timeoutUs = 15000L
            var attemptsWithoutOutput = 0

            while (!isEOS && acceptedCount < maxFrames && attemptsWithoutOutput < 100) {
                val inputIndex = codec.dequeueInputBuffer(timeoutUs)
                if (inputIndex >= 0) {
                    val inputBuffer = codec.getInputBuffer(inputIndex)
                    if (inputBuffer != null) {
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            isEOS = true
                        } else {
                            val timeUs = extractor.sampleTime
                            codec.queueInputBuffer(inputIndex, 0, sampleSize, timeUs, 0)
                            extractor.advance()
                        }
                    }
                }

                val outputIndex = codec.dequeueOutputBuffer(info, timeoutUs)
                if (outputIndex >= 0) {
                    attemptsWithoutOutput = 0
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        isEOS = true
                    }

                    if (info.size > 0) {
                        val image = try { codec.getOutputImage(outputIndex) } catch (_: Throwable) { null }
                        if (image != null) {
                            if (decodedCount % frameStep == 0) {
                                val bitmap = yuvImageToBitmap(image)
                                if (bitmap != null) {
                                    val scaled = if (bitmap.width != targetWidth && targetWidth > 0) {
                                        val aspect = bitmap.height.toFloat() / bitmap.width.toFloat()
                                        val targetHeight = ((targetWidth * aspect).toInt() / 2) * 2
                                        Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
                                    } else bitmap

                                    onFrame(scaled)
                                    acceptedCount++
                                }
                            }
                            try { image.close() } catch (_: Throwable) {}
                            decodedCount++
                        }
                    }
                    codec.releaseOutputBuffer(outputIndex, false)
                } else {
                    attemptsWithoutOutput++
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            try { codec?.stop() } catch (_: Throwable) {}
            try { codec?.release() } catch (_: Throwable) {}
            try { extractor.release() } catch (_: Throwable) {}
        }

        return acceptedCount
    }

    /**
     * Конвертация YUV_420_888 в RGB Bitmap (ITU-R BT.601)
     */
    private fun yuvImageToBitmap(image: Image): Bitmap? {
        return try {
            val width = image.width
            val height = image.height

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

            val pixels = IntArray(width * height)
            var pixelIdx = 0

            for (y in 0 until height) {
                val yRowOffset = y * yRowStride
                val uvRowOffset = (y shr 1) * uRowStride

                for (x in 0 until width) {
                    val yVal = yBuffer.get(yRowOffset + x * yPixelStride).toInt() and 0xFF
                    val uvColOffset = (x shr 1) * uPixelStride

                    val uVal = (uBuffer.get(uvRowOffset + uvColOffset).toInt() and 0xFF) - 128
                    val vVal = (vBuffer.get((y shr 1) * vRowStride + (x shr 1) * vPixelStride).toInt() and 0xFF) - 128

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
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Извлечение кадров через MediaMetadataRetriever с использованием Context + Uri
     */
    private fun extractWithContextRetriever(
        context: Context,
        videoFile: File,
        maxDurationSec: Int,
        fps: Int,
        maxDimension: Int,
        encoder: AnimatedGifEncoder,
        onProgress: (Int) -> Unit
    ): Int {
        val retriever = MediaMetadataRetriever()
        var added = 0

        try {
            // Использование Context + Uri позволяет обойти SELinux-ограничения mediaserver
            var setSuccess = false
            try {
                retriever.setDataSource(context, Uri.fromFile(videoFile))
                setSuccess = true
            } catch (_: Throwable) {
                try {
                    val fis = FileInputStream(videoFile)
                    retriever.setDataSource(fis.fd, 0L, videoFile.length())
                    setSuccess = true
                } catch (_: Throwable) {}
            }

            if (!setSuccess) return 0

            val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            val durationMs = durationStr?.toLongOrNull() ?: 3000L
            val actualDurationMs = min(durationMs, maxDurationSec * 1000L).coerceAtLeast(400L)

            val frameIntervalMs = 1000L / fps
            val totalFrames = (actualDurationMs / frameIntervalMs).toInt().coerceIn(2, 60)

            var lastValid: Bitmap? = null

            for (i in 0 until totalFrames) {
                val timeUs = i * frameIntervalMs * 1000L
                var frame: Bitmap? = null

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    try { frame = retriever.getFrameAtIndex(i) } catch (_: Throwable) {}
                }
                if (frame == null) {
                    try { frame = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST) } catch (_: Throwable) {}
                }
                if (frame == null) {
                    try { frame = retriever.getFrameAtTime(timeUs) } catch (_: Throwable) {}
                }
                if (frame == null && i == 0) {
                    try { frame = retriever.frameAtTime } catch (_: Throwable) {}
                }

                val toUse = frame ?: lastValid
                if (toUse != null) {
                    val scaled = if (toUse.width != maxDimension && maxDimension > 0) {
                        val aspect = toUse.height.toFloat() / toUse.width.toFloat()
                        val targetHeight = ((maxDimension * aspect).toInt() / 2) * 2
                        Bitmap.createScaledBitmap(toUse, maxDimension, targetHeight, true)
                    } else toUse

                    encoder.addFrame(scaled)
                    added++

                    if (frame != null && frame != lastValid) {
                        lastValid?.recycle()
                        lastValid = frame
                    }
                }

                val p = 35 + ((i + 1) * 60 / totalFrames)
                onProgress(p)
            }
            lastValid?.recycle()
        } catch (_: Throwable) {
        } finally {
            try { retriever.release() } catch (_: Throwable) {}
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
            val fileUri = Uri.fromFile(videoFile)
            var lastValid: Bitmap? = null

            for (i in 0 until totalFrames) {
                val timeUs = i * frameIntervalMs * 1000L
                var frame: Bitmap? = null
                try {
                    frame = Glide.with(context.applicationContext)
                        .asBitmap()
                        .load(fileUri)
                        .frame(timeUs)
                        .submit(maxDimension, maxDimension)
                        .get()
                } catch (_: Throwable) {}

                val toUse = frame ?: lastValid
                if (toUse != null) {
                    encoder.addFrame(toUse)
                    added++
                    if (frame != null && frame != lastValid) {
                        lastValid?.recycle()
                        lastValid = frame
                    }
                }

                val p = 35 + ((i + 1) * 60 / totalFrames)
                onProgress(p)
            }
            lastValid?.recycle()
        } catch (_: Throwable) {}
        return added
    }
}
