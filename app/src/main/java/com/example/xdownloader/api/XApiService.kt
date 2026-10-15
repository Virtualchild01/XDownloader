package com.example.xdownloader.api

import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern
import kotlin.math.max
import kotlin.math.min

class XApiService {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val gson = Gson()
    private val urlDimPattern = Pattern.compile("/(\\d{2,4})x(\\d{2,4})/")

    fun extractTweetId(url: String): String? {
        val pattern = Pattern.compile(
            "(?:https?://)?(?:www\\.|mobile\\.)?(?:twitter\\.com|x\\.com|vxtwitter\\.com|fxtwitter\\.com)/.*?/status/(\\d+)",
            Pattern.CASE_INSENSITIVE
        )
        val matcher = pattern.matcher(url)
        return if (matcher.find()) {
            matcher.group(1)
        } else {
            val shortPattern = Pattern.compile("(?:status/)(\\d+)", Pattern.CASE_INSENSITIVE)
            val shortMatcher = shortPattern.matcher(url)
            if (shortMatcher.find()) shortMatcher.group(1) else null
        }
    }

    /**
     * Извлекает разрешение (ширина x высота) из URL видео/фото или JSON-метаданных поста.
     */
    private fun extractDimensions(mObj: JsonObject?, url: String): Pair<Int, Int>? {
        if (url.isNotEmpty()) {
            val m = urlDimPattern.matcher(url)
            if (m.find()) {
                val w = m.group(1)?.toIntOrNull() ?: 0
                val h = m.group(2)?.toIntOrNull() ?: 0
                if (w > 0 && h > 0) return Pair(w, h)
            }
        }
        if (mObj != null) {
            try {
                if (mObj.has("size") && mObj.get("size").isJsonObject) {
                    val s = mObj.getAsJsonObject("size")
                    val w = s.get("width")?.asInt ?: 0
                    val h = s.get("height")?.asInt ?: 0
                    if (w > 0 && h > 0) return Pair(w, h)
                }
                if (mObj.has("original_info") && mObj.get("original_info").isJsonObject) {
                    val o = mObj.getAsJsonObject("original_info")
                    val w = o.get("width")?.asInt ?: 0
                    val h = o.get("height")?.asInt ?: 0
                    if (w > 0 && h > 0) return Pair(w, h)
                }
                val w = mObj.get("width")?.asInt ?: 0
                val h = mObj.get("height")?.asInt ?: 0
                if (w > 0 && h > 0) return Pair(w, h)
            } catch (ignored: Exception) {}
        }
        return null
    }

    /**
     * Вычисляет пропорциональное разрешение для режима HD 720p (короткая сторона = 720px, длинная <= 1280px).
     */
    private fun compute720pDimensions(origW: Int, origH: Int): Pair<Int, Int> {
        val minSide = min(origW, origH)
        val maxSide = max(origW, origH)
        if (minSide <= 720 && maxSide <= 1280) return Pair(origW, origH)
        val scale = min(720f / minSide.toFloat(), 1280f / maxSide.toFloat()).coerceAtMost(1.0f)
        val newW = (((origW * scale).toInt() + 1) / 2 * 2).coerceAtLeast(2)
        val newH = (((origH * scale).toInt() + 1) / 2 * 2).coerceAtLeast(2)
        return Pair(newW, newH)
    }

    private fun buildVariantQualityLabel(index: Int, bitrate: Long, dims: Pair<Int, Int>?): String {
        if (index == 0) {
            return if (dims != null) {
                "MP4 Видео (Оригинал • ${dims.first}×${dims.second})"
            } else {
                "MP4 Видео (Оригинал)"
            }
        }
        if (dims != null) {
            val shortSide = min(dims.first, dims.second)
            val tier = when {
                shortSide >= 1080 -> "Full HD 1080p"
                shortSide >= 720 -> "HD 720p"
                shortSide >= 480 -> "SD 480p"
                else -> "${shortSide}p"
            }
            return "MP4 Видео ($tier • ${dims.first}×${dims.second})"
        }
        return when {
            bitrate > 1_500_000 -> "MP4 Видео (Full HD 1080p)"
            bitrate > 700_000 -> "MP4 Видео (HD 720p)"
            bitrate > 350_000 -> "MP4 Видео (SD 480p)"
            bitrate > 0 -> "MP4 Видео (360p)"
            else -> "MP4 Видео (Вариант ${index + 1})"
        }
    }

    suspend fun getPostInfo(tweetId: String): Result<XPostInfo> = withContext(Dispatchers.IO) {
        try {
            val vxtResult = fetchFromVxTwitter(tweetId)
            if (vxtResult != null && vxtResult.mediaItems.isNotEmpty()) {
                return@withContext Result.success(vxtResult)
            }

            val syndicationResult = fetchFromSyndication(tweetId)
            if (syndicationResult != null && syndicationResult.mediaItems.isNotEmpty()) {
                return@withContext Result.success(syndicationResult)
            }

            val fxtResult = fetchFromFxTwitter(tweetId)
            if (fxtResult != null && fxtResult.mediaItems.isNotEmpty()) {
                return@withContext Result.success(fxtResult)
            }

            Result.failure(Exception("Медиафайлы (видео, фото или GIF) в этом посте не найдены или пост приватный."))
        } catch (e: Exception) {
            e.printStackTrace()
            Result.failure(e)
        }
    }

    private fun fetchFromVxTwitter(tweetId: String): XPostInfo? {
        val endpoint = "https://api.vxtwitter.com/Twitter/status/$tweetId"
        val request = Request.Builder()
            .url(endpoint)
            .header("User-Agent", "XDownloaderAndroid/1.0")
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string() ?: return null
                val json = gson.fromJson(body, JsonObject::class.java)

                parseVxTwitterJson(tweetId, json)
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun fetchFromFxTwitter(tweetId: String): XPostInfo? {
        val endpoint = "https://api.fxtwitter.com/Twitter/status/$tweetId"
        val request = Request.Builder()
            .url(endpoint)
            .header("User-Agent", "XDownloaderAndroid/1.0")
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string() ?: return null
                val json = gson.fromJson(body, JsonObject::class.java)

                if (json.has("tweet")) {
                    parseVxTwitterJson(tweetId, json.getAsJsonObject("tweet"))
                } else {
                    parseVxTwitterJson(tweetId, json)
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun parseVxTwitterJson(tweetId: String, json: JsonObject): XPostInfo? {
        val authorName = json.get("user_name")?.asString ?: "Пользователь X"
        val authorHandle = "@" + (json.get("user_screen_name")?.asString ?: "x_user")
        val tweetText = json.get("text")?.asString ?: ""

        val mediaList = mutableListOf<XMediaItem>()

        if (json.has("media_extended") && json.get("media_extended").isJsonArray) {
            val mediaArray = json.getAsJsonArray("media_extended")
            for (elem in mediaArray) {
                if (!elem.isJsonObject) continue
                val mObj = elem.asJsonObject
                val mTypeStr = mObj.get("type")?.asString?.lowercase() ?: "photo"
                val previewUrl = mObj.get("thumbnail_url")?.asString
                    ?: mObj.get("url")?.asString ?: ""

                val durationMs = mObj.get("duration_millis")?.asLong ?: 0L
                val durationSec = durationMs / 1000L

                val formats = mutableListOf<XFormatOption>()

                when (mTypeStr) {
                    "video", "gif" -> {
                        val isNativeGif = mTypeStr == "gif"
                        var bestMp4Url = mObj.get("url")?.asString ?: ""

                        if (mObj.has("variants") && mObj.get("variants").isJsonArray) {
                            val varArray = mObj.getAsJsonArray("variants")
                            val mp4Variants = mutableListOf<Triple<Long, String, String>>()

                            for (v in varArray) {
                                if (!v.isJsonObject) continue
                                val vObj = v.asJsonObject
                                val contentType = vObj.get("content_type")?.asString ?: ""
                                val vUrl = vObj.get("url")?.asString ?: continue
                                val bitrate = vObj.get("bitrate")?.asLong ?: 0L

                                if (contentType.contains("mp4") || vUrl.contains(".mp4")) {
                                    mp4Variants.add(Triple(bitrate, vUrl, contentType))
                                }
                            }

                            mp4Variants.sortByDescending { it.first }

                            if (mp4Variants.isNotEmpty()) {
                                bestMp4Url = mp4Variants.first().second
                            }

                            if (!isNativeGif) {
                                val seenLabels = mutableSetOf<String>()
                                for ((index, item) in mp4Variants.withIndex()) {
                                    val bitrate = item.first
                                    val vUrl = item.second
                                    val dims = if (index == 0) extractDimensions(mObj, vUrl) else extractDimensions(null, vUrl)
                                    val qualityLabel = buildVariantQualityLabel(index, bitrate, dims)
                                    if (seenLabels.add(qualityLabel)) {
                                        formats.add(
                                            XFormatOption(
                                                id = "mp4_$index",
                                                label = qualityLabel,
                                                extension = "mp4",
                                                downloadUrl = vUrl,
                                                bitrate = bitrate,
                                                isGifConversion = false,
                                                durationMs = durationMs
                                            )
                                        )
                                    }
                                }
                            }
                        } else if (!isNativeGif && bestMp4Url.isNotEmpty()) {
                            val origDims = extractDimensions(mObj, bestMp4Url)
                            val mp4Label = if (origDims != null) {
                                "MP4 Видео (Оригинал • ${origDims.first}×${origDims.second})"
                            } else {
                                "MP4 Видео (Оригинал)"
                            }
                            formats.add(
                                XFormatOption(
                                    id = "mp4_main",
                                    label = mp4Label,
                                    extension = "mp4",
                                    downloadUrl = bestMp4Url,
                                    isGifConversion = false,
                                    durationMs = durationMs
                                )
                            )
                        }

                        val origDims = extractDimensions(mObj, bestMp4Url)
                        if (isNativeGif) {
                            val gifLabel = if (origDims != null) {
                                "GIF (Оригинал • ${origDims.first}×${origDims.second})"
                            } else {
                                "GIF (Оригинал)"
                            }
                            val mp4Label = if (origDims != null) {
                                "MP4 Видео (Без конвертации • ${origDims.first}×${origDims.second})"
                            } else {
                                "MP4 Видео (Без конвертации)"
                            }
                            formats.add(
                                XFormatOption(
                                    id = "gif_format",
                                    label = gifLabel,
                                    extension = "gif",
                                    downloadUrl = bestMp4Url,
                                    isGifConversion = true,
                                    durationMs = durationMs,
                                    targetGifMaxDim = 0
                                )
                            )
                            formats.add(
                                XFormatOption(
                                    id = "mp4_gif_fast",
                                    label = mp4Label,
                                    extension = "mp4",
                                    downloadUrl = bestMp4Url,
                                    isGifConversion = false,
                                    durationMs = durationMs
                                )
                            )
                        } else if (bestMp4Url.isNotEmpty()) {
                            val origGifLabel = if (origDims != null) {
                                "GIF (Оригинал • ${origDims.first}×${origDims.second})"
                            } else {
                                "GIF (Оригинал)"
                            }
                            formats.add(
                                XFormatOption(
                                    id = "gif_format_orig",
                                    label = origGifLabel,
                                    extension = "gif",
                                    downloadUrl = bestMp4Url,
                                    isGifConversion = true,
                                    durationMs = durationMs,
                                    targetGifMaxDim = 0
                                )
                            )

                            val needs720pOption = origDims == null ||
                                min(origDims.first, origDims.second) > 720 ||
                                max(origDims.first, origDims.second) > 1280

                            if (needs720pOption) {
                                val hdGifLabel = if (origDims != null) {
                                    val hdDims = compute720pDimensions(origDims.first, origDims.second)
                                    "GIF (HD 720p • ${hdDims.first}×${hdDims.second})"
                                } else {
                                    "GIF (HD 720p • компактный)"
                                }
                                formats.add(
                                    XFormatOption(
                                        id = "gif_format_fast",
                                        label = hdGifLabel,
                                        extension = "gif",
                                        downloadUrl = bestMp4Url,
                                        isGifConversion = true,
                                        durationMs = durationMs,
                                        targetGifMaxDim = 720
                                    )
                                )
                            }
                        }

                        mediaList.add(
                            XMediaItem(
                                type = if (isNativeGif) XMediaType.GIF else XMediaType.VIDEO,
                                previewUrl = previewUrl,
                                availableFormats = formats,
                                durationSeconds = durationSec,
                                durationMs = durationMs
                            )
                        )
                    }
                    else -> {
                        val photoUrl = mObj.get("url")?.asString ?: previewUrl
                        val origUrl = if (photoUrl.contains("twimg.com")) {
                            if (photoUrl.contains("?")) "${photoUrl.substringBefore("?")}?name=orig" else "$photoUrl?name=orig"
                        } else photoUrl

                        val photoDims = extractDimensions(mObj, photoUrl)
                        val photoLabel = if (photoDims != null) {
                            "JPG Фото (Оригинал • ${photoDims.first}×${photoDims.second})"
                        } else {
                            "JPG Фото (Оригинал)"
                        }

                        formats.add(
                            XFormatOption(
                                id = "photo_orig",
                                label = photoLabel,
                                extension = "jpg",
                                downloadUrl = origUrl
                            )
                        )

                        mediaList.add(
                            XMediaItem(
                                type = XMediaType.PHOTO,
                                previewUrl = photoUrl,
                                availableFormats = formats
                            )
                        )
                    }
                }
            }
        }

        return if (mediaList.isNotEmpty()) {
            XPostInfo(tweetId, authorName, authorHandle, tweetText, mediaList)
        } else null
    }

    private fun fetchFromSyndication(tweetId: String): XPostInfo? {
        val endpoint = "https://cdn.syndication.twimg.com/tweet-result?id=$tweetId&lang=en"
        val request = Request.Builder()
            .url(endpoint)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36")
            .header("Accept", "application/json")
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body?.string() ?: return null
                val json = gson.fromJson(body, JsonObject::class.java)

                val userObj = json.getAsJsonObject("user")
                val authorName = userObj?.get("name")?.asString ?: "Пользователь X"
                val authorHandle = "@" + (userObj?.get("screen_name")?.asString ?: "x_user")
                val tweetText = json.get("text")?.asString ?: ""

                val mediaList = mutableListOf<XMediaItem>()

                if (json.has("mediaDetails") && json.get("mediaDetails").isJsonArray) {
                    val mediaArray = json.getAsJsonArray("mediaDetails")
                    for (elem in mediaArray) {
                        if (!elem.isJsonObject) continue
                        val mObj = elem.asJsonObject
                        val mType = mObj.get("type")?.asString ?: "photo"
                        val previewUrl = mObj.get("media_url_https")?.asString ?: ""

                        val formats = mutableListOf<XFormatOption>()

                        if (mType == "video" || mType == "animated_gif") {
                            val isGif = mType == "animated_gif"
                            var bestUrl = ""
                            var durationMs = 0L

                            if (mObj.has("video_info") && mObj.get("video_info").isJsonObject) {
                                val videoInfo = mObj.getAsJsonObject("video_info")
                                durationMs = videoInfo.get("duration_millis")?.asLong ?: 0L

                                if (videoInfo.has("variants") && videoInfo.get("variants").isJsonArray) {
                                    val variants = videoInfo.getAsJsonArray("variants")
                                    val list = mutableListOf<Pair<Long, String>>()

                                    for (v in variants) {
                                        if (!v.isJsonObject) continue
                                        val vObj = v.asJsonObject
                                        val cType = vObj.get("content_type")?.asString ?: ""
                                        val vUrl = vObj.get("url")?.asString ?: continue
                                        val bitrate = vObj.get("bitrate")?.asLong ?: 0L

                                        if (cType.contains("mp4") || vUrl.contains(".mp4")) {
                                            list.add(Pair(bitrate, vUrl))
                                        }
                                    }
                                    list.sortByDescending { it.first }

                                    if (list.isNotEmpty()) {
                                        bestUrl = list.first().second
                                    }

                                    if (!isGif) {
                                        val seenLabels = mutableSetOf<String>()
                                        for ((idx, item) in list.withIndex()) {
                                            val dims = if (idx == 0) extractDimensions(mObj, item.second) else extractDimensions(null, item.second)
                                            val label = buildVariantQualityLabel(idx, item.first, dims)
                                            if (seenLabels.add(label)) {
                                                formats.add(
                                                    XFormatOption(
                                                        id = "mp4_$idx",
                                                        label = label,
                                                        extension = "mp4",
                                                        downloadUrl = item.second,
                                                        bitrate = item.first,
                                                        isGifConversion = false,
                                                        durationMs = durationMs
                                                    )
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            val origDims = extractDimensions(mObj, bestUrl)
                            if (bestUrl.isNotEmpty()) {
                                if (isGif) {
                                    val gifLabel = if (origDims != null) {
                                        "GIF (Оригинал • ${origDims.first}×${origDims.second})"
                                    } else {
                                        "GIF (Оригинал)"
                                    }
                                    val mp4Label = if (origDims != null) {
                                        "MP4 Видео (Без конвертации • ${origDims.first}×${origDims.second})"
                                    } else {
                                        "MP4 Видео (Без конвертации)"
                                    }
                                    formats.add(
                                        XFormatOption(
                                            id = "gif_format",
                                            label = gifLabel,
                                            extension = "gif",
                                            downloadUrl = bestUrl,
                                            isGifConversion = true,
                                            durationMs = durationMs,
                                            targetGifMaxDim = 0
                                        )
                                    )
                                    formats.add(
                                        XFormatOption(
                                            id = "mp4_gif_fast",
                                            label = mp4Label,
                                            extension = "mp4",
                                            downloadUrl = bestUrl,
                                            isGifConversion = false,
                                            durationMs = durationMs
                                        )
                                    )
                                } else {
                                    val origGifLabel = if (origDims != null) {
                                        "GIF (Оригинал • ${origDims.first}×${origDims.second})"
                                    } else {
                                        "GIF (Оригинал)"
                                    }
                                    formats.add(
                                        XFormatOption(
                                            id = "gif_format_orig",
                                            label = origGifLabel,
                                            extension = "gif",
                                            downloadUrl = bestUrl,
                                            isGifConversion = true,
                                            durationMs = durationMs,
                                            targetGifMaxDim = 0
                                        )
                                    )

                                    val needs720pOption = origDims == null ||
                                        min(origDims.first, origDims.second) > 720 ||
                                        max(origDims.first, origDims.second) > 1280

                                    if (needs720pOption) {
                                        val hdGifLabel = if (origDims != null) {
                                            val hdDims = compute720pDimensions(origDims.first, origDims.second)
                                            "GIF (HD 720p • ${hdDims.first}×${hdDims.second})"
                                        } else {
                                            "GIF (HD 720p • компактный)"
                                        }
                                        formats.add(
                                            XFormatOption(
                                                id = "gif_format_fast",
                                                label = hdGifLabel,
                                                extension = "gif",
                                                downloadUrl = bestUrl,
                                                isGifConversion = true,
                                                durationMs = durationMs,
                                                targetGifMaxDim = 720
                                            )
                                        )
                                    }
                                }
                            }

                            mediaList.add(
                                XMediaItem(
                                    type = if (isGif) XMediaType.GIF else XMediaType.VIDEO,
                                    previewUrl = previewUrl,
                                    availableFormats = formats,
                                    durationSeconds = durationMs / 1000L,
                                    durationMs = durationMs
                                )
                            )
                        } else {
                            val origUrl = "$previewUrl?name=orig"
                            val photoDims = extractDimensions(mObj, previewUrl)
                            val photoLabel = if (photoDims != null) {
                                "JPG Фото (Оригинал • ${photoDims.first}×${photoDims.second})"
                            } else {
                                "JPG Фото (Оригинал)"
                            }
                            formats.add(
                                XFormatOption(
                                    id = "photo_orig",
                                    label = photoLabel,
                                    extension = "jpg",
                                    downloadUrl = origUrl
                                )
                            )
                            mediaList.add(
                                XMediaItem(
                                    type = XMediaType.PHOTO,
                                    previewUrl = previewUrl,
                                    availableFormats = formats
                                )
                            )
                        }
                    }
                }

                if (mediaList.isNotEmpty()) {
                    XPostInfo(tweetId, authorName, authorHandle, tweetText, mediaList)
                } else null
            }
        } catch (e: Exception) {
            null
        }
    }
}
