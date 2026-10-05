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
import java.io.FileOutputStream
import java.io.OutputStream
import kotlin.math.min

object GifConverter {

    /**
     * Converts a local MP4 video file into an animated GIF.
     * @param context Application context
     * @param videoFile Local MP4 file
     * @param targetTitle Desired title of output file
     * @param onProgress Callback receiving percentage 0..100
     * @return Saved Uri or null on failure
     */
    suspend fun convertVideoToGif(
        context: Context,
        videoFile: File,
        targetTitle: String,
        maxDurationSec: Int = 10,
        fps: Int = 8,
        targetWidth: Int = 400,
        onProgress: (Int) -> Unit
    ): Uri? = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(videoFile.absolutePath)
            val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            val durationMs = durationStr?.toLongOrNull() ?: 3000L
            val actualDurationMs = min(durationMs, maxDurationSec * 1000L)

            val frameIntervalMs = 1000L / fps
            val totalFrames = (actualDurationMs / frameIntervalMs).toInt().coerceAtLeast(2)

            val fileName = "${targetTitle.replace(Regex("[^a-zA-Z0-9а-яА-ЯёЁ._\\-\\s]"), "").trim().take(50)}_anim.gif"

            var outputUri: Uri? = null
            var outStream: OutputStream? = null

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/gif")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                outputUri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (outputUri != null) {
                    outStream = context.contentResolver.openOutputStream(outputUri)
                }
            } else {
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val targetFile = File(downloadsDir, fileName)
                outStream = FileOutputStream(targetFile)
                outputUri = Uri.fromFile(targetFile)
            }

            if (outStream == null) return@withContext null

            val encoder = AnimatedGifEncoder()
            encoder.setDelay(frameIntervalMs.toInt())
            encoder.setRepeat(0) // infinite loop
            encoder.setQuality(10) // 10 = default good quality / speed trade-off
            encoder.start(outStream)

            for (i in 0 until totalFrames) {
                val timeUs = (i * frameIntervalMs * 1000L)
                val rawBitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                    retriever.getScaledFrameAtTime(
                        timeUs,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                        targetWidth,
                        (targetWidth * 9) / 16 // approximate aspect ratio or 0
                    ) ?: retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                } else {
                    retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                }

                if (rawBitmap != null) {
                    val scaledBitmap = scaleBitmapToWidth(rawBitmap, targetWidth)
                    encoder.addFrame(scaledBitmap)
                    if (scaledBitmap != rawBitmap) {
                        scaledBitmap.recycle()
                    }
                    rawBitmap.recycle()
                }

                val progress = ((i + 1) * 100) / totalFrames
                withContext(Dispatchers.Main) {
                    onProgress(progress)
                }
            }

            encoder.finish()
            outStream.close()
            retriever.release()

            outputUri
        } catch (e: Exception) {
            e.printStackTrace()
            try { retriever.release() } catch (_: Exception) {}
            null
        }
    }

    private fun scaleBitmapToWidth(bitmap: Bitmap, targetWidth: Int): Bitmap {
        if (bitmap.width <= targetWidth) return bitmap
        val aspectRatio = bitmap.height.toFloat() / bitmap.width.toFloat()
        val targetHeight = (targetWidth * aspectRatio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
    }
}
