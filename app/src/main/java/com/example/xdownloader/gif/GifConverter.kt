package com.example.xdownloader.gif

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

object GifConverter {

    /**
     * Converts a local MP4 video file into an animated GIF using native FFmpeg.
     * Totally independent of Android's MediaMetadataRetriever or OEM codec bugs.
     * @return Pair of (Uri?, errorMessage?)
     */
    suspend fun convertVideoToGif(
        context: Context,
        videoFile: File,
        targetTitle: String,
        maxDurationSec: Int = 10,
        fps: Int = 12,
        maxDimension: Int = 360,
        onProgress: (Int) -> Unit
    ): Pair<Uri?, String?> = withContext(Dispatchers.IO) {
        if (!videoFile.exists() || !videoFile.canRead() || videoFile.length() < 1000L) {
            return@withContext Pair(null, "Видеофайл пуст или не прочитан (${videoFile.length()} байт)")
        }

        withContext(Dispatchers.Main) {
            onProgress(40)
        }

        val safeTitle = targetTitle
            .replace(Regex("[^a-zA-Z0-9а-яА-ЯёЁ._\\-\\s]"), "")
            .trim()
            .take(45)
            .ifEmpty { "X_Post" }
        val fileName = "${safeTitle}_anim.gif"

        val tempGifFile = File(context.cacheDir, "ffmpeg_temp_${System.currentTimeMillis()}.gif")

        try {
            // Запуск нативного FFmpeg: качественная 2-проходная палитра для чистого GIF без зернистости
            val ffmpegCmd = "-y -t $maxDurationSec -i \"${videoFile.absolutePath}\" -vf \"fps=$fps,scale=$maxDimension:-1:flags=lanczos,split[s0][s1];[s0]palettegen=max_colors=128[p];[s1][p]paletteuse=dither=bayer\" \"${tempGifFile.absolutePath}\""

            withContext(Dispatchers.Main) {
                onProgress(55)
            }

            val session = FFmpegKit.execute(ffmpegCmd)
            val returnCode = session.returnCode

            withContext(Dispatchers.Main) {
                onProgress(85)
            }

            if (ReturnCode.isSuccess(returnCode) && tempGifFile.exists() && tempGifFile.length() > 1000L) {
                val finalUri = saveGifToDownloads(context, tempGifFile, fileName)
                withContext(Dispatchers.Main) {
                    onProgress(100)
                }
                if (finalUri != null) {
                    return@withContext Pair(finalUri, null)
                } else {
                    return@withContext Pair(null, "Не удалось сохранить файл в Загрузки")
                }
            }

            // Запасной быстрый запуск FFmpeg (на случай редких фильтровых ограничений)
            val fallbackCmd = "-y -t $maxDurationSec -i \"${videoFile.absolutePath}\" -vf \"fps=$fps,scale=$maxDimension:-1\" \"${tempGifFile.absolutePath}\""
            val fallbackSession = FFmpegKit.execute(fallbackCmd)
            if (ReturnCode.isSuccess(fallbackSession.returnCode) && tempGifFile.exists() && tempGifFile.length() > 1000L) {
                val finalUri = saveGifToDownloads(context, tempGifFile, fileName)
                withContext(Dispatchers.Main) {
                    onProgress(100)
                }
                if (finalUri != null) {
                    return@withContext Pair(finalUri, null)
                }
            }

            val logs = session.allLogsAsString ?: "Код завершения: $returnCode"
            Pair(null, "Ошибка FFmpeg: ${logs.takeLast(100)}")
        } catch (e: Exception) {
            e.printStackTrace()
            Pair(null, e.message ?: "Сбой конвертера FFmpeg")
        } finally {
            if (tempGifFile.exists()) {
                tempGifFile.delete()
            }
        }
    }

    private fun saveGifToDownloads(context: Context, gifFile: File, fileName: String): Uri? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/gif")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        FileInputStream(gifFile).use { input ->
                            input.copyTo(out)
                        }
                    }
                }
                uri
            } else {
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!downloadsDir.exists()) downloadsDir.mkdirs()
                val targetFile = File(downloadsDir, fileName)
                FileInputStream(gifFile).use { input ->
                    FileOutputStream(targetFile).use { output ->
                        input.copyTo(output)
                    }
                }
                Uri.fromFile(targetFile)
            }
        } catch (_: Exception) {
            null
        }
    }
}
