package com.example.xdownloader.gif

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
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
        if (!videoFile.exists() || videoFile.length() < 1000L) {
            return@withContext Pair(null, "Видеофайл пуст или поврежден")
        }

        val retriever = MediaMetadataRetriever()
        var dataSourceSet = false
        var dataSourceError = ""

        // Способ 1: через абсолютный путь (самый надежный для файлов во внутреннем кэше приложения)
        try {
            retriever.setDataSource(videoFile.absolutePath)
            dataSourceSet = true
        } catch (e: Exception) {
            dataSourceError = e.message ?: "absolutePath failed"
        }

        // Способ 2: через дескриптор с явным указанием длины файла (для совместимости со специфичными прошивками)
        if (!dataSourceSet) {
            var fis: FileInputStream? = null
            try {
                fis = FileInputStream(videoFile)
                retriever.setDataSource(fis.fd, 0L, videoFile.length())
                dataSourceSet = true
            } catch (e: Exception) {
                dataSourceError += "; fd failed: ${e.message}"
            } finally {
                try { fis?.close() } catch (_: Throwable) {}
            }
        }

        if (!dataSourceSet) {
            try { retriever.release() } catch (_: Throwable) {}
            return@withContext Pair(null, "Не удалось открыть медиапоток ($dataSourceError)")
        }

        var outputUri: Uri? = null
        var outStream: OutputStream? = null
        var targetFile: File? = null

        try {
            val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            val widthStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
            val heightStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)

            val durationMs = durationStr?.toLongOrNull() ?: 3000L
            val actualDurationMs = min(durationMs, maxDurationSec * 1000L).coerceAtLeast(400L)

            val srcWidth = widthStr?.toIntOrNull() ?: 360
            val srcHeight = heightStr?.toIntOrNull() ?: 360

            // Сохраняем точные пропорции с четными размерами (для стабильности кодировщика)
            val (targetW, targetH) = if (srcWidth >= srcHeight) {
                val w = (min(srcWidth, maxDimension).coerceAtLeast(100) / 2) * 2
                val rawH = ((w.toLong() * srcHeight) / srcWidth).toInt().coerceAtLeast(100)
                val h = (rawH / 2) * 2
                Pair(w, h)
            } else {
                val h = (min(srcHeight, maxDimension).coerceAtLeast(100) / 2) * 2
                val rawW = ((h.toLong() * srcWidth) / srcHeight).toInt().coerceAtLeast(100)
                val w = (rawW / 2) * 2
                Pair(w, h)
            }

            val frameIntervalMs = 1000L / fps
            val totalFrames = (actualDurationMs / frameIntervalMs).toInt().coerceIn(2, 60)

            val safeTitle = targetTitle
                .replace(Regex("[^a-zA-Z0-9а-яА-ЯёЁ._\\-\\s]"), "")
                .trim()
                .take(45)
                .ifEmpty { "X_Post" }
            val fileName = "${safeTitle}_anim.gif"

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
                retriever.release()
                return@withContext Pair(null, "Не удалось создать файл в Загрузках")
            }

            val encoder = AnimatedGifEncoder()
            encoder.setSize(targetW, targetH)
            encoder.setDelay(frameIntervalMs.toInt())
            encoder.setRepeat(0) // 0 = бесконечный цикл
            encoder.setQuality(10)
            encoder.start(outStream)

            var framesAdded = 0
            var lastValidBitmap: Bitmap? = null

            for (i in 0 until totalFrames) {
                val timeUs = i * frameIntervalMs * 1000L
                var frame: Bitmap? = null

                // Вариант 1: Точный поиск кадра по таймкоду
                try {
                    frame = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
                } catch (_: Throwable) {}

                // Вариант 2: Стандартный поиск кадра
                if (frame == null) {
                    try {
                        frame = retriever.getFrameAtTime(timeUs)
                    } catch (_: Throwable) {}
                }

                // Вариант 3: Поиск ближайшего ключевого кадра
                if (frame == null) {
                    try {
                        frame = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    } catch (_: Throwable) {}
                }

                // Вариант 4: Первый доступный кадр для старта
                if (frame == null && i == 0) {
                    try {
                        frame = retriever.frameAtTime
                    } catch (_: Throwable) {}
                }

                val bitmapToUse = frame ?: lastValidBitmap

                if (bitmapToUse != null) {
                    val scaled = if (bitmapToUse.width == targetW && bitmapToUse.height == targetH) {
                        bitmapToUse
                    } else {
                        Bitmap.createScaledBitmap(bitmapToUse, targetW, targetH, true)
                    }

                    encoder.addFrame(scaled)
                    framesAdded++

                    if (frame != null && frame != lastValidBitmap) {
                        lastValidBitmap?.recycle()
                        lastValidBitmap = frame
                    }
                    if (scaled != bitmapToUse && scaled != lastValidBitmap) {
                        scaled.recycle()
                    }
                }

                val progress = ((i + 1) * 100) / totalFrames
                withContext(Dispatchers.Main) {
                    onProgress(progress)
                }
            }

            lastValidBitmap?.recycle()
            try { retriever.release() } catch (_: Throwable) {}

            if (framesAdded == 0) {
                try { outStream.close() } catch (_: Throwable) {}
                if (outputUri != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    try { context.contentResolver.delete(outputUri, null, null) } catch (_: Throwable) {}
                } else if (targetFile != null && targetFile.exists()) {
                    targetFile.delete()
                }
                return@withContext Pair(null, "Декодер не смог извлечь кадры (frames=0)")
            }

            encoder.finish()
            outStream.flush()
            outStream.close()

            // Снимаем флаг IS_PENDING на Android 10+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && outputUri != null) {
                val updateValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }
                context.contentResolver.update(outputUri, updateValues, null, null)
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
            try { retriever.release() } catch (_: Throwable) {}
            Pair(null, e.message ?: "Сбой при конвертации")
        }
    }
}
