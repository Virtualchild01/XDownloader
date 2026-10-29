package com.example.xdownloader.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import java.io.File

/**
 * Открытие и отправка скачанных файлов наружу.
 *
 * На Android 10+ файл лежит в MediaStore и отдаётся как content:// — его можно
 * передавать как есть. На Android 7-9 файл лежит как file://, а такой путь
 * наружу отдавать запрещено (FileUriExposedException), поэтому он оборачивается
 * в content:// через FileProvider.
 */
object MediaSharer {

    /** Безопасный Uri для передачи в другое приложение. */
    fun shareableUri(context: Context, media: DownloadedMedia): Uri {
        val uri = media.uri
        if (uri.scheme != "file") return uri
        val path = uri.path ?: return uri
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", File(path))
    }

    /** Системный выбор «поделиться». @return false, если отправлять нечем */
    fun share(context: Context, media: DownloadedMedia, chooserTitle: String): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = media.mimeType.ifEmpty { "*/*" }
                putExtra(Intent.EXTRA_STREAM, shareableUri(context, media))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, chooserTitle))
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Открыть файл подходящим приложением. @return false, если открывать нечем */
    fun open(context: Context, media: DownloadedMedia): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(shareableUri(context, media), media.mimeType.ifEmpty { "*/*" })
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            false
        }
    }
}
