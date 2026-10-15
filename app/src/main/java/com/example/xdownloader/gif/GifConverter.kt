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

    /**
     * Переиспользуемые буферы декодера JCodec.
     * Полностью исключают выделение 45 МБ новых массивов на каждом кадре 1920x2160 и предотвращают OutOfMemoryError!
     */
    private class JCodecReusableBuffers {
        var rgbPic: Picture? = null
        var pixels: IntArray = IntArray(0)
    }

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
     * Конвертирует локальный видеофайл MP4 в плавный анимированный GIF:
     * - Сохраняет 100% кадров (полные 24/25/30 fps без урезания частоты кадров)
     * - Упорядочивает двунаправленные B-кадры H.264 строго по времени показа (PTS = DTS + CTTS)
     * - Сжимает каждый декодированный кадр в LZW на лету и освобождает Bitmap немедленно,
     *   гарантируя нулевые потери кадров даже на огромных 1920x2160 видеороликах.
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

            bufferedOut = BufferedOutputStream(rawOutStream, 262144)

            val encoder = AnimatedGifEncoder()
            encoder.setDelay(40)
            encoder.setRepeat(0)
            encoder.setQuality(5)
            encoder.start(bufferedOut)

            var framesAdded = 0
            val totalTargetFrames = maxDurationSec * 32
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

                for (i in 4 until (read - 36)) {
                    val tag = bb.getInt(i)
                    // 'mvhd' = 0x6D766864
                    if (tag == 0x6D766864 && durationMs <= 0L) {
                        val version = buf[i + 4].toInt() and 0xFF
                        val flags = bb.getInt(i + 4) and 0x00FFFFFF
                        if (flags == 0 && (version == 0 || version == 1)) {
                            val timescale: Long
                            val duration: Long
                            if (version == 0) {
                                timescale = bb.getInt(i + 16).toLong() and 0xFFFFFFFFL
                                duration = bb.getInt(i + 20).toLong() and 0xFFFFFFFFL
                            } else {
                                timescale = bb.getInt(i + 24).toLong() and 0xFFFFFFFFL
                                duration = bb.getLong(i + 28)
                            }
                            if (timescale > 0L && duration > 0L && duration < timescale * 36000L) {
                                durationMs = (duration * 1000L) / timescale
                            }
                        }
                    }
                    // 'trak' = 0x7472616B
                    if (tag == 0x7472616B) {
                        val boxSize = bb.getInt(i - 4)
                        if (boxSize in 64..read) {
                            inVideoTrack = false
                        }
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
                            val vf = bb.getInt(i + 4)
                            if (vf == 0) {
                                val count = bb.getInt(i + 12)
                                if (count in 1..100000) {
                                    videoFrames = count
                                }
                            }
                        }
                        // 'stts' = 0x73747473
                        if (tag == 0x73747473 && sttsPos == -1) {
                            val vf = bb.getInt(i + 4)
                            if (vf == 0) {
                                sttsPos = i
                            }
                        }
                        // 'ctts' = 0x63747473
                        if (tag == 0x63747473 && cttsPos == -1) {
                            val flags = bb.getInt(i + 4) and 0x00FFFFFF
                            val ver = buf[i + 4].toInt() and 0xFF
                            if (flags == 0 && (ver == 0 || ver == 1)) {
                                cttsPos = i
                            }
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
                            if (sc <= 0 || sc > 100000) break
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
                            if (sc <= 0 || sc > 100000) break
                            var k = 0
                            while (k < sc && sampleIdx < videoFrames) {
                                pts[sampleIdx] += offset
                                sampleIdx++
                                k++
                            }
                            e++
                        }
                    }

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
     * Для всех роликов <= 50 кадр/с сохраняются 100% кадров (24/25/30 fps без пропусков!).
     * В буфере переупорядочивания хранятся уже сжатые LZW-байты (~300 КБ), а не 16.6 МБ Bitmap,
     * что полностью исключает нехватку памяти и потерю кадров.
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
        val buffers = JCodecReusableBuffers()

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

                // Сохраняем 100% кадров для всех роликов 24, 25 и 30 fps (step = 1)!
                // Только для 60 fps роликов берём каждый 2-й кадр (плавные 30 fps).
                val originalFps = (totalRawToRead * 1000.0) / clipDurationMs
                val step = if (originalFps > 50.0) 2 else 1

                val totalFramesToEncode = ((totalRawToRead + step - 1) / step).coerceAtLeast(1)
                encoder.setRepeat(0)

                // Буфер переупорядочивания хранит сжатые LZW-байты кадра (~300 КБ), а не гигантские Bitmap!
                val reorderBuffer = TreeMap<Int, ByteArray>()
                var readCount = 0
                var nextGifOrderToEmit = 0
                var encodedCount = 0

                val maxReadLimit = min(meta.videoFrames, totalRawToRead + 8)

                while (readCount < maxReadLimit) {
                    val pic: Picture = grab.nativeFrame ?: break
                    val dispIdx = if (readCount < meta.displayRank.size) meta.displayRank[readCount] else readCount

                    if (dispIdx < totalRawToRead && dispIdx % step == 0) {
                        val gifOrder = dispIdx / step
                        val bitmap = jcodecPictureToBitmap(pic, targetWidth, buffers)
                        if (bitmap != null) {
                            val lzwBytes = encoder.encodeFrameToLzwBytes(bitmap)
                            bitmap.recycle()
                            reorderBuffer.put(gifOrder, lzwBytes)
                        }
                    }

                    // Выдаём в GIF-поток кадры строго по порядку возрастания gifOrder (0, 1, 2, 3...)
                    while (reorderBuffer.isNotEmpty()) {
                        val firstKey = reorderBuffer.firstKey()
                        if (firstKey <= nextGifOrderToEmit || reorderBuffer.size > 16) {
                            val entry = reorderBuffer.pollFirstEntry()!!
                            val lzwBytes = entry.value

                            val startCs = Math.round((encodedCount * clipDurationMs) / (10.0 * totalFramesToEncode)).toInt()
                            val endCs = Math.round(((encodedCount + 1) * clipDurationMs) / (10.0 * totalFramesToEncode)).toInt()
                            val delayCs = (endCs - startCs).coerceIn(2, 100)
                            encoder.setDelay(delayCs * 10)

                            encoder.writePreEncodedFrame(lzwBytes)
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
                    val lzwBytes = entry.value

                    val startCs = Math.round((encodedCount * clipDurationMs) / (10.0 * totalFramesToEncode)).toInt()
                    val endCs = Math.round(((encodedCount + 1) * clipDurationMs) / (10.0 * totalFramesToEncode)).toInt()
                    val delayCs = (endCs - startCs).coerceIn(2, 100)
                    encoder.setDelay(delayCs * 10)

                    encoder.writePreEncodedFrame(lzwBytes)
                    encodedCount++

                    val p = (35 + (encodedCount * 60 / totalFramesToEncode)).coerceIn(35, 95)
                    kotlinx.coroutines.runBlocking(Dispatchers.Main) {
                        onProgress(p)
                    }
                }

                return encodedCount
            }

            // Резервный потоковый режим, если в MP4 нестандартный заголовок
            val compressedFrames = mutableListOf<ByteArray>()
            var readCount = 0
            while (readCount < maxFrames) {
                val pic: Picture = grab.nativeFrame ?: break
                val bitmap = jcodecPictureToBitmap(pic, targetWidth, buffers)
                if (bitmap != null) {
                    val lzwBytes = encoder.encodeFrameToLzwBytes(bitmap)
                    bitmap.recycle()
                    compressedFrames.add(lzwBytes)
                }
                readCount++
            }

            if (compressedFrames.isEmpty()) return 0

            val clipDurationMs = if (fullVideoDurationMs > 0L) {
                min(fullVideoDurationMs, maxDurationSec * 1000L).coerceAtLeast(200L)
            } else {
                ((readCount * 1000L) / 24L).coerceAtLeast(200L)
            }

            val currentFps = (compressedFrames.size * 1000.0) / clipDurationMs
            val postStep = if (currentFps > 50.0) 2 else 1

            val framesToWrite = mutableListOf<ByteArray>()
            for ((index, bytes) in compressedFrames.withIndex()) {
                if (index % postStep == 0) {
                    framesToWrite.add(bytes)
                }
            }

            val totalFrames = framesToWrite.size
            if (totalFrames == 0) return 0

            encoder.setRepeat(0)
            for ((index, lzwBytes) in framesToWrite.withIndex()) {
                val startCs = Math.round((index * clipDurationMs) / (10.0 * totalFrames)).toInt()
                val endCs = Math.round(((index + 1) * clipDurationMs) / (10.0 * totalFrames)).toInt()
                val delayCs = (endCs - startCs).coerceIn(2, 100)
                encoder.setDelay(delayCs * 10)

                encoder.writePreEncodedFrame(lzwBytes)

                val p = 35 + ((index + 1) * 60 / totalFrames)
                kotlinx.coroutines.runBlocking(Dispatchers.Main) {
                    onProgress(p)
                }
            }
            return totalFrames
        } finally {
            buffers.rgbPic = null
            buffers.pixels = IntArray(0)
            try { NIOUtils.closeQuietly(channel) } catch (ignored: Throwable) {}
        }
    }

    /**
     * Конвертация Picture из JCodec 0.2.5 в Android ARGB_8888 Bitmap с переиспользованием буферов памяти.
     * Если задан режим HD 720p (targetShortSide > 0), масштабирование выполняется напрямую при чтении байтов,
     * без создания гигантского 1920x2160 промежуточного Bitmap!
     */
    private fun jcodecPictureToBitmap(
        src: Picture,
        targetShortSide: Int,
        buffers: JCodecReusableBuffers
    ): Bitmap? {
        return try {
            val rgbPic: Picture
            if (src.color == ColorSpace.RGB) {
                rgbPic = src
            } else {
                val transform = ColorUtil.getTransform(src.color, ColorSpace.RGB)
                val existing: Picture? = buffers.rgbPic
                val targetPic: Picture = if (existing != null && existing.width == src.width && existing.height == src.height) {
                    existing
                } else {
                    val created: Picture = Picture.create(src.width, src.height, ColorSpace.RGB)
                    buffers.rgbPic = created
                    created
                }
                transform.transform(src, targetPic)
                rgbPic = targetPic
            }

            val w = rgbPic.width
            val h = rgbPic.height
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

            val minSide = min(cropW, cropH)
            val maxSide = max(cropW, cropH)
            val scale = if (targetShortSide > 0 && (minSide > targetShortSide || maxSide > 1280)) {
                min(targetShortSide.toFloat() / minSide.toFloat(), 1280f / maxSide.toFloat()).coerceAtMost(1.0f)
            } else {
                1.0f
            }

            val outW = if (scale < 1.0f) (((cropW * scale).toInt() + 1) / 2 * 2).coerceAtLeast(2) else cropW
            val outH = if (scale < 1.0f) (((cropH * scale).toInt() + 1) / 2 * 2).coerceAtLeast(2) else cropH

            val neededPixels = outW * outH
            if (buffers.pixels.size < neededPixels) {
                buffers.pixels = IntArray(neededPixels)
            }
            val pixels = buffers.pixels
            val bytes = rgbPic.data[0]
            val limit = bytes.size

            if (outW == w && outH == h && cropX == 0 && cropY == 0) {
                var bi = 0
                for (i in 0 until neededPixels) {
                    if (bi + 2 < limit) {
                        val r = (bytes[bi++].toInt() + 128).coerceIn(0, 255)
                        val g = (bytes[bi++].toInt() + 128).coerceIn(0, 255)
                        val b = (bytes[bi++].toInt() + 128).coerceIn(0, 255)
                        pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                    } else {
                        pixels[i] = -0x1000000
                    }
                }
            } else {
                var outIdx = 0
                for (oy in 0 until outH) {
                    val sy = cropY + (oy * cropH) / outH
                    val rowStart = sy * w
                    for (ox in 0 until outW) {
                        val sx = cropX + (ox * cropW) / outW
                        val bi = (rowStart + sx) * 3
                        if (bi + 2 < limit) {
                            val r = (bytes[bi].toInt() + 128).coerceIn(0, 255)
                            val g = (bytes[bi + 1].toInt() + 128).coerceIn(0, 255)
                            val b = (bytes[bi + 2].toInt() + 128).coerceIn(0, 255)
                            pixels[outIdx++] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                        } else {
                            pixels[outIdx++] = -0x1000000
                        }
                    }
                }
            }

            Bitmap.createBitmap(pixels, 0, outW, outW, outH, Bitmap.Config.ARGB_8888)
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
                    val scaled = scaleBitmapIfNeeded(toUse, maxDimension)

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
