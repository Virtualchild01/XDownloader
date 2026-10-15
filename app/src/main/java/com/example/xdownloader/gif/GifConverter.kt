package com.example.xdownloader.gif

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import com.bumptech.glide.Glide
import com.bumptech.glide.request.RequestOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jcodec.api.FrameGrab
import org.jcodec.common.io.NIOUtils
import org.jcodec.common.io.SeekableByteChannel
import org.jcodec.common.model.ColorSpace
import org.jcodec.common.model.Picture
import org.jcodec.scale.ColorUtil
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.util.Arrays
import java.util.TreeMap
import kotlin.math.max
import kotlin.math.min

object GifConverter {

    data class Mp4TrackMeta(
        val durationMs: Long,
        val videoFrames: Int,
        val displayRank: IntArray
    )

    private fun scaleBitmapIfNeeded(bitmap: Bitmap, targetShortSide: Int): Bitmap {
        if (targetShortSide <= 0) return bitmap
        val w = bitmap.width
        val h = bitmap.height
        val minSide = min(w, h)
        val maxSide = max(w, h)
        if (minSide <= targetShortSide && maxSide <= 1280) return bitmap

        val scale = min(targetShortSide.toFloat() / minSide.toFloat(), 1280f / maxSide.toFloat()).coerceAtMost(1.0f)
        val newW = (((w * scale).toInt() + 1) / 2 * 2).coerceAtLeast(2)
        val newH = (((h * scale).toInt() + 1) / 2 * 2).coerceAtLeast(2)
        if (newW == w && newH == h) return bitmap

        val scaled = Bitmap.createScaledBitmap(bitmap, newW, newH, true)
        if (scaled != bitmap) bitmap.recycle()
        return scaled
    }

    /**
     * Converts a local MP4 video file into an animated GIF.
     * Reorders H.264 B-frames from Decode Order (DTS) into strict Presentation Order (PTS)
     * using MP4 'stts' + 'ctts' tables, eliminating frame jumping/stuttering.
     * @return Pair of (Uri?, errorMessage?)
     */
    suspend fun convertVideoToGif(
        context: Context,
        videoFile: File,
        targetTitle: String,
        expectedDurationMs: Long = 0L,
        maxDurationSec: Int = 12,
        fps: Int = 24,
        maxDimension: Int = 0,
        onProgress: (Int) -> Unit
    ): Pair<Uri?, String?> = withContext(Dispatchers.IO) {
        if (!videoFile.exists() || !videoFile.canRead() || videoFile.length() < 1000L) {
            return@withContext Pair(null, "Видеофайл пуст или не прочитан (" + videoFile.length() + " байт)")
        }

        try {
            videoFile.setReadable(true, false)
        } catch (ignored: Throwable) {}

        withContext(Dispatchers.Main) {
            onProgress(35)
        }

        val safeTitle = targetTitle
            .filter { it.isLetterOrDigit() || it == '_' || it == '-' || it == '.' || it == ' ' }
            .trim()
            .take(45)
            .ifEmpty { "X_Post" }
        val fileName = safeTitle + "_anim.gif"

        var outputUri: Uri? = null
        var rawOutStream: OutputStream? = null
        var bufferedOut: BufferedOutputStream? = null
        var targetFile: File? = null

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/gif")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                outputUri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (outputUri != null) {
                    rawOutStream = context.contentResolver.openOutputStream(outputUri)
                }
            } else {
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!downloadsDir.exists()) downloadsDir.mkdirs()
                targetFile = File(downloadsDir, fileName)
                rawOutStream = FileOutputStream(targetFile)
                outputUri = Uri.fromFile(targetFile)
            }

            if (rawOutStream == null) {
                return@withContext Pair(null, "Не удалось создать файл в Загрузках")
            }

            // Буферизация потока записи (256 КБ) ускоряет сохранение GIF в несколько раз
            bufferedOut = BufferedOutputStream(rawOutStream, 262144)

            val encoder = AnimatedGifEncoder()
            encoder.setDelay(40)
            encoder.setRepeat(0) // 0 = бесконечный цикл
            encoder.setQuality(5)
            encoder.start(bufferedOut)

            var framesAdded = 0
            val totalTargetFrames = maxDurationSec * 30
            val diagLog = StringBuilder()

            // УРОВЕНЬ 1 (Основной): Потоковый декодер JCodec с переупорядочиванием B-кадров (stts + ctts -> PTS)
            try {
                framesAdded = extractWithJCodec(
                    videoFile = videoFile,
                    expectedDurationMs = expectedDurationMs,
                    maxDurationSec = maxDurationSec,
                    maxFrames = totalTargetFrames,
                    targetWidth = maxDimension,
                    encoder = encoder,
                    onProgress = onProgress
                )
            } catch (e: Throwable) {
                diagLog.append("JCodec: ").append(e.message ?: e.javaClass.simpleName).append("; ")
            }

            // УРОВЕНЬ 2 (Резервный): MediaMetadataRetriever
            if (framesAdded == 0) {
                try {
                    framesAdded = extractWithRetriever(
                        context = context,
                        videoFile = videoFile,
                        expectedDurationMs = expectedDurationMs,
                        maxDurationSec = maxDurationSec,
                        fps = fps,
                        maxDimension = maxDimension,
                        encoder = encoder,
                        diagLog = diagLog
                    ) { p ->
                        kotlinx.coroutines.runBlocking(Dispatchers.Main) {
                            onProgress(p)
                        }
                    }
                } catch (e: Throwable) {
                    diagLog.append("MMR: ").append(e.message ?: e.javaClass.simpleName).append("; ")
                }
            }

            // УРОВЕНЬ 3 (Резервный): Glide Video Frame Loader
            if (framesAdded == 0) {
                try {
                    framesAdded = extractWithGlide(
                        context = context,
                        videoFile = videoFile,
                        expectedDurationMs = expectedDurationMs,
                        maxDurationSec = maxDurationSec,
                        fps = fps,
                        maxDimension = maxDimension,
                        encoder = encoder
                    ) { p ->
                        kotlinx.coroutines.runBlocking(Dispatchers.Main) {
                            onProgress(p)
                        }
                    }
                } catch (e: Throwable) {
                    diagLog.append("Glide: ").append(e.message ?: e.javaClass.simpleName).append("; ")
                }
            }

            if (framesAdded == 0) {
                try { bufferedOut.close() } catch (ignored: Throwable) {}
                if (outputUri != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    try { context.contentResolver.delete(outputUri, null, null) } catch (ignored: Throwable) {}
                } else if (targetFile != null && targetFile.exists()) {
                    targetFile.delete()
                }
                val msg = if (diagLog.isNotBlank()) {
                    "Сбой декодирования (" + diagLog.toString().trim() + ", размер: " + videoFile.length() + " Б)"
                } else {
                    "Не удалось извлечь кадры из видеофайла (размер: " + videoFile.length() + " Б)"
                }
                return@withContext Pair(null, msg)
            }

            encoder.finish()
            bufferedOut.flush()
            bufferedOut.close()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && outputUri != null) {
                val updateValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }
                context.contentResolver.update(outputUri, updateValues, null, null)
            } else if (targetFile != null) {
                try {
                    MediaScannerConnection.scanFile(context, arrayOf(targetFile.absolutePath), arrayOf("image/gif"), null)
                } catch (ignored: Throwable) {}
            }

            withContext(Dispatchers.Main) {
                onProgress(100)
            }

            Pair(outputUri, null)
        } catch (e: Exception) {
            e.printStackTrace()
            try { bufferedOut?.close() } catch (ignored: Throwable) {}
            if (outputUri != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try { context.contentResolver.delete(outputUri, null, null) } catch (ignored: Throwable) {}
            } else if (targetFile != null && targetFile.exists()) {
                targetFile.delete()
            }
            Pair(null, e.message ?: "Сбой при конвертации")
        }
    }

    /**
     * Прямое бинарное чтение метаданных MP4-файла из атомов:
     * - 'mvhd': общая длительность ролика в миллисекундах
     * - 'stsz' (внутри видеотрека 'vide'): точное количество видеокадров
     * - 'stts' + 'ctts' (внутри 'vide'): вычисление PTS = DTS + CTTS каждого кадра
     *   для восстановления строгого хронологического порядка кадров при наличии B-frames!
     */
    fun parseMp4Meta(videoFile: File): Mp4TrackMeta {
        var durationMs = 0L
        var videoFrames = 0
        var displayRank = IntArray(0)
        var raf: RandomAccessFile? = null

        try {
            raf = RandomAccessFile(videoFile, "r")
            val len = raf.length()
            if (len < 48L) return Mp4TrackMeta(0L, 0, displayRank)

            val chunkSize = min(len, 2L * 1024L * 1024L).toInt()
            val buf = ByteArray(chunkSize)

            for (pass in 0 until 2) {
                if (pass == 0) {
                    raf.seek(0L)
                } else {
                    if (len <= chunkSize) break
                    raf.seek((len - chunkSize).coerceAtLeast(0L))
                }
                val read = raf.read(buf)
                if (read < 40) continue

                val bb = ByteBuffer.wrap(buf, 0, read)
                var inVideoTrack = false
                var sttsPos = -1
                var cttsPos = -1

                for (i in 0 until (read - 36)) {
                    val tag = bb.getInt(i)
                    // 'mvhd' = 0x6D766864
                    if (tag == 0x6D766864 && durationMs <= 0L) {
                        val version = buf[i + 4].toInt() and 0xFF
                        val timescale: Long
                        val duration: Long
                        if (version == 0) {
                            timescale = bb.getInt(i + 16).toLong() and 0xFFFFFFFFL
                            duration = bb.getInt(i + 20).toLong() and 0xFFFFFFFFL
                        } else if (version == 1) {
                            timescale = bb.getInt(i + 24).toLong() and 0xFFFFFFFFL
                            duration = bb.getLong(i + 28)
                        } else {
                            continue
                        }
                        if (timescale > 0L && duration > 0L && duration < timescale * 36000L) {
                            durationMs = (duration * 1000L) / timescale
                        }
                    }
                    // 'trak' = 0x7472616B
                    if (tag == 0x7472616B) {
                        inVideoTrack = false
                    }
                    // 'vide' = 0x76696465
                    if (tag == 0x76696465) {
                        inVideoTrack = true
                    }
                    // 'soun' = 0x736F756E
                    if (tag == 0x736F756E) {
                        inVideoTrack = false
                    }
                    if (inVideoTrack) {
                        // 'stsz' = 0x7374737A
                        if (tag == 0x7374737A && videoFrames <= 0) {
                            val count = bb.getInt(i + 12)
                            if (count in 1..100000) {
                                videoFrames = count
                            }
                        }
                        // 'stts' = 0x73747473
                        if (tag == 0x73747473 && sttsPos == -1) {
                            sttsPos = i
                        }
                        // 'ctts' = 0x63747473
                        if (tag == 0x63747473 && cttsPos == -1) {
                            cttsPos = i
                        }
                    }
                }

                if (videoFrames > 0) {
                    val dts = LongArray(videoFrames)
                    if (sttsPos != -1 && sttsPos + 12 <= read) {
                        val entryCount = bb.getInt(sttsPos + 8)
                        var pos = sttsPos + 12
                        var sampleIdx = 0
                        var curDts = 0L
                        var e = 0
                        while (e < entryCount && pos + 8 <= read && sampleIdx < videoFrames) {
                            val sc = bb.getInt(pos)
                            val delta = bb.getInt(pos + 4).toLong()
                            pos += 8
                            var k = 0
                            while (k < sc && sampleIdx < videoFrames) {
                                dts[sampleIdx++] = curDts
                                curDts += delta
                                k++
                            }
                            e++
                        }
                    } else {
                        for (idx in 0 until videoFrames) dts[idx] = idx * 1000L
                    }

                    val pts = LongArray(videoFrames)
                    for (idx in 0 until videoFrames) pts[idx] = dts[idx]

                    if (cttsPos != -1 && cttsPos + 12 <= read) {
                        val entryCount = bb.getInt(cttsPos + 8)
                        var pos = cttsPos + 12
                        var sampleIdx = 0
                        var e = 0
                        while (e < entryCount && pos + 8 <= read && sampleIdx < videoFrames) {
                            val sc = bb.getInt(pos)
                            val offset = bb.getInt(pos + 4).toLong()
                            pos += 8
                            var k = 0
                            while (k < sc && sampleIdx < videoFrames) {
                                pts[sampleIdx] += offset
                                sampleIdx++
                                k++
                            }
                            e++
                        }
                    }

                    // Сортируем индексы сэмплов по возрастанию PTS = DTS + CTTS:
                    // упаковываем (ptsNormalized shl 20) | decodeIdx в LongArray для быстрой сортировки без аллокаций
                    var minPts = Long.MAX_VALUE
                    for (p in pts) {
                        if (p < minPts) minPts = p
                    }
                    val packedPts = LongArray(videoFrames)
                    for (idx in 0 until videoFrames) {
                        val normPts = (pts[idx] - minPts).coerceAtLeast(0L)
                        packedPts[idx] = (normPts shl 20) or (idx.toLong() and 0xFFFFFL)
                    }
                    Arrays.sort(packedPts)

                    displayRank = IntArray(videoFrames)
                    for (rank in 0 until videoFrames) {
                        val decodeIdx = (packedPts[rank] and 0xFFFFFL).toInt()
                        if (decodeIdx in 0 until videoFrames) {
                            displayRank[decodeIdx] = rank
                        }
                    }
                }

                if (durationMs > 0L && videoFrames > 0) break
            }
        } catch (ignored: Throwable) {
        } finally {
            try { raf?.close() } catch (ignored: Throwable) {}
        }
        return Mp4TrackMeta(durationMs, videoFrames, displayRank)
    }

    /**
     * Потоковое декодирование видео через JCodec с переупорядочиванием B-кадров в строгий PTS порядок.
     * Для роликов <= 26 кадр/с сохраняются все 100% кадров (например, все 102 кадра при 24 fps).
     */
    private fun extractWithJCodec(
        videoFile: File,
        expectedDurationMs: Long,
        maxDurationSec: Int,
        maxFrames: Int,
        targetWidth: Int,
        encoder: AnimatedGifEncoder,
        onProgress: (Int) -> Unit
    ): Int {
        var channel: SeekableByteChannel? = null

        try {
            val meta = parseMp4Meta(videoFile)
            var fullVideoDurationMs = meta.durationMs
            if (fullVideoDurationMs <= 0L && expectedDurationMs > 0L) {
                fullVideoDurationMs = expectedDurationMs
            }
            if (fullVideoDurationMs <= 0L) {
                try {
                    val mmr = MediaMetadataRetriever()
                    mmr.setDataSource(videoFile.absolutePath)
                    fullVideoDurationMs = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                    mmr.release()
                } catch (ignored: Throwable) {}
            }

            channel = NIOUtils.readableChannel(videoFile)
            val grab = FrameGrab.createFrameGrab(channel)

            if (meta.videoFrames > 0 && fullVideoDurationMs > 0L) {
                val maxAllowedMs = maxDurationSec * 1000L
                val totalRawToRead = if (fullVideoDurationMs > maxAllowedMs) {
                    ((meta.videoFrames.toLong() * maxAllowedMs) / fullVideoDurationMs).toInt().coerceIn(1, maxFrames)
                } else {
                    min(meta.videoFrames, maxFrames)
                }
                val clipDurationMs = if (fullVideoDurationMs > maxAllowedMs) {
                    maxAllowedMs.toLong()
                } else {
                    fullVideoDurationMs.coerceAtLeast(200L)
                }

                // Для видео <= 26 fps (включая 24 fps и 25 fps) сохраняем все 100% кадров (step = 1)!
                val originalFps = (totalRawToRead * 1000.0) / clipDurationMs
                val step = when {
                    originalFps > 45.0 -> 3
                    originalFps > 26.0 -> 2
                    else -> 1
                }

                val totalFramesToEncode = ((totalRawToRead + step - 1) / step).coerceAtLeast(1)
                encoder.setRepeat(0)

                val reorderBuffer = TreeMap<Int, Bitmap>()
                var readCount = 0
                var nextGifOrderToEmit = 0
                var encodedCount = 0

                // Читаем до totalRawToRead + 8 (чтобы захватить опорные P-кадры для последних B-кадров на границе)
                val maxReadLimit = min(meta.videoFrames, totalRawToRead + 8)

                try {
                    while (readCount < maxReadLimit) {
                        val pic: Picture = grab.nativeFrame ?: break
                        val dispIdx = if (readCount < meta.displayRank.size) meta.displayRank[readCount] else readCount

                        if (dispIdx < totalRawToRead && dispIdx % step == 0) {
                            val gifOrder = dispIdx / step
                            val bitmap = jcodecPictureToBitmap(pic)
                            if (bitmap != null) {
                                val finalBmp = scaleBitmapIfNeeded(bitmap, targetWidth)

                                val prev = reorderBuffer.put(gifOrder, finalBmp)
                                prev?.recycle()
                            }
                        }

                        // Выдаём в GIF-энкодер кадры строго по порядку возрастания gifOrder (0, 1, 2, 3...)
                        while (reorderBuffer.isNotEmpty()) {
                            val firstKey = reorderBuffer.firstKey()
                            if (firstKey <= nextGifOrderToEmit || reorderBuffer.size > 12) {
                                val entry = reorderBuffer.pollFirstEntry()!!
                                val bmpToEncode = entry.value

                                val startCs = Math.round((encodedCount * clipDurationMs) / (10.0 * totalFramesToEncode)).toInt()
                                val endCs = Math.round(((encodedCount + 1) * clipDurationMs) / (10.0 * totalFramesToEncode)).toInt()
                                val delayCs = (endCs - startCs).coerceIn(2, 100)
                                encoder.setDelay(delayCs * 10)

                                encoder.addFrame(bmpToEncode)
                                bmpToEncode.recycle()
                                encodedCount++
                                nextGifOrderToEmit = entry.key + 1

                                val p = (35 + (encodedCount * 60 / totalFramesToEncode)).coerceIn(35, 95)
                                kotlinx.coroutines.runBlocking(Dispatchers.Main) {
                                    onProgress(p)
                                }
                            } else {
                                break
                            }
                        }

                        readCount++
                    }

                    // Дозаписываем оставшиеся в буфере кадры в строгом хронологическом порядке
                    while (reorderBuffer.isNotEmpty()) {
                        val entry = reorderBuffer.pollFirstEntry()!!
                        val bmpToEncode = entry.value

                        val startCs = Math.round((encodedCount * clipDurationMs) / (10.0 * totalFramesToEncode)).toInt()
                        val endCs = Math.round(((encodedCount + 1) * clipDurationMs) / (10.0 * totalFramesToEncode)).toInt()
                        val delayCs = (endCs - startCs).coerceIn(2, 100)
                        encoder.setDelay(delayCs * 10)

                        encoder.addFrame(bmpToEncode)
                        bmpToEncode.recycle()
                        encodedCount++

                        val p = (35 + (encodedCount * 60 / totalFramesToEncode)).coerceIn(35, 95)
                        kotlinx.coroutines.runBlocking(Dispatchers.Main) {
                            onProgress(p)
                        }
                    }
                } finally {
                    for (bmp in reorderBuffer.values) {
                        if (!bmp.isRecycled) bmp.recycle()
                    }
                    reorderBuffer.clear()
                }

                return encodedCount
            }

            // Резервный режим, если в MP4 нестандартный заголовок
            val decodedBitmaps = mutableListOf<Bitmap>()
            var readCount = 0
            try {
                while (readCount < maxFrames) {
                    val pic: Picture = grab.nativeFrame ?: break
                    val bitmap = jcodecPictureToBitmap(pic)
                    if (bitmap != null) {
                        val finalBmp = scaleBitmapIfNeeded(bitmap, targetWidth)
                        decodedBitmaps.add(finalBmp)
                    }
                    readCount++
                }

                if (decodedBitmaps.isEmpty()) return 0

                val clipDurationMs = if (fullVideoDurationMs > 0L) {
                    min(fullVideoDurationMs, maxDurationSec * 1000L).coerceAtLeast(200L)
                } else {
                    ((readCount * 1000L) / 24L).coerceAtLeast(200L)
                }

                val currentFps = (decodedBitmaps.size * 1000.0) / clipDurationMs
                val postStep = when {
                    currentFps > 45.0 -> 3
                    currentFps > 26.0 -> 2
                    else -> 1
                }

                val framesToEncode = mutableListOf<Bitmap>()
                for ((index, bmp) in decodedBitmaps.withIndex()) {
                    if (index % postStep == 0) {
                        framesToEncode.add(bmp)
                    } else {
                        bmp.recycle()
                    }
                }

                val totalFrames = framesToEncode.size
                if (totalFrames == 0) return 0

                encoder.setRepeat(0)
                for ((index, bmp) in framesToEncode.withIndex()) {
                    val startCs = Math.round((index * clipDurationMs) / (10.0 * totalFrames)).toInt()
                    val endCs = Math.round(((index + 1) * clipDurationMs) / (10.0 * totalFrames)).toInt()
                    val delayCs = (endCs - startCs).coerceIn(2, 100)
                    encoder.setDelay(delayCs * 10)

                    encoder.addFrame(bmp)
                    bmp.recycle()

                    val p = 35 + ((index + 1) * 60 / totalFrames)
                    kotlinx.coroutines.runBlocking(Dispatchers.Main) {
                        onProgress(p)
                    }
                }
                return totalFrames
            } finally {
                for (b in decodedBitmaps) {
                    if (!b.isRecycled) b.recycle()
                }
            }
        } finally {
            try { NIOUtils.closeQuietly(channel) } catch (ignored: Throwable) {}
        }
    }

    /**
     * Конвертация Picture из JCodec 0.2.5 в стандартный Android ARGB_8888 Bitmap
     * в полном исходном разрешении с восстановлением знакового диапазона [-128..127] -> [0..255].
     */
    private fun jcodecPictureToBitmap(src: Picture): Bitmap? {
        return try {
            val rgbPic: Picture
            if (src.color == ColorSpace.RGB) {
                rgbPic = src
            } else {
                val transform = ColorUtil.getTransform(src.color, ColorSpace.RGB)
                rgbPic = Picture.create(src.width, src.height, ColorSpace.RGB)
                transform.transform(src, rgbPic)
            }

            val w = rgbPic.width
            val h = rgbPic.height
            val bytes = rgbPic.data[0]
            val pixels = IntArray(w * h)
            var bi = 0
            val limit = bytes.size

            for (i in 0 until (w * h)) {
                if (bi + 2 < limit) {
                    val r = (bytes[bi++].toInt() + 128).coerceIn(0, 255)
                    val g = (bytes[bi++].toInt() + 128).coerceIn(0, 255)
                    val b = (bytes[bi++].toInt() + 128).coerceIn(0, 255)
                    pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                } else {
                    pixels[i] = -0x1000000
                }
            }

            val fullBitmap = Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)

            var cropX = 0
            var cropY = 0
            var cropW = w
            var cropH = h
            try {
                val cropObj = src.javaClass.getMethod("getCrop").invoke(src)
                if (cropObj != null) {
                    val cx = (cropObj.javaClass.getMethod("getX").invoke(cropObj) as? Number)?.toInt() ?: 0
                    val cy = (cropObj.javaClass.getMethod("getY").invoke(cropObj) as? Number)?.toInt() ?: 0
                    val cw = (cropObj.javaClass.getMethod("getWidth").invoke(cropObj) as? Number)?.toInt() ?: w
                    val ch = (cropObj.javaClass.getMethod("getHeight").invoke(cropObj) as? Number)?.toInt() ?: h
                    if (cw in 1..w && ch in 1..h && cx >= 0 && cy >= 0 && cx + cw <= w && cy + ch <= h) {
                        cropX = cx
                        cropY = cy
                        cropW = cw
                        cropH = ch
                    }
                }
            } catch (ignored: Throwable) {}

            if (cropW != w || cropH != h || cropX != 0 || cropY != 0) {
                val cropped = Bitmap.createBitmap(fullBitmap, cropX, cropY, cropW, cropH)
                if (cropped != fullBitmap) fullBitmap.recycle()
                cropped
            } else {
                fullBitmap
            }
        } catch (e: Throwable) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Извлечение кадров через MediaMetadataRetriever (резервный метод).
     */
    private fun extractWithRetriever(
        context: Context,
        videoFile: File,
        expectedDurationMs: Long,
        maxDurationSec: Int,
        fps: Int,
        maxDimension: Int,
        encoder: AnimatedGifEncoder,
        diagLog: StringBuilder,
        onProgress: (Int) -> Unit
    ): Int {
        var added = 0
        var retriever: MediaMetadataRetriever? = null
        var pfd: ParcelFileDescriptor? = null
        var fis: FileInputStream? = null

        try {
            var initialized = false

            try {
                val testRetriever = MediaMetadataRetriever()
                pfd = ParcelFileDescriptor.open(videoFile, ParcelFileDescriptor.MODE_READ_ONLY)
                testRetriever.setDataSource(pfd.fileDescriptor)
                retriever = testRetriever
                initialized = true
            } catch (e: Throwable) {
                diagLog.append("PfdInit: ").append(e.message ?: e.javaClass.simpleName).append("; ")
            }

            if (!initialized) {
                try {
                    val testRetriever = MediaMetadataRetriever()
                    testRetriever.setDataSource(videoFile.absolutePath)
                    retriever = testRetriever
                    initialized = true
                } catch (e: Throwable) {
                    diagLog.append("PathInit: ").append(e.message ?: e.javaClass.simpleName).append("; ")
                }
            }

            if (!initialized) {
                try {
                    val testRetriever = MediaMetadataRetriever()
                    fis = FileInputStream(videoFile)
                    testRetriever.setDataSource(fis.fd)
                    retriever = testRetriever
                    initialized = true
                } catch (e: Throwable) {
                    diagLog.append("FisInit: ").append(e.message ?: e.javaClass.simpleName).append("; ")
                }
            }

            if (!initialized) {
                try {
                    val testRetriever = MediaMetadataRetriever()
                    testRetriever.setDataSource(context, Uri.fromFile(videoFile))
                    retriever = testRetriever
                    initialized = true
                } catch (e: Throwable) {
                    diagLog.append("UriInit: ").append(e.message ?: e.javaClass.simpleName).append("; ")
                }
            }

            val mmr = retriever ?: return 0

            val meta = parseMp4Meta(videoFile)
            var durationMs = meta.durationMs
            if (durationMs <= 0L && expectedDurationMs > 0L) {
                durationMs = expectedDurationMs
            }
            if (durationMs <= 0L) {
                durationMs = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 2500L
            }

            val actualDurationMs = min(durationMs, maxDurationSec * 1000L).coerceAtLeast(300L)
            val frameIntervalMs = (1000L / fps).coerceAtLeast(40L)
            val totalFrames = (actualDurationMs / frameIntervalMs).toInt().coerceIn(2, 150)

            var lastValid: Bitmap? = null

            for (i in 0 until totalFrames) {
                val timeUs = (i * actualDurationMs * 1000L) / totalFrames
                var frame: Bitmap? = null

                try {
                    frame = mmr.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
                } catch (ignored: Throwable) {}

                if (frame == null) {
                    try {
                        frame = mmr.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    } catch (ignored: Throwable) {}
                }

                val toUse = frame ?: lastValid
                if (toUse != null) {
                    val scaled = if (maxDimension > 0 && toUse.width > maxDimension) {
                        val aspect = toUse.height.toFloat() / toUse.width.toFloat()
                        val targetHeight = ((maxDimension * aspect).toInt() / 2) * 2
                        Bitmap.createScaledBitmap(toUse, maxDimension, targetHeight.coerceAtLeast(2), true)
                    } else toUse

                    val startCs = Math.round((i * actualDurationMs) / (10.0 * totalFrames)).toInt()
                    val endCs = Math.round(((i + 1) * actualDurationMs) / (10.0 * totalFrames)).toInt()
                    val delayCs = (endCs - startCs).coerceIn(2, 100)
                    encoder.setDelay(delayCs * 10)

                    encoder.addFrame(scaled)
                    if (scaled != toUse) scaled.recycle()
                    added++

                    if (frame != null) {
                        if (lastValid != null && lastValid != frame) {
                            lastValid.recycle()
                        }
                        lastValid = frame
                    }
                }

                val p = 35 + ((i + 1) * 60 / totalFrames)
                onProgress(p)
            }
            lastValid?.recycle()
        } finally {
            try { retriever?.release() } catch (ignored: Throwable) {}
            try { pfd?.close() } catch (ignored: Throwable) {}
            try { fis?.close() } catch (ignored: Throwable) {}
        }

        return added
    }

    /**
     * Извлечение кадров через Glide (резервный метод).
     */
    private fun extractWithGlide(
        context: Context,
        videoFile: File,
        expectedDurationMs: Long,
        maxDurationSec: Int,
        fps: Int,
        maxDimension: Int,
        encoder: AnimatedGifEncoder,
        onProgress: (Int) -> Unit
    ): Int {
        var added = 0
        try {
            val meta = parseMp4Meta(videoFile)
            val durationMs = meta.durationMs.takeIf { it > 0L }
                ?: expectedDurationMs.takeIf { it > 0L }
                ?: 3000L
            val actualDurationMs = min(durationMs, maxDurationSec * 1000L).coerceAtLeast(300L)
            val frameIntervalMs = (1000L / fps).coerceAtLeast(40L)
            val totalFrames = (actualDurationMs / frameIntervalMs).toInt().coerceIn(2, 120)
            var lastValid: Bitmap? = null

            for (i in 0 until totalFrames) {
                val timeUs = (i * actualDurationMs * 1000L) / totalFrames
                var frame: Bitmap? = null
                try {
                    val req = Glide.with(context.applicationContext)
                        .asBitmap()
                        .load(videoFile)
                        .apply(RequestOptions.frameOf(timeUs))
                    frame = if (maxDimension > 0) {
                        req.submit(maxDimension, maxDimension).get()
                    } else {
                        req.submit().get()
                    }
                } catch (ignored: Throwable) {}

                val toUse = frame ?: lastValid
                if (toUse != null) {
                    val startCs = Math.round((i * actualDurationMs) / (10.0 * totalFrames)).toInt()
                    val endCs = Math.round(((i + 1) * actualDurationMs) / (10.0 * totalFrames)).toInt()
                    val delayCs = (endCs - startCs).coerceIn(2, 100)
                    encoder.setDelay(delayCs * 10)

                    encoder.addFrame(toUse)
                    added++

                    if (frame != null) {
                        if (lastValid != null && lastValid != frame) {
                            lastValid.recycle()
                        }
                        lastValid = frame
                    }
                }

                val p = 35 + ((i + 1) * 60 / totalFrames)
                onProgress(p)
            }
            lastValid?.recycle()
        } catch (e: Throwable) {
            e.printStackTrace()
        }
        return added
    }
}
