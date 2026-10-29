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
 * Хранилище скачанного.
 *
 * Куда именно можно писать, задаёт не приложение, а платформа. В AOSP
 * (MediaProvider.ensureFileColumns) у каждой коллекции MediaStore свой белый
 * список верхних папок:
 *  - Files   → только Downloads и Documents;
 *  - Images  → только DCIM и Pictures;
 *  - Video   → DCIM, Movies и Pictures.
 * Поэтому картинку (GIF, JPG) физически нельзя положить в Movies на Android 10+:
 * запрос отклоняется с "Primary directory Movies not allowed". Раньше здесь
 * использовалась коллекция Files с путём Movies/... — она принимает только
 * Downloads и Documents, из-за чего сохранение GIF падало всегда.
 *
 * Отсюда раскладка по типу файла:
 *  - видео → Movies/XDownloader;
 *  - фото и GIF → Pictures/XDownloader.
 * Обе папки публичные: видны в системной галерее, в Проводнике и во встроенной
 * галерее приложения. На Android 9 и ниже ограничений нет, и всё пишется
 * напрямую в те же папки.
 */
object DownloadStorage {

    const val SUBDIR = "XDownloader"

    // Константы Environment — это просто строки, но в JVM-тестах они заглушены и
    // возвращают null, поэтому пути собираются из литералов.
    private const val DIR_MOVIES = "Movies"
    private const val DIR_PICTURES = "Pictures"
    private const val DIR_DOWNLOADS = "Download"

    /** Публичная папка для видео. */
    val videoPath: String get() = "$DIR_MOVIES/$SUBDIR"

    /** Публичная папка для изображений и GIF: в Movies платформа их не пускает. */
    val imagePath: String get() = "$DIR_PICTURES/$SUBDIR"

    /** Где версии до 1.0.28 складывали готовые GIF. */
    private val legacyDownloadsPath: String get() = "$DIR_DOWNLOADS/$SUBDIR"

    private fun isScoped(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    /**
     * Папка под конкретный тип файла. Видео — Movies, всё остальное — Pictures.
     *
     * Отдельная функция (а не инлайн-ветка) ради теста: именно неверный выбор
     * коллекции/папки был причиной ошибки «не удалось сохранить GIF».
     */
    internal fun relativePathFor(mime: String): String =
        if (mime.startsWith("video/")) videoPath else imagePath

    // ---------------------------------------------------------------- запись

    /**
     * Кладёт [source] в публичную папку приложения: видео — в Movies/XDownloader,
     * изображения и GIF — в Pictures/XDownloader.
     *
     * @return Uri сохранённого файла или null, если записать не удалось
     */
    fun save(context: Context, source: File, displayName: String): Uri? {
        val mime = mimeFromName(displayName)
        val safeName = uniqueName(context, sanitizeName(displayName), mime)
        return if (isScoped()) {
            saveScoped(context, source, safeName, mime)
        } else {
            saveLegacy(context, source, safeName, mime)
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun saveScoped(context: Context, source: File, displayName: String, mime: String): Uri? {
        val resolver = context.contentResolver
        val collection = if (mime.startsWith("video/")) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePathFor(mime))
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }

        val uri = try {
            resolver.insert(collection, values)
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
        val base = if (mime.startsWith("video/")) DIR_MOVIES else DIR_PICTURES
        val dir = File(Environment.getExternalStoragePublicDirectory(base), SUBDIR)
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
    private fun uniqueName(context: Context, displayName: String, mime: String): String {
        if (!exists(context, displayName, mime)) return displayName
        val base = displayName.substringBeforeLast('.', displayName)
        val ext = displayName.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
        var counter = 1
        while (counter < 1000) {
            val candidate = "$base ($counter)$ext"
            if (!exists(context, candidate, mime)) return candidate
            counter++
        }
        return displayName
    }

    private fun exists(context: Context, displayName: String, mime: String): Boolean {
        if (!isScoped()) {
            val base = if (mime.startsWith("video/")) DIR_MOVIES else DIR_PICTURES
            val dir = File(Environment.getExternalStoragePublicDirectory(base), SUBDIR)
            return File(dir, displayName).exists()
        }
        val collection = if (mime.startsWith("video/")) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }
        val projection = arrayOf(MediaStore.MediaColumns._ID)
        val selection = "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? AND " +
            "${MediaStore.MediaColumns.DISPLAY_NAME} = ?"
        return try {
            context.contentResolver.query(
                collection,
                projection,
                selection,
                arrayOf("${relativePathFor(mime)}%", displayName),
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
        // Основной источник — правильные коллекции: Video для видео, Images для
        // картинок и GIF. Так у файлов гарантированно верный MIME.
        val videos = queryScoped(
            context,
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            videoPath
        )
        val images = queryScoped(
            context,
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            imagePath
        )

        // Дополнительно смотрим коллекцию Files по обеим папкам приложения.
        // DownloadManager регистрирует скачанное в своей коллекции, и файл,
        // физически лежащий в Movies/XDownloader, мог не попасть в выборку Video.
        // Files видит все файлы сразу, поэтому ничего не теряется.
        val files = queryScoped(context, MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), videoPath) +
            queryScoped(context, MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), imagePath)

        // Файлы версий до 1.0.28 лежали в Download/XDownloader — показываем их,
        // чтобы галерея не опустела после обновления.
        val legacy = queryScoped(
            context,
            MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            legacyDownloadsPath
        )

        // Один и тот же файл может прийти из двух коллекций: разные content:// Uri,
        // но одно имя в одной папке. Ключ — имя, последняя запись побеждает.
        return (videos + images + files + legacy)
            .associateBy { it.displayName }
            .values
            .sortedByDescending { it.dateAddedSec }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun queryScoped(context: Context, collection: Uri, pathPrefix: String): List<DownloadedMedia> {
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
                collection,
                projection,
                selection,
                arrayOf("$pathPrefix%"),
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
                    if (!isMediaName(name)) continue
                    result += DownloadedMedia(
                        uri = ContentUris.withAppendedId(collection, cursor.getLong(idCol)),
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
        val dirs = listOf(
            File(Environment.getExternalStoragePublicDirectory(DIR_MOVIES), SUBDIR),
            File(Environment.getExternalStoragePublicDirectory(DIR_PICTURES), SUBDIR),
            File(Environment.getExternalStoragePublicDirectory(DIR_DOWNLOADS), SUBDIR)
        )

        return dirs
            .filter { it.isDirectory }
            .flatMap { dir ->
                dir.listFiles()
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
                    ?: emptyList()
            }
            .sortedByDescending { it.dateAddedSec }
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
