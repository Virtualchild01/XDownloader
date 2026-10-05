package com.example.xdownloader.utils

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import com.example.xdownloader.gif.GifConverter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
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
     * @return Pair of (Uri?, errorMessage?)
     */
    suspend fun downloadAndConvertToGif(
        context: Context,
        videoUrl: String,
        title: String,
        onStatusUpdate: (String) -> Unit,
        onProgress: (Int) -> Unit
    ): Pair<Uri?, String?> = withContext(Dispatchers.IO) {
        // Сохраняем в getExternalFilesDir, где у системного mediaserver есть доступ на чтение
        val baseDir = context.getExternalFilesDir(null) ?: context.cacheDir
        val tempVideoFile = File(baseDir, "temp_x_${System.currentTimeMillis()}.mp4")
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

            Pair(gifUri, gifErr)
        } catch (e: Exception) {
            e.printStackTrace()
            Pair(null, e.message ?: "Неизвестная ошибка")
        } finally {
            if (tempVideoFile.exists()) {
                tempVideoFile.delete()
            }
        }
    }

    fun sanitizeFilename(name: String): String {
        return name.replace(Regex("[^a-zA-Z0-9а-яА-ЯёЁ._\\-\\s]"), "")
            .trim()
            .take(45)
            .ifEmpty { "Post" }
    }
}
