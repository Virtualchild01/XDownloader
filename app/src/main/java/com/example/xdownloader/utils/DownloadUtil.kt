package com.example.xdownloader.utils

import android.app.DownloadManager
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.example.xdownloader.gif.GifConverter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

object DownloadUtil {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    /**
     * Enqueues standard file download via Android DownloadManager.
     */
    fun enqueueDownload(
        context: Context,
        url: String,
        title: String,
        formatLabel: String,
        extension: String
    ): Long {
        val sanitizedTitle = sanitizeFilename(title)
        val ext = if (extension.startsWith(".")) extension else ".$extension"
        val fileName = "X_${sanitizedTitle}_${formatLabel.replace(" ", "_").replace("(", "").replace(")", "")}$ext"

        val request = DownloadManager.Request(Uri.parse(url)).apply {
            setTitle("XDownloader: $title")
            setDescription("Скачивание $formatLabel...")
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            setAllowedOverMetered(true)
            setAllowedOverRoaming(true)
            addRequestHeader(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
            )
        }

        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        return downloadManager.enqueue(request)
    }

    /**
     * Downloads video stream and converts it into a pure animated GIF file.
     * If GIF conversion fails, gracefully saves original stream as MP4 into Downloads.
     * @return Pair of (Uri?, statusMessage?)
     */
    suspend fun downloadAndConvertToGif(
        context: Context,
        videoUrl: String,
        title: String,
        onStatusUpdate: (String) -> Unit,
        onProgress: (Int) -> Unit
    ): Pair<Uri?, String?> = withContext(Dispatchers.IO) {
        val tempVideoFile = File(context.cacheDir, "temp_x_${System.currentTimeMillis()}.mp4")
        try {
            withContext(Dispatchers.Main) {
                onStatusUpdate("Загрузка видеопотока...")
                onProgress(5)
            }

            val request = Request.Builder()
                .url(videoUrl)
                .header(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
                )
                .header("Accept", "*/*")
                .build()

            var downloadError: String? = null

            try {
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        downloadError = "HTTP ${response.code}"
                    } else {
                        val body = response.body
                        if (body == null) {
                            downloadError = "Пустой ответ сервера"
                        } else {
                            val totalBytes = body.contentLength()
                            body.byteStream().use { input ->
                                FileOutputStream(tempVideoFile).use { output ->
                                    val buffer = ByteArray(8192)
                                    var bytesRead: Int
                                    var downloaded = 0L

                                    while (input.read(buffer).also { bytesRead = it } != -1) {
                                        output.write(buffer, 0, bytesRead)
                                        downloaded += bytesRead
                                        if (totalBytes > 0) {
                                            val downloadPercent = (downloaded * 30 / totalBytes).toInt().coerceIn(5, 30)
                                            withContext(Dispatchers.Main) {
                                                onProgress(downloadPercent)
                                            }
                                        }
                                    }
                                    output.flush()
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                downloadError = e.message ?: "Сбой соединения"
            }

            if (downloadError != null || !tempVideoFile.exists() || tempVideoFile.length() < 1000L) {
                val err = downloadError ?: "Файл не скачался"
                return@withContext Pair(null, "Ошибка загрузки ($err)")
            }

            withContext(Dispatchers.Main) {
                onStatusUpdate("Конвертация в анимацию GIF...")
                onProgress(35)
            }

            val (gifUri, gifErr) = GifConverter.convertVideoToGif(
                context = context,
                videoFile = tempVideoFile,
                targetTitle = "X_${sanitizeFilename(title)}",
                maxDurationSec = 10,
                fps = 10,
                maxDimension = 320
            ) { gifPercent ->
                val totalProgress = 35 + (gifPercent * 65 / 100)
                onProgress(totalProgress)
            }

            if (gifUri != null) {
                return@withContext Pair(gifUri, null)
            }

            // РЕЗЕРВ: Если декодер устройства не смог создать GIF, сохраняем оригинальное видео в Загрузки
            val fallbackMp4Uri = saveVideoToDownloads(context, tempVideoFile, "X_${sanitizeFilename(title)}_anim")
            if (fallbackMp4Uri != null) {
                return@withContext Pair(fallbackMp4Uri, "SAVED_AS_MP4")
            }

            Pair(null, gifErr ?: "Не удалось создать анимацию")
        } catch (e: Exception) {
            e.printStackTrace()
            Pair(null, e.message ?: "Неизвестная ошибка")
        } finally {
            if (tempVideoFile.exists()) {
                tempVideoFile.delete()
            }
        }
    }

    private fun saveVideoToDownloads(context: Context, videoFile: File, title: String): Uri? {
        return try {
            val fileName = "${title}.mp4"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        FileInputStream(videoFile).use { input ->
                            input.copyTo(out)
                        }
                    }
                }
                uri
            } else {
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!dir.exists()) dir.mkdirs()
                val target = File(dir, fileName)
                FileInputStream(videoFile).use { input ->
                    FileOutputStream(target).use { output ->
                        input.copyTo(output)
                    }
                }
                Uri.fromFile(target)
            }
        } catch (_: Exception) {
            null
        }
    }

    fun sanitizeFilename(name: String): String {
        return name.replace(Regex("[^a-zA-Z0-9а-яА-ЯёЁ._\\-\\s]"), "")
            .trim()
            .take(45)
            .ifEmpty { "Post" }
    }
}
