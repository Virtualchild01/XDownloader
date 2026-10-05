package com.example.xdownloader.api

enum class XMediaType {
    VIDEO,
    PHOTO,
    GIF
}

data class XFormatOption(
    val id: String,
    val label: String,
    val extension: String,
    val downloadUrl: String,
    val bitrate: Long = 0,
    val isGifConversion: Boolean = false,
    val fileSizeFormatted: String = ""
)

data class XMediaItem(
    val type: XMediaType,
    val previewUrl: String,
    val availableFormats: List<XFormatOption>,
    val durationSeconds: Long = 0
)

data class XPostInfo(
    val tweetId: String,
    val authorName: String,
    val authorHandle: String,
    val tweetText: String,
    val mediaItems: List<XMediaItem>
)
