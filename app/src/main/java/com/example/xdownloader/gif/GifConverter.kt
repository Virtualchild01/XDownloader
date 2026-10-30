package com.example.xdownloader.gif

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.bumptech.glide.Glide
import com.bumptech.glide.request.RequestOptions
import com.example.xdownloader.data.DownloadStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.util.Arrays
import kotlin.math.max
import kotlin.math.min

object GifConverter {

    data class Mp4TrackMeta(
        val durationMs: Long,
        val videoFrames: Int,
        val displayRank: IntArray
    )

    /**
     * Масштабирует Bitmap до targetShortSide (720p) БЕЗ автоматического вызова recycle() на исходном bitmap,
     * чтобы вызывающий код мог безопасно хранить lastValid без риска IllegalStateException (recycled bitmap)!
     */
    private fun scaleBitmapSafe(bitmap: Bitmap, targetShortSide: Int): Bitmap {
        if (targetShortSide <= 0 || bitmap.isRecycled) return bitmap
        val w = bitmap.width
        val h = bitmap.height
        val minSide = min(w, h)
        val maxSide = max(w, h)
        if (minSide <= targetShortSide && maxSide <= 1280) return bitmap

        val scale = min(targetShortSide.toFloat() / minSide.toFloat(), 1280f / maxSide.toFloat()).coerceAtMost(1.0f)
        val newW = (((w * scale).toInt() + 1) / 2 * 2).coerceAtLeast(2)
        val newH = (((h * scale).toInt() + 1) / 2 * 2).coerceAtLeast(2)
        if (newW == w && newH == h) return bitmap

        return try {
            Bitmap.createScaledBitmap(bitmap, newW, newH, true)
        } catch (e: Throwable) {
            bitmap
        }
    }

    /**
     * Проверяет целостность созданного GIF-файла:
     * - ненулевой размер (> 800 байт)
     * - корректный заголовок "GIF89a"
     * - корректный завершающий байт 0x3B (';')
     */
    private fun isValidGifFile(file: File): Boolean {
        if (!file.exists() || file.length() <= 800L) return false
        var raf: RandomAccessFile? = null
        return try {
            raf = RandomAccessFile(file, "r")
            val hdr = ByteArray(6)
            if (raf.read(hdr) != 6) return false
            val headerStr = String(hdr, Charsets.US_ASCII)
            if (headerStr != "GIF89a" && headerStr != "GIF87a") return false
            raf.seek(raf.length() - 1L)
            val lastByte = raf.read()
            lastByte == 0x3B
        } catch (e: Throwable) {
            false
        } finally {
            try { raf?.close() } catch (ignored: Throwable) {}
        }
    }

    /**
     * Выполняет попытку кодирования GIF в изолированный временный файл tempGifFile.
     * Если декодер выбросил исключение или вернул 0 кадров, временный файл немедленно очищается,
     * чтобы следующий резервный декодер никогда не дописывал байты поверх оборванного потока!
     */
    private fun runIsolatedEncoderAttempt(
        tempGifFile: File,
        block: (AnimatedGifEncoder) -> Int
    ): Int {
        var bufferedOut: BufferedOutputStream? = null
        return try {
            if (tempGifFile.exists()) tempGifFile.delete()
            bufferedOut = BufferedOutputStream(FileOutputStream(tempGifFile, false), 262144)
            val encoder = AnimatedGifEncoder()
            encoder.setDelay(40)
            encoder.setRepeat(0)
            encoder.setQuality(5)
            if (!encoder.start(bufferedOut)) {
                try { bufferedOut.close() } catch (ignored: Throwable) {}
                if (tempGifFile.exists()) tempGifFile.delete()
                return 0
            }

            val frames = block(encoder)
            if (frames > 0) {
                encoder.finish()
                bufferedOut.flush()
                bufferedOut.close()
                bufferedOut = null
                if (isValidGifFile(tempGifFile)) {
                    frames
                } else {
                    if (tempGifFile.exists()) tempGifFile.delete()
                    0
                }
            } else {
                try { bufferedOut.close() } catch (ignored: Throwable) {}
                bufferedOut = null
                if (tempGifFile.exists()) tempGifFile.delete()
                0
            }
        } catch (e: Throwable) {
            e.printStackTrace()
            try { bufferedOut?.close() } catch (ignored: Throwable) {}
            if (tempGifFile.exists()) tempGifFile.delete()
            0
        }
    }

    /**
     * Конвертирует локальный видеофайл MP4 в плавный анимированный GIF:
     * - Сохраняет 100% кадров (полные 24/25/30 fps без урезания частоты кадров)
     * - Упорядочивает двунаправленные B-кадры H.264 строго по времени показа (PTS = DTS + CTTS)
     * - Кодирует в изолированный проверенный временный файл и только после проверки целостности
     *   (GIF89a ... 0x3B) публикует готовый файл в папку «Загрузки» (MediaStore.Downloads).
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

        onProgress(35)

        val safeTitle = targetTitle
            .filter { it.isLetterOrDigit() || it == '_' || it == '-' || it == '.' || it == ' ' }
            .trim()
            .replace(Regex("\\s+"), "_")
            .take(45)
            .ifEmpty { "X_Post" }
        val fileName = "${safeTitle}_anim.gif"

        val tempGifFile = File(context.cacheDir, "temp_gif_${System.currentTimeMillis()}.gif")
        val diagLog = StringBuilder()
        var framesAdded = 0

        try {
            // УРОВЕНЬ 1 (Основной): Прямой потоковый декодер JCodec (без Bitmap, с пулом буферов и PTS-порядком)
            framesAdded = runIsolatedEncoderAttempt(tempGifFile) { encoder ->
                try {
                    extractWithJCodec(
                        videoFile = videoFile,
                        expectedDurationMs = expectedDurationMs,
                        maxDurationSec = maxDurationSec,
                        targetWidth = maxDimension,
                        encoder = encoder,
                        onProgress = onProgress
                    )
                } catch (e: Throwable) {
                    diagLog.append("JCodec: ").append(e.message ?: e.javaClass.simpleName).append("; ")
                    0
                }
            }

            // УРОВЕНЬ 2 (Резервный): Системный MediaMetadataRetriever в чистый временный файл
            if (framesAdded == 0) {
                framesAdded = runIsolatedEncoderAttempt(tempGifFile) { encoder ->
                    try {
                        extractWithRetriever(
                            context = context,
                            videoFile = videoFile,
                            expectedDurationMs = expectedDurationMs,
                            maxDurationSec = maxDurationSec,
                            fps = fps,
                            maxDimension = maxDimension,
                            encoder = encoder,
                            diagLog = diagLog
                        ) { p ->
                            onProgress(p)
                        }
                    } catch (e: Throwable) {
                        diagLog.append("MMR: ").append(e.message ?: e.javaClass.simpleName).append("; ")
                        0
                    }
                }
            }

            // УРОВЕНЬ 3 (Резервный): Декодер кадров Glide (с отключённым HARDWARE config) в чистый временный файл
            if (framesAdded == 0) {
                framesAdded = runIsolatedEncoderAttempt(tempGifFile) { encoder ->
                    try {
                        extractWithGlide(
                            context = context,
                            videoFile = videoFile,
                            expectedDurationMs = expectedDurationMs,
                            maxDurationSec = maxDurationSec,
                            fps = fps,
                            maxDimension = maxDimension,
                            encoder = encoder
                        ) { p ->
                            onProgress(p)
                        }
                    } catch (e: Throwable) {
                        diagLog.append("Glide: ").append(e.message ?: e.javaClass.simpleName).append("; ")
                        0
                    }
                }
            }

            if (framesAdded == 0 || !isValidGifFile(tempGifFile)) {
                if (tempGifFile.exists()) tempGifFile.delete()
                val msg = if (diagLog.isNotBlank()) {
                    "Сбой декодирования (" + diagLog.toString().trim() + ", размер: " + videoFile.length() + " Б)"
                } else {
                    "Не удалось извлечь кадры из видеофайла (размер: " + videoFile.length() + " Б)"
                }
                return@withContext Pair(null, msg)
            }

            // GIF — это изображение, поэтому он кладётся в Pictures/XDownloader:
            // платформа не разрешает писать картинки в Movies (см. DownloadStorage).
            val savedUri = DownloadStorage.save(context, tempGifFile, fileName)
            if (savedUri == null) {
                return@withContext Pair(null, "Не удалось сохранить готовый GIF в Pictures/XDownloader")
            }

            onProgress(100)

            Pair(savedUri, null)
        } catch (e: Exception) {
            e.printStackTrace()
            Pair(null, e.message ?: "Сбой при конвертации")
        } finally {
            if (tempGifFile.exists()) {
                tempGifFile.delete()
            }
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
     * Кодирование через JCodec: переупорядочивание B-кадров, палитра по нескольким
     * кадрам и запись кадров с правильными задержками.
     *
     * Сама логика живёт в [GifEncoderCore] — она не трогает Android API, поэтому
     * покрыта обычными JUnit-тестами на реальном видео. Здесь остаётся только
     * переключение прогресса на главный поток.
     */
    private fun extractWithJCodec(
        videoFile: File,
        expectedDurationMs: Long,
        maxDurationSec: Int,
        targetWidth: Int,
        encoder: AnimatedGifEncoder,
        onProgress: (Int) -> Unit
    ): Int {
        var lastReported = -1
        val frames = GifEncoderCore.encode(
            videoFile = videoFile,
            durationMs = expectedDurationMs,
            maxDurationSec = maxDurationSec,
            targetWidth = targetWidth,
            encoder = encoder
        ) { p ->
            // Не дёргаем колбэк на каждый кадр: обновляем раз в 5%.
            //
            // Раньше здесь стоял runBlocking(Dispatchers.Main): конвертация шла в
            // lifecycleScope активити, и колбэк надо было переключить на UI-поток.
            // Теперь работа живёт в сервисе и не должна зависеть от главного потока
            // (при свёрнутом приложении он может быть занят) — вызываем напрямую,
            // а поток выбирает подписчик.
            if (p - lastReported >= 5) {
                lastReported = p
                onProgress(p)
            }
        }
        if (frames > 0) {
            onProgress(95)
        }
        return frames
    }

    /**
     * Извлечение кадров через MediaMetadataRetriever (резервный метод).
     * Полностью защищён от повторного вызова recycle() на кэшированном кадре lastValid!
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

            // Частоту берём из самого ролика, а не из параметра fps = 24.
            // Раньше 30-кадровый ролик сэмплировался на 24 кадра/с: часть кадров
            // терялась, и движение выходило рваным. Верхняя граница — предел GIF.
            val sourceFps = if (meta.videoFrames > 0 && durationMs > 0L) {
                meta.videoFrames * 1000.0 / durationMs
            } else {
                fps.toDouble()
            }
            val effectiveFps = sourceFps.coerceIn(1.0, GifEncoderCore.MAX_FRAMES_PER_SECOND.toDouble())
            val wanted = Math.round(actualDurationMs * effectiveFps / 1000.0).toInt()
            val totalFrames = wanted
                .coerceAtMost(if (meta.videoFrames > 0) meta.videoFrames else Int.MAX_VALUE)
                .coerceIn(2, 600)

            var lastValidScaled: Bitmap? = null

            for (i in 0 until totalFrames) {
                val timeUs = (i * actualDurationMs * 1000L) / totalFrames
                var rawFrame: Bitmap? = null

                try {
                    rawFrame = mmr.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
                } catch (ignored: Throwable) {}

                if (rawFrame == null) {
                    try {
                        rawFrame = mmr.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    } catch (ignored: Throwable) {}
                }

                if (rawFrame != null) {
                    val scaled = scaleBitmapSafe(rawFrame, maxDimension)
                    if (scaled !== rawFrame) {
                        rawFrame.recycle()
                    }
                    if (lastValidScaled != null && lastValidScaled !== scaled && !lastValidScaled.isRecycled) {
                        lastValidScaled.recycle()
                    }
                    lastValidScaled = scaled
                }

                val toEncode = lastValidScaled
                if (toEncode != null && !toEncode.isRecycled) {
                    val startCs = Math.round((i * actualDurationMs) / (10.0 * totalFrames)).toInt()
                    val endCs = Math.round(((i + 1) * actualDurationMs) / (10.0 * totalFrames)).toInt()
                    val delayCs = (endCs - startCs).coerceIn(2, 100)
                    encoder.setDelay(delayCs * 10)

                    if (encoder.addFrame(toEncode)) {
                        added++
                    }
                }

                val p = 35 + ((i + 1) * 60 / totalFrames)
                onProgress(p)
            }
            if (lastValidScaled != null && !lastValidScaled.isRecycled) {
                lastValidScaled.recycle()
            }
        } finally {
            try { retriever?.release() } catch (ignored: Throwable) {}
            try { pfd?.close() } catch (ignored: Throwable) {}
            try { fis?.close() } catch (ignored: Throwable) {}
        }

        return added
    }

    /**
     * Извлечение кадров через Glide (резервный метод) с отключённым HARDWARE Bitmap.
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

            // Частота — из ролика, а не зашитые 24 fps (иначе 30-кадровое видео
            // теряло кадры и движение получалось рваным).
            val sourceFps = if (meta.videoFrames > 0 && durationMs > 0L) {
                meta.videoFrames * 1000.0 / durationMs
            } else {
                fps.toDouble()
            }
            val effectiveFps = sourceFps.coerceIn(1.0, GifEncoderCore.MAX_FRAMES_PER_SECOND.toDouble())
            val wanted = Math.round(actualDurationMs * effectiveFps / 1000.0).toInt()
            val totalFrames = wanted
                .coerceAtMost(if (meta.videoFrames > 0) meta.videoFrames else Int.MAX_VALUE)
                .coerceIn(2, 600)
            var lastValid: Bitmap? = null

            for (i in 0 until totalFrames) {
                val timeUs = (i * actualDurationMs * 1000L) / totalFrames
                var frame: Bitmap? = null
                try {
                    val opts = RequestOptions.frameOf(timeUs).disallowHardwareConfig()
                    val req = Glide.with(context.applicationContext)
                        .asBitmap()
                        .load(videoFile)
                        .apply(opts)
                    frame = if (maxDimension > 0) {
                        req.submit(maxDimension, maxDimension).get()
                    } else {
                        req.submit().get()
                    }
                } catch (ignored: Throwable) {}

                if (frame != null) {
                    val scaled = scaleBitmapSafe(frame, maxDimension)
                    lastValid = scaled
                }

                val toUse = lastValid
                if (toUse != null && !toUse.isRecycled) {
                    val startCs = Math.round((i * actualDurationMs) / (10.0 * totalFrames)).toInt()
                    val endCs = Math.round(((i + 1) * actualDurationMs) / (10.0 * totalFrames)).toInt()
                    val delayCs = (endCs - startCs).coerceIn(2, 100)
                    encoder.setDelay(delayCs * 10)

                    if (encoder.addFrame(toUse)) {
                        added++
                    }
                }

                val p = 35 + ((i + 1) * 60 / totalFrames)
                onProgress(p)
            }
        } catch (e: Throwable) {
            e.printStackTrace()
        }
        return added
    }
}
