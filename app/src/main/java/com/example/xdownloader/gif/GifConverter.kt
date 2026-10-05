package com.example.xdownloader.gif

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import com.bumptech.glide.Glide
import com.bumptech.glide.request.RequestOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jcodec.android.AndroidUtil
import org.jcodec.api.FrameGrab
import org.jcodec.common.io.NIOUtils
import org.jcodec.common.io.SeekableByteChannel
import org.jcodec.common.model.ColorSpace
import org.jcodec.common.model.Picture
import org.jcodec.scale.ColorUtil
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream
import kotlin.math.min

object GifConverter {

    /**
     * Converts a local MP4 video file into an animated GIF.
     * Uses pure Java H.264 decoding (JCodec) with correct color restoration and smooth playback delay.
     * @return Pair of (Uri?, errorMessage?)
     */
    suspend fun convertVideoToGif(
        context: Context,
        videoFile: File,
        targetTitle: String,
        maxDurationSec: Int = 10,
        fps: Int = 10,
        maxDimension: Int = 540,
        onProgress: (Int) -> Unit
    ): Pair<Uri?, String?> = withContext(Dispatchers.IO) {
        if (!videoFile.exists() || !videoFile.canRead() || videoFile.length() < 1000L) {
            return@withContext Pair(null, "Видеофайл пуст или не прочитан (" + videoFile.length() + " байт)")
        }

        try {
            videoFile.setReadable(true, false)
        } catch (ignored: Throwable) {}

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
            encoder.setDelay(100) // По умолчанию 100 мс (10 fps)
            encoder.setRepeat(0) // 0 = бесконечный цикл
            encoder.setQuality(10)
            encoder.start(outStream)

            var framesAdded = 0
            val totalTargetFrames = maxDurationSec * fps
            val diagLog = StringBuilder()

            // УРОВЕНЬ 1 (Основной): 100% чистый Java декодер JCodec.
            // Работает прямо в процессе приложения, не зависит от mediaserver, драйверов Qualcomm/MediaTek и Surface.
            try {
                framesAdded = extractWithJCodec(
                    videoFile = videoFile,
                    maxFrames = totalTargetFrames,
                    targetWidth = maxDimension,
                    encoder = encoder,
                    onProgress = onProgress
                )
            } catch (e: Throwable) {
                diagLog.append("JCodec: ").append(e.message ?: e.javaClass.simpleName).append("; ")
            }

            // УРОВЕНЬ 2 (Резервный): MediaMetadataRetriever через PFD / дескриптор
            if (framesAdded == 0) {
                try {
                    framesAdded = extractWithRetriever(
                        context = context,
                        videoFile = videoFile,
                        maxDurationSec = maxDurationSec,
                        fps = fps,
                        maxDimension = maxDimension,
                        encoder = encoder,
                        diagLog = diagLog
                    ) { p ->
                        kotlinx.coroutines.runBlocking(Dispatchers.Main) {
                            onProgress(p)
                        }
                    }
                } catch (e: Throwable) {
                    diagLog.append("MMR: ").append(e.message ?: e.javaClass.simpleName).append("; ")
                }
            }

            // УРОВЕНЬ 3 (Резервный): Glide Video Frame Loader
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
                    diagLog.append("Glide: ").append(e.message ?: e.javaClass.simpleName).append("; ")
                }
            }

            if (framesAdded == 0) {
                try { outStream.close() } catch (ignored: Throwable) {}
                if (outputUri != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    try { context.contentResolver.delete(outputUri, null, null) } catch (ignored: Throwable) {}
                } else if (targetFile != null && targetFile.exists()) {
                    targetFile.delete()
                }
                val msg = if (diagLog.isNotBlank()) {
                    "Сбой декодирования (" + diagLog.toString().trim() + ", размер: " + videoFile.length() + " Б)"
                } else {
                    "Не удалось извлечь кадры из видеофайла (размер: " + videoFile.length() + " Б)"
                }
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
     * Декодирование видео через чистый Java движок JCodec.
     * Не использует mediaserver, MediaCodec, Surface или нативные библиотеки C++.
     */
    private fun extractWithJCodec(
        videoFile: File,
        maxFrames: Int,
        targetWidth: Int,
        encoder: AnimatedGifEncoder,
        onProgress: (Int) -> Unit
    ): Int {
        var channel: SeekableByteChannel? = null
        val decodedBitmaps = mutableListOf<Bitmap>()

        try {
            channel = NIOUtils.readableChannel(videoFile)
            val grab = FrameGrab.createFrameGrab(channel)

            var readCount = 0
            while (readCount < maxFrames) {
                val pic: Picture = grab.nativeFrame ?: break

                val bitmap = jcodecPictureToBitmap(pic)
                if (bitmap != null) {
                    // Масштабируем ТОЛЬКО если ширина кадра превышает targetWidth.
                    // Если исходное видео 500x500 (стандартный GIF твиттера), сохраняем исходный размер 1:1!
                    val scaled = if (targetWidth > 0 && bitmap.width > targetWidth) {
                        val aspect = bitmap.height.toFloat() / bitmap.width.toFloat()
                        val targetHeight = ((targetWidth * aspect).toInt() / 2) * 2
                        val s = Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight.coerceAtLeast(2), true)
                        if (s != bitmap) bitmap.recycle()
                        s
                    } else bitmap

                    decodedBitmaps.add(scaled)
                }
                readCount++
            }

            if (decodedBitmaps.isEmpty()) return 0

            val totalFrames = decodedBitmaps.size

            // Извлекаем точную длительность исходного видео (в мс) через MediaMetadataRetriever
            var videoDurationMs = 0L
            try {
                val mmr = MediaMetadataRetriever()
                mmr.setDataSource(videoFile.absolutePath)
                videoDurationMs = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                mmr.release()
            } catch (ignored: Throwable) {}

            // Рассчитываем точную задержку между кадрами для сохранения оригинальной скорости
            // Ограничиваем разумными пределами: 70..140 мс (~7-14 fps), по умолчанию 95 мс (~10.5 fps)
            val delayPerFrameMs = if (videoDurationMs > 0L) {
                (videoDurationMs.toDouble() / totalFrames).toInt().coerceIn(70, 140)
            } else {
                95
            }

            encoder.setDelay(delayPerFrameMs)
            encoder.setRepeat(0) // Бесконечный цикл

            for ((index, bmp) in decodedBitmaps.withIndex()) {
                encoder.addFrame(bmp)
                bmp.recycle()

                val p = 35 + ((index + 1) * 60 / totalFrames)
                kotlinx.coroutines.runBlocking(Dispatchers.Main) {
                    onProgress(p)
                }
            }
        } finally {
            try { NIOUtils.closeQuietly(channel) } catch (ignored: Throwable) {}
            for (b in decodedBitmaps) {
                if (!b.isRecycled) b.recycle()
            }
        }
        return decodedBitmaps.size
    }

    /**
     * Конвертация Picture из JCodec в стандартный Android ARGB_8888 Bitmap
     * с сохранением 100% исходной яркости и правильным цветовым пространством.
     */
    private fun jcodecPictureToBitmap(src: Picture): Bitmap? {
        return try {
            val cleanPic = if (src.crop != null) src.createCropped() else src

            // 1. Официальный конвертер JCodec для Android
            try {
                val bmp = AndroidUtil.toBitmap(cleanPic)
                if (bmp != null) return bmp
            } catch (ignored: Throwable) {}

            // 2. Резервный конвертер с правильным преобразованием YUV -> RGB (без затемнения)
            val rgbPic: Picture
            if (cleanPic.color == ColorSpace.RGB) {
                rgbPic = cleanPic
            } else {
                val transform = ColorUtil.getTransform(cleanPic.color, ColorSpace.RGB)
                rgbPic = Picture.create(cleanPic.width, cleanPic.height, ColorSpace.RGB)
                transform.transform(cleanPic, rgbPic)
            }

            val w = rgbPic.width
            val h = rgbPic.height
            val bytes = rgbPic.data[0]
            val pixels = IntArray(w * h)
            var bi = 0
            val limit = bytes.size

            for (i in 0 until (w * h)) {
                if (bi + 2 < limit) {
                    // Беззнаковое преобразование байта (and 0xFF) даёт точные значения 0..255
                    val r = bytes[bi++].toInt() and 0xFF
                    val g = bytes[bi++].toInt() and 0xFF
                    val b = bytes[bi++].toInt() and 0xFF
                    pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                } else {
                    pixels[i] = -0x1000000
                }
            }

            Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
        } catch (e: Throwable) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Извлечение кадров через MediaMetadataRetriever (универсальный перебор).
     */
    private fun extractWithRetriever(
        context: Context,
        videoFile: File,
        maxDurationSec: Int,
        fps: Int,
        maxDimension: Int,
        encoder: AnimatedGifEncoder,
        diagLog: StringBuilder,
        onProgress: (Int) -> Unit
    ): Int {
        var added = 0
        var retriever: MediaMetadataRetriever? = null
        var pfd: ParcelFileDescriptor? = null
        var fis: FileInputStream? = null

        try {
            var initialized = false

            // Попытка 1: ParcelFileDescriptor
            try {
                val testRetriever = MediaMetadataRetriever()
                pfd = ParcelFileDescriptor.open(videoFile, ParcelFileDescriptor.MODE_READ_ONLY)
                testRetriever.setDataSource(pfd.fileDescriptor)
                retriever = testRetriever
                initialized = true
            } catch (e: Throwable) {
                diagLog.append("PfdInit: ").append(e.message ?: e.javaClass.simpleName).append("; ")
            }

            // Попытка 2: абсолютный путь
            if (!initialized) {
                try {
                    val testRetriever = MediaMetadataRetriever()
                    testRetriever.setDataSource(videoFile.absolutePath)
                    retriever = testRetriever
                    initialized = true
                } catch (e: Throwable) {
                    diagLog.append("PathInit: ").append(e.message ?: e.javaClass.simpleName).append("; ")
                }
            }

            // Попытка 3: FileInputStream FD
            if (!initialized) {
                try {
                    val testRetriever = MediaMetadataRetriever()
                    fis = FileInputStream(videoFile)
                    testRetriever.setDataSource(fis.fd)
                    retriever = testRetriever
                    initialized = true
                } catch (e: Throwable) {
                    diagLog.append("FisInit: ").append(e.message ?: e.javaClass.simpleName).append("; ")
                }
            }

            // Попытка 4: Context + Uri
            if (!initialized) {
                try {
                    val testRetriever = MediaMetadataRetriever()
                    testRetriever.setDataSource(context, Uri.fromFile(videoFile))
                    retriever = testRetriever
                    initialized = true
                } catch (e: Throwable) {
                    diagLog.append("UriInit: ").append(e.message ?: e.javaClass.simpleName).append("; ")
                }
            }

            val mmr = retriever ?: return 0

            val durationStr = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            val durationMs = durationStr?.toLongOrNull() ?: 2000L
            val actualDurationMs = min(durationMs, maxDurationSec * 1000L).coerceAtLeast(300L)

            val frameIntervalMs = 1000L / fps
            val totalFrames = (actualDurationMs / frameIntervalMs).toInt().coerceIn(2, 60)

            encoder.setDelay(frameIntervalMs.toInt())

            var lastValid: Bitmap? = null

            for (i in 0 until totalFrames) {
                val timeUs = i * frameIntervalMs * 1000L
                var frame: Bitmap? = null

                try {
                    frame = mmr.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                } catch (ignored: Throwable) {}

                if (frame == null) {
                    try {
                        frame = mmr.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
                    } catch (ignored: Throwable) {}
                }

                if (frame == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1 && maxDimension > 0) {
                    try {
                        val videoWidth = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: maxDimension
                        val videoHeight = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: maxDimension
                        val aspect = videoHeight.toFloat() / videoWidth.toFloat()
                        val targetHeight = ((maxDimension * aspect).toInt() / 2) * 2
                        frame = mmr.getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, maxDimension, targetHeight)
                    } catch (ignored: Throwable) {}
                }

                if (frame == null) {
                    try {
                        frame = mmr.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_PREVIOUS_SYNC)
                    } catch (ignored: Throwable) {}
                }

                if (frame == null && i == 0) {
                    try {
                        frame = mmr.frameAtTime
                    } catch (ignored: Throwable) {}
                }

                val toUse = frame ?: lastValid
                if (toUse != null) {
                    val scaled = if (maxDimension > 0 && toUse.width > maxDimension) {
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
            try { retriever?.release() } catch (ignored: Throwable) {}
            try { pfd?.close() } catch (ignored: Throwable) {}
            try { fis?.close() } catch (ignored: Throwable) {}
        }

        return added
    }

    /**
     * Извлечение кадров через Glide с использованием RequestOptions.frameOf(timeUs).
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
            encoder.setDelay(frameIntervalMs.toInt())
            var lastValid: Bitmap? = null

            for (i in 0 until totalFrames) {
                val timeUs = i * frameIntervalMs * 1000L
                var frame: Bitmap? = null
                try {
                    frame = Glide.with(context.applicationContext)
                        .asBitmap()
                        .load(videoFile)
                        .apply(RequestOptions.frameOf(timeUs))
                        .submit(maxDimension, maxDimension)
                        .get()
                } catch (ignored: Throwable) {}

                if (frame == null && i == 0) {
                    try {
                        frame = Glide.with(context.applicationContext)
                            .asBitmap()
                            .load(videoFile)
                            .apply(RequestOptions.frameOf(0L))
                            .submit(maxDimension, maxDimension)
                            .get()
                    } catch (ignored: Throwable) {}
                }

                val toUse = frame ?: lastValid
                if (toUse != null) {
                    val scaled = if (maxDimension > 0 && toUse.width > maxDimension) {
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
        } catch (e: Throwable) {
            e.printStackTrace()
        }
        return added
    }
}
