package com.example.xdownloader.gif

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream
import kotlin.math.min

object GifConverter {

    /**
     * Converts a local MP4 video file into an animated GIF.
     * @param context Application context
     * @param videoFile Local MP4 file
     * @param targetTitle Desired title of output file
     * @param maxDurationSec Maximum duration in seconds to convert (default 12s)
     * @param fps Frame rate for the GIF (default 10 fps)
     * @param maxDimension Maximum dimension in pixels (default 360)
     * @param onProgress Callback receiving percentage 0..100
     * @return Saved Uri or null on failure
     */
    suspend fun convertVideoToGif(
        context: Context,
        videoFile: File,
        targetTitle: String,
        maxDurationSec: Int = 12,
        fps: Int = 10,
        maxDimension: Int = 360,
        onProgress: (Int) -> Unit
    ): Uri? = withContext(Dispatchers.IO) {
        if (!videoFile.exists() || videoFile.length() < 1000L) {
            return@withContext null
        }

        val retriever = MediaMetadataRetriever()
        var fis: FileInputStream? = null

        var outputUri: Uri? = null
        var outStream: OutputStream? = null
        var targetFile: File? = null

        try {
            fis = FileInputStream(videoFile)
            retriever.setDataSource(fis.fd)

            val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            val widthStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
            val heightStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)

            val durationMs = durationStr?.toLongOrNull() ?: 3000L
            val actualDurationMs = min(durationMs, maxDurationSec * 1000L).coerceAtLeast(400L)

            val srcWidth = widthStr?.toIntOrNull() ?: 480
            val srcHeight = heightStr?.toIntOrNull() ?: 480

            // Strictly preserve aspect ratio with even dimensions
            val (targetW, targetH) = if (srcWidth >= srcHeight) {
                val w = (min(srcWidth, maxDimension).coerceAtLeast(120) / 2) * 2
                val rawH = ((w.toLong() * srcHeight) / srcWidth).toInt().coerceAtLeast(120)
                val h = (rawH / 2) * 2
                Pair(w, h)
            } else {
                val h = (min(srcHeight, maxDimension).coerceAtLeast(120) / 2) * 2
                val rawW = ((h.toLong() * srcWidth) / srcHeight).toInt().coerceAtLeast(120)
                val w = (rawW / 2) * 2
                Pair(w, h)
            }

            val frameIntervalMs = 1000L / fps
            val totalFrames = (actualDurationMs / frameIntervalMs).toInt().coerceIn(2, 60)

            val safeTitle = targetTitle
                .replace(Regex("[^a-zA-Z0-9а-яА-ЯёЁ._\\-\\s]"), "")
                .trim()
                .take(45)
                .ifEmpty { "X_Post" }
            val fileName = "${safeTitle}_anim.gif"

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/gif")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                outputUri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (outputUri != null) {
                    outStream = context.contentResolver.openOutputStream(outputUri)
                }
            } else {
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!downloadsDir.exists()) downloadsDir.mkdirs()
                targetFile = File(downloadsDir, fileName)
                outStream = FileOutputStream(targetFile)
                outputUri = Uri.fromFile(targetFile)
            }

            if (outStream == null) {
                return@withContext null
            }

            val encoder = AnimatedGifEncoder()
            encoder.setSize(targetW, targetH)
            encoder.setDelay(frameIntervalMs.toInt())
            encoder.setRepeat(0) // 0 = loop forever
            encoder.setQuality(10)
            encoder.start(outStream)

            var framesAdded = 0
            var lastValidBitmap: Bitmap? = null

            for (i in 0 until totalFrames) {
                val timeUs = i * frameIntervalMs * 1000L
                var frame: Bitmap? = null

                // 1. Primary: Exact frame decoding
                try {
                    frame = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
                } catch (_: Throwable) {}

                // 2. Fallback: Sync keyframe decoding
                if (frame == null) {
                    try {
                        frame = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    } catch (_: Throwable) {}
                }

                // 3. Fallback: General frame at time
                if (frame == null) {
                    try {
                        frame = retriever.getFrameAtTime(timeUs)
                    } catch (_: Throwable) {}
                }

                // 4. Fallback: First frame
                if (frame == null && i == 0) {
                    try {
                        frame = retriever.frameAtTime
                    } catch (_: Throwable) {}
                }

                val bitmapToUse = frame ?: lastValidBitmap

                if (bitmapToUse != null) {
                    val scaled = if (bitmapToUse.width == targetW && bitmapToUse.height == targetH) {
                        bitmapToUse
                    } else {
                        Bitmap.createScaledBitmap(bitmapToUse, targetW, targetH, true)
                    }

                    encoder.addFrame(scaled)
                    framesAdded++

                    if (frame != null && frame != lastValidBitmap) {
                        lastValidBitmap?.recycle()
                        lastValidBitmap = frame
                    }
                    if (scaled != bitmapToUse && scaled != lastValidBitmap) {
                        scaled.recycle()
                    }
                }

                val progress = ((i + 1) * 100) / totalFrames
                withContext(Dispatchers.Main) {
                    onProgress(progress)
                }
            }

            lastValidBitmap?.recycle()

            if (framesAdded == 0) {
                // If not a single frame could be decoded, do NOT leave a corrupt 7-byte file!
                try { outStream.close() } catch (_: Exception) {}
                if (outputUri != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    try { context.contentResolver.delete(outputUri, null, null) } catch (_: Exception) {}
                } else if (targetFile != null && targetFile.exists()) {
                    targetFile.delete()
                }
                return@withContext null
            }

            encoder.finish()
            outStream.flush()
            outStream.close()

            // Remove IS_PENDING on Android 10+ so the file becomes visible and valid in Downloads
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && outputUri != null) {
                val updateValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }
                context.contentResolver.update(outputUri, updateValues, null, null)
            }

            outputUri
        } catch (e: Exception) {
            e.printStackTrace()
            try { outStream?.close() } catch (_: Exception) {}
            if (outputUri != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try { context.contentResolver.delete(outputUri, null, null) } catch (_: Exception) {}
            } else if (targetFile != null && targetFile.exists()) {
                targetFile.delete()
            }
            null
        } finally {
            try { fis?.close() } catch (_: Exception) {}
            try { retriever.release() } catch (_: Exception) {}
        }
    }
}
