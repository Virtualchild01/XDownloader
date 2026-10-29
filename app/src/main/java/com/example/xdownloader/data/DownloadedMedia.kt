package com.example.xdownloader.data

import android.net.Uri

/** Файл, скачанный приложением: видео из Movies/XDownloader, остальное из Pictures/XDownloader. */
data class DownloadedMedia(
    val uri: Uri,
    val displayName: String,
    val sizeBytes: Long,
    val dateAddedSec: Long,
    val mimeType: String
) {
    val isVideo: Boolean
        get() = mimeType.startsWith("video/")

    /** Расширение в верхнем регистре — для бейджа на карточке (GIF / MP4 / JPG). */
    val extensionLabel: String
        get() = displayName.substringAfterLast('.', "").uppercase()
}
