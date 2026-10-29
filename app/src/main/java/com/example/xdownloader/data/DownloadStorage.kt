package com.example.xdownloader.data

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import java.io.File
import java.io.FileInputStream

/**
 * Хранилище скачанного: всё лежит в одной публичной папке Movies/XDownloader.
 *
 * Почему через [MediaStore.Files], а не через коллекции Images/Video: у каждой
 * коллекции свой белый список верхних папок. Картинку (GIF) система разрешает
 * только в DCIM и Pictures, видео — в Movies, поэтому записать GIF в Movies
 * через MediaStore.Images нельзя (IllegalArgumentException). Коллекция Files
 * такого ограничения не имеет, файл при этом физически лежит в Movies/XDownloader
 * и виден и в галерее, и в Проводнике.
 */
object DownloadStorage {

    const val SUBDIR = "XDownloader"

    /** Публичная папка, в которую пишет приложение. */
    val relativePath: String get() = "${Environment.DIRECTORY_MOVIES}/$SUBDIR"

    private fun isScoped(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    // ---------------------------------------------------------------- запись

    /**
     * Кладёт [source] в Movies/XDownloader под именем [displayName].
     * @return Uri сохранённого файла или null при ошибке
     */
    fun save(context: Context, source: File, displayName: String): Uri? {
        val safeName = uniqueName(context, sanitizeName(displayName))
        val mime = mimeFromName(safeName)
        if (isScoped()) {
            // На части прошивок коллекция Files может отказать в записи в Movies —
            // тогда пробуем прямую запись, чтобы файл всё-таки сохранился.
            saveScoped(context, source, safeName, mime)?.let { return it }
        }
        return saveLegacy(context, source, safeName, mime)
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun saveScoped(context: Context, source: File, displayName: String, mime: String): Uri? {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }

        val uri = try {
            resolver.insert(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
        } catch (e: Exception) {
            null
        } ?: return null

        val copied = try {
            resolver.openOutputStream(uri)?.use { out ->
                FileInputStream(source).use { input -> input.copyTo(out, 1 shl 18) }
                out.flush()
            } != null
        } catch (e: Exception) {
            false
        }

        if (!copied) {
            runCatching { resolver.delete(uri, null, null) }
            return null
        }

        val published = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
        runCatching { resolver.update(uri, published, null, null) }
        return uri
    }

    private fun saveLegacy(context: Context, source: File, displayName: String, mime: String): Uri? {
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), SUBDIR)
        if (!dir.exists() && !dir.mkdirs()) return null

        val target = File(dir, displayName)
        return try {
            FileInputStream(source).use { input ->
                target.outputStream().use { output -> input.copyTo(output, 1 shl 18) }
            }
            MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), arrayOf(mime), null)
            Uri.fromFile(target)
        } catch (e: Exception) {
            null
        }
    }

    /** Не перезаписывает существующий файл: добавляет « (1)», « (2)» и т. д. */
    private fun uniqueName(context: Context, displayName: String): String {
        if (!exists(context, displayName)) return displayName
        val base = displayName.substringBeforeLast('.', displayName)
        val ext = displayName.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
        var counter = 1
        while (counter < 1000) {
            val candidate = "$base ($counter)$ext"
            if (!exists(context, candidate)) return candidate
            counter++
        }
        return displayName
    }

    private fun exists(context: Context, displayName: String): Boolean {
        if (!isScoped()) {
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), SUBDIR)
            return File(dir, displayName).exists()
        }
        val projection = arrayOf(MediaStore.MediaColumns._ID)
        val selection = "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? AND " +
            "${MediaStore.MediaColumns.DISPLAY_NAME} = ?"
        return try {
            context.contentResolver.query(
                MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                projection,
                selection,
                arrayOf("$relativePath%", displayName),
                null
            )?.use { it.count > 0 } ?: false
        } catch (e: Exception) {
            false
        }
    }

    // --------------------------------------------------------------- чтение

    fun list(context: Context): List<DownloadedMedia> =
        if (isScoped()) listScoped(context) else listLegacy()

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun listScoped(context: Context): List<DownloadedMedia> {
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.MIME_TYPE
        )
        val selection = "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"

        val result = mutableListOf<DownloadedMedia>()
        try {
            context.contentResolver.query(
                MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                projection,
                selection,
                arrayOf("$relativePath%"),
                "${MediaStore.MediaColumns.DATE_ADDED} DESC"
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameCol = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                val sizeCol = cursor.getColumnIndex(MediaStore.MediaColumns.SIZE)
                val dateCol = cursor.getColumnIndex(MediaStore.MediaColumns.DATE_ADDED)
                val mimeCol = cursor.getColumnIndex(MediaStore.MediaColumns.MIME_TYPE)

                while (cursor.moveToNext()) {
                    val name = if (nameCol >= 0) cursor.getString(nameCol) else null
                    if (name.isNullOrEmpty()) continue
                    val mime = if (mimeCol >= 0) cursor.getString(mimeCol) ?: "" else ""
                    // Папка может содержать служебные файлы (.nomedia и подобные)
                    if (mime.isEmpty() && !isMediaName(name)) continue
                    result += DownloadedMedia(
                        uri = ContentUris.withAppendedId(
                            MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                            cursor.getLong(idCol)
                        ),
                        displayName = name,
                        sizeBytes = if (sizeCol >= 0) cursor.getLong(sizeCol) else 0L,
                        dateAddedSec = if (dateCol >= 0) cursor.getLong(dateCol) else 0L,
                        mimeType = mime.ifEmpty { mimeFromName(name) }
                    )
                }
            }
        } catch (e: Exception) {
            // Пустая галерея лучше, чем падение приложения
        }
        return result
    }

    private fun listLegacy(): List<DownloadedMedia> {
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), SUBDIR)
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles()
            ?.filter { it.isFile && isMediaName(it.name) }
            ?.map { file ->
                DownloadedMedia(
                    uri = Uri.fromFile(file),
                    displayName = file.name,
                    sizeBytes = file.length(),
                    dateAddedSec = file.lastModified() / 1000L,
                    mimeType = mimeFromName(file.name)
                )
            }
            ?.sortedByDescending { it.dateAddedSec }
            ?: emptyList()
    }

    // ------------------------------------------------------------- удаление

    fun delete(context: Context, media: DownloadedMedia): Boolean {
        return try {
            if (media.uri.scheme == "content") {
                context.contentResolver.delete(media.uri, null, null) > 0
            } else {
                val path = media.uri.path ?: return false
                File(path).delete()
            }
        } catch (e: Exception) {
            false
        }
    }

    // -------------------------------------------------------------- утилиты

    private fun isMediaName(name: String): Boolean =
        name.substringAfterLast('.', "").lowercase() in
            setOf("mp4", "gif", "jpg", "jpeg", "png", "webp", "webm", "mov")

    /** Готовый медиафайл, а не .part/.tmp — по имени определяем, что уже скачано. */
    fun isMediaFile(name: String): Boolean = isMediaName(name)

    /**
     * MediaStore отвергает имена с разделителями пути и управляющими символами,
     * поэтому название поста приводится к безопасному виду.
     */
    fun sanitizeName(name: String): String {
        val cleaned = name
            .replace(Regex("""[\\/:*?"<>|\u0000-\u001F]"""), "_")
            .trim()
            .trimStart('.')
        return cleaned.ifEmpty { "X_${System.currentTimeMillis()}" }
    }

    fun mimeFromName(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "mp4", "m4v" -> "video/mp4"
        "webm" -> "video/webm"
        "mov" -> "video/quicktime"
        "gif" -> "image/gif"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "jpg", "jpeg" -> "image/jpeg"
        else -> "application/octet-stream"
    }
}
