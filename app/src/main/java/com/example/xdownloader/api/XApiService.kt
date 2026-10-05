package com.example.xdownloader.api

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

class XApiService {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val gson = Gson()

    /**
     * Extracts Tweet numeric status ID from varied X / Twitter link forms.
     */
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
     * Fetches post information and available media streams (MP4s, photos, GIF conversion options).
     */
    suspend fun getPostInfo(tweetId: String): Result<XPostInfo> = withContext(Dispatchers.IO) {
        try {
            // Attempt 1: VxTwitter / FxTwitter public API (fast and returns structured media variants)
            val vxtResult = fetchFromVxTwitter(tweetId)
            if (vxtResult != null && vxtResult.mediaItems.isNotEmpty()) {
                return@withContext Result.success(vxtResult)
            }

            // Attempt 2: Official Twitter Syndication CDN API
            val syndicationResult = fetchFromSyndication(tweetId)
            if (syndicationResult != null && syndicationResult.mediaItems.isNotEmpty()) {
                return@withContext Result.success(syndicationResult)
            }

            // Attempt 3: FxTwitter secondary endpoint
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

                val durationSec = (mObj.get("duration_millis")?.asLong ?: 0L) / 1000L

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

                            // Sort descending by bitrate
                            mp4Variants.sortByDescending { it.first }

                            if (mp4Variants.isNotEmpty()) {
                                bestMp4Url = mp4Variants.first().second
                            }

                            for ((index, item) in mp4Variants.withIndex()) {
                                val bitrate = item.first
                                val vUrl = item.second
                                val qualityLabel = when {
                                    bitrate > 1_500_000 -> "1080p (Full HD)"
                                    bitrate > 700_000 -> "720p (HD)"
                                    bitrate > 350_000 -> "480p (SD)"
                                    bitrate > 0 -> "360p"
                                    else -> if (index == 0) "Максимальное качество (MP4)" else "Стандартное качество (MP4)"
                                }

                                formats.add(
                                    XFormatOption(
                                        id = "mp4_$index",
                                        label = qualityLabel,
                                        extension = "mp4",
                                        downloadUrl = vUrl,
                                        bitrate = bitrate,
                                        isGifConversion = false
                                    )
                                )
                            }
                        } else {
                            formats.add(
                                XFormatOption(
                                    id = "mp4_main",
                                    label = "MP4 Видео (Оригинал)",
                                    extension = "mp4",
                                    downloadUrl = bestMp4Url,
                                    isGifConversion = false
                                )
                            )
                        }

                        // ОБЯЗАТЕЛЬНО: Добавляем выбор формата GIF (первым, если это GIF-пост)
                        val gifOption = XFormatOption(
                            id = "gif_format",
                            label = if (isNativeGif) "GIF (Оригинальная анимация .gif)" else "GIF (Конвертировать в анимацию .gif)",
                            extension = "gif",
                            downloadUrl = bestMp4Url,
                            isGifConversion = true
                        )
                        if (isNativeGif) {
                            formats.add(0, gifOption)
                        } else {
                            formats.add(gifOption)
                        }

                        mediaList.add(
                            XMediaItem(
                                type = if (isNativeGif) XMediaType.GIF else XMediaType.VIDEO,
                                previewUrl = previewUrl,
                                availableFormats = formats,
                                durationSeconds = durationSec
                            )
                        )
                    }
                    else -> {
                        // Photo
                        val photoUrl = mObj.get("url")?.asString ?: previewUrl
                        val origUrl = if (photoUrl.contains("twimg.com")) {
                            if (photoUrl.contains("?")) "${photoUrl.substringBefore("?")}?name=orig" else "$photoUrl?name=orig"
                        } else photoUrl

                        formats.add(
                            XFormatOption(
                                id = "photo_orig",
                                label = "Оригинальное фото (HD)",
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
                            if (mObj.has("video_info") && mObj.getAsJsonObject("video_info").has("variants")) {
                                val variants = mObj.getAsJsonObject("video_info").getAsJsonArray("variants")
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

                                for ((idx, item) in list.withIndex()) {
                                    val label = when {
                                        item.first > 1_500_000 -> "1080p (Full HD)"
                                        item.first > 700_000 -> "720p (HD)"
                                        item.first > 350_000 -> "480p"
                                        item.first > 0 -> "360p"
                                        else -> "Качество $idx"
                                    }
                                    formats.add(
                                        XFormatOption(
                                            id = "mp4_$idx",
                                            label = label,
                                            extension = "mp4",
                                            downloadUrl = item.second,
                                            bitrate = item.first
                                        )
                                    )
                                }
                            }

                            if (bestUrl.isNotEmpty()) {
                                val gifOption = XFormatOption(
                                    id = "gif_format",
                                    label = "GIF (Анимированный GIF)",
                                    extension = "gif",
                                    downloadUrl = bestUrl,
                                    isGifConversion = true
                                )
                                if (isGif) {
                                    formats.add(0, gifOption)
                                } else {
                                    formats.add(gifOption)
                                }
                            }

                            mediaList.add(
                                XMediaItem(
                                    type = if (isGif) XMediaType.GIF else XMediaType.VIDEO,
                                    previewUrl = previewUrl,
                                    availableFormats = formats
                                )
                            )
                        } else {
                            // Photo
                            val origUrl = "$previewUrl?name=orig"
                            formats.add(
                                XFormatOption(
                                    id = "photo_orig",
                                    label = "Оригинальное фото (HD)",
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
