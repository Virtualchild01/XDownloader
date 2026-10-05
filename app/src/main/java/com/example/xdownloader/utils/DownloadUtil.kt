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
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
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
        }

        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        return downloadManager.enqueue(request)
    }

    /**
     * Downloads video stream and converts it into a pure animated GIF file.
     */
    suspend fun downloadAndConvertToGif(
        context: Context,
        videoUrl: String,
        title: String,
        onStatusUpdate: (String) -> Unit,
        onProgress: (Int) -> Unit
    ): Uri? = withContext(Dispatchers.IO) {
        val tempVideoFile = File(context.cacheDir, "temp_x_${System.currentTimeMillis()}.mp4")
        try {
            withContext(Dispatchers.Main) {
                onStatusUpdate("Загрузка видеопотока...")
                onProgress(5)
            }

            // Download MP4 to cache
            val request = Request.Builder().url(videoUrl).build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw Exception("Ошибка загрузки видео: ${response.code}")
                val body = response.body ?: throw Exception("Пустой ответ сервера")
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
                                val downloadPercent = (downloaded * 30 / totalBytes).toInt()
                                withContext(Dispatchers.Main) {
                                    onProgress(downloadPercent)
                                }
                            }
                        }
                    }
                }
            }

            withContext(Dispatchers.Main) {
                onStatusUpdate("Создание анимированного GIF...")
                onProgress(35)
            }

            // Convert to GIF
            val gifUri = GifConverter.convertVideoToGif(
                context = context,
                videoFile = tempVideoFile,
                targetTitle = "X_${sanitizeFilename(title)}",
                maxDurationSec = 10,
                fps = 8,
                targetWidth = 400
            ) { gifPercent ->
                // Progress from 35% to 100%
                val totalProgress = 35 + (gifPercent * 65 / 100)
                onProgress(totalProgress)
            }

            gifUri
        } catch (e: Exception) {
            e.printStackTrace()
            null
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
