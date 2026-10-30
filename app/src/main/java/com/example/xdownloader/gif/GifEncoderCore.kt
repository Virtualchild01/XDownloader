package com.example.xdownloader.gif

import org.jcodec.api.FrameGrab
import org.jcodec.common.io.NIOUtils
import org.jcodec.common.io.SeekableByteChannel
import org.jcodec.common.model.ColorSpace
import org.jcodec.common.model.Picture
import org.jcodec.scale.ColorUtil
import java.io.File
import java.util.ArrayDeque
import java.util.TreeMap
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Кодирование кадров видео в GIF без единого Android-API.
 *
 * Раньше этот цикл жил внутри [GifConverter] вперемешку с MediaStore и переключением
 * на главный поток, поэтому его нельзя было проверить тестом. Здесь только JCodec,
 * палитра и LZW — всё это запускается обычным JUnit на JVM.
 */
object GifEncoderCore {

    /**
     * Потолок кадров в секунду. GIF хранит задержку в сотых долях секунды, и 20 мс
     * (50 кадров/с) — практический максимум. Раньше потолок был 32 кадра/с, из-за чего
     * 60-кадровый ролик терял половину кадров, а задержки растягивались на всю
     * длительность: движение замедлялось примерно вдвое.
     */
    const val MAX_FRAMES_PER_SECOND = 50

    /**
     * Сколько кадров усредняем в общую палитру. Палитра по одному первому кадру
     * делала GIF тёмным, если ролик начинается с чёрного кадра или с фейда из темноты.
     */
    const val SAMPLE_FRAME_COUNT = 12

    /** Кроп и масштаб кадра: что вырезать из исходника и какого размера писать в GIF. */
    private data class Geometry(
        val cropX: Int,
        val cropY: Int,
        val cropW: Int,
        val cropH: Int,
        val outW: Int,
        val outH: Int
    )

    /**
     * Кодирует видео в GIF.
     *
     * @param durationMs длительность ролика; если 0, берётся из MP4
     * @param maxDurationSec сколько секунд от начала ролика попадает в GIF
     * @param targetWidth желаемая короткая сторона (0 — без масштабирования)
     * @return сколько кадров записано (0 — не удалось)
     */
    fun encode(
        videoFile: File,
        durationMs: Long,
        maxDurationSec: Int,
        targetWidth: Int,
        encoder: AnimatedGifEncoder,
        onProgress: (Int) -> Unit = {}
    ): Int {
        val meta = GifConverter.parseMp4Meta(videoFile)
        val fullDurationMs = when {
            meta.durationMs > 0L -> meta.durationMs
            durationMs > 0L -> durationMs
            else -> return 0
        }
        if (meta.videoFrames <= 0) return 0

        val clipDurationMs = min(fullDurationMs, maxDurationSec * 1000L).coerceAtLeast(200L)
        val sourceFps = (meta.videoFrames * 1000.0) / fullDurationMs

        // Сколько исходных кадров реально попадает в клип.
        val framesInClip = min(
            meta.videoFrames,
            Math.round(clipDurationMs * sourceFps / 1000.0).toInt().coerceAtLeast(1)
        )

        // ceil, а не round: для 60 fps round(1.2) дал бы 1, то есть все 60 кадров в секунду.
        val step: Int = if (sourceFps > MAX_FRAMES_PER_SECOND) {
            ceil(sourceFps / MAX_FRAMES_PER_SECOND).toInt().coerceAtLeast(1)
        } else {
            1
        }
        val totalFramesToEncode = ((framesInClip + step - 1) / step).coerceAtLeast(1)

        // Палитра — по кадрам, разнесённым по КЛИПУ, а не по всему ролику: кадры за
        // пределами первых maxDurationSec в GIF не попадают, и их цвета только
        // разбавляли палитру. Плюс прогресс палитры теперь виден, а не «замирает» на 35%.
        if (!encoder.isPaletteBuilt()) {
            if (samplePalette(videoFile, framesInClip, targetWidth, encoder) { p ->
                onProgress((35 + p * 10 / 100).coerceIn(35, 45))
            }) {
                encoder.finalizePalette()
            }
        }
        encoder.setRepeat(0)

        // Задержка по номеру кадра, а не по числу уже записанных: если кадр потерялся,
        // суммарная длительность не «уплывает».
        fun delayCsFor(order: Int): Int {
            val startCs = Math.round((order * clipDurationMs) / (10.0 * totalFramesToEncode)).toInt()
            val endCs = Math.round(((order + 1) * clipDurationMs) / (10.0 * totalFramesToEncode)).toInt()
            return (endCs - startCs).coerceIn(2, 100)
        }

        var channel: SeekableByteChannel? = null
        var reusableRgbPic: Picture? = null
        val reorderBuffer = TreeMap<Int, ByteArray>()
        val framePool = ArrayDeque<ByteArray>()
        var encodedCount = 0

        try {
            channel = NIOUtils.readableChannel(videoFile)
            val grab = FrameGrab.createFrameGrab(channel)

            // Читаем с запасом: B-кадры приходят раньше своей позиции показа
            val maxReadLimit = min(meta.videoFrames, framesInClip + step * 8)
            var readCount = 0
            var nextGifOrderToEmit = 0

            while (readCount < maxReadLimit) {
                val pic: Picture = try {
                    grab.nativeFrame
                } catch (e: Throwable) {
                    null
                } ?: break

                val dispIdx = if (readCount < meta.displayRank.size) meta.displayRank[readCount] else readCount

                if (dispIdx < framesInClip && dispIdx % step == 0) {
                    val gifOrder = dispIdx / step
                    val rgbPic = convertPictureToRgb(pic, reusableRgbPic)
                    if (rgbPic != null) {
                        if (rgbPic !== pic) reusableRgbPic = rgbPic
                        val indices = quantizeFrame(
                            src = pic,
                            rgbPic = rgbPic,
                            targetShortSide = targetWidth,
                            encoder = encoder,
                            framePool = framePool
                        )
                        if (indices != null) {
                            val prev = reorderBuffer.put(gifOrder, indices)
                            if (prev != null) framePool.addLast(prev)
                        }
                    }
                }

                // Выдаём кадры строго по возрастанию gifOrder (0, 1, 2, 3...)
                while (reorderBuffer.isNotEmpty()) {
                    val firstKey = reorderBuffer.firstKey()
                    if (firstKey <= nextGifOrderToEmit || reorderBuffer.size > 16) {
                        val entry = reorderBuffer.pollFirstEntry()!!
                        encoder.setDelay(delayCsFor(encodedCount) * 10)
                        if (encoder.writeQuantizedFrame(entry.value)) encodedCount++
                        framePool.addLast(entry.value)
                        nextGifOrderToEmit = entry.key + 1
                        onProgress((45 + (encodedCount * 50 / totalFramesToEncode)).coerceIn(45, 95))
                    } else {
                        break
                    }
                }

                readCount++
            }

            // Остаток буфера — тоже в хронологическом порядке
            while (reorderBuffer.isNotEmpty()) {
                val entry = reorderBuffer.pollFirstEntry()!!
                encoder.setDelay(delayCsFor(encodedCount) * 10)
                if (encoder.writeQuantizedFrame(entry.value)) encodedCount++
                framePool.addLast(entry.value)
                onProgress((45 + (encodedCount * 50 / totalFramesToEncode)).coerceIn(45, 95))
            }

            framePool.clear()
        } catch (ignored: Throwable) {
        } finally {
            reusableRgbPic = null
            try { NIOUtils.closeQuietly(channel) } catch (ignored: Throwable) {}
        }
        return encodedCount
    }

    /**
     * Собирает цвета нескольких кадров, равномерно разнесённых по ролику.
     *
     * Способ чтения выбирается по стоимости. Точный seek в jcodec декодирует все
     * кадры от предыдущего ключевого, поэтому на длинном GOP (один I-кадр на весь
     * клип) 12 seek'ов по 12-секундному ролику стоят дороже, чем один честный
     * проход по клипу: замер на 720p/30fps/12с дал 16.6 с против 4.6 с. На частом
     * GOP (кадр в секунду) seek дешевле — 0.46 с против 5.8 с. Поэтому здесь
     * считается оценка по метаданным ключевых кадров, а не выбирается жёстко.
     *
     * @param onProgress 0..100 по числу собранных образцов
     * @return true, если палитра набрала хотя бы один кадр
     */
    fun samplePalette(
        videoFile: File,
        totalFrames: Int,
        targetShortSide: Int,
        encoder: AnimatedGifEncoder,
        onProgress: (Int) -> Unit = {}
    ): Boolean {
        if (totalFrames <= 0) return false

        val wanted = SAMPLE_FRAME_COUNT.coerceAtMost(totalFrames).coerceAtLeast(1)
        val picks = (0 until wanted)
            .map { (it.toLong() * totalFrames / wanted).toInt() }
            .distinct()

        val keyFrames = readKeyFrames(videoFile)
        val seekCost = picks.sumOf { p -> p - prevKeyFrameAt(p, keyFrames) + 1 }
        val useSequential = seekCost > totalFrames

        var channel: SeekableByteChannel? = null
        var reusableRgbPic: Picture? = null
        var sampled = 0

        // Возвращает true, если кадр добавлен в гистограмму. RGB-буфер переиспользуется.
        fun accumulate(pic: Picture): Boolean = try {
            val rgbPic = convertPictureToRgb(pic, reusableRgbPic)
            if (rgbPic != null) {
                if (rgbPic !== pic) reusableRgbPic = rgbPic
                val g = computeGeometry(pic, rgbPic, targetShortSide)
                encoder.accumulateJCodecRgb(
                    rgbPic.data[0], rgbPic.width, rgbPic.height,
                    g.cropX, g.cropY, g.cropW, g.cropH, g.outW, g.outH
                )
                true
            } else {
                false
            }
        } catch (ignored: Throwable) {
            false
        }

        try {
            channel = NIOUtils.readableChannel(videoFile)
            var grab: FrameGrab = FrameGrab.createFrameGrab(channel)

            if (useSequential) {
                // Один проход: seek не нужен, кадры идут подряд.
                var frameIdx = 0
                var pickIdx = 0
                val lastPick = picks.last()
                while (pickIdx < picks.size && encoder.canAddPaletteSample()) {
                    val pic = try { grab.nativeFrame } catch (e: Throwable) { null } ?: break
                    if (frameIdx == picks[pickIdx]) {
                        if (accumulate(pic)) {
                            sampled++
                            onProgress(sampled * 100 / picks.size)
                        }
                        pickIdx++
                    }
                    frameIdx++
                    if (frameIdx > lastPick) break
                }
            } else {
                for (frameIdx in picks) {
                    if (!encoder.canAddPaletteSample()) break
                    try {
                        grab = grab.seekToFramePrecise(frameIdx)
                        val pic = grab.nativeFrame ?: continue
                        if (accumulate(pic)) {
                            sampled++
                            onProgress(sampled * 100 / picks.size)
                        }
                    } catch (ignored: Throwable) {
                        // Один неудачный seek не должен обнулять уже собранную палитру
                    }
                }
            }
        } catch (ignored: Throwable) {
        } finally {
            reusableRgbPic = null
            try { NIOUtils.closeQuietly(channel) } catch (ignored: Throwable) {}
        }
        return sampled > 0
    }

    /**
     * Индексы ключевых кадров из метаданных трека (jcodec отдаёт их нулевыми).
     * `null` — метаданных нет, считаем, что seek стоит одного кадра.
     */
    private fun readKeyFrames(videoFile: File): IntArray? {
        var channel: SeekableByteChannel? = null
        return try {
            channel = NIOUtils.readableChannel(videoFile)
            FrameGrab.createFrameGrab(channel).videoTrack.meta.seekFrames
        } catch (ignored: Throwable) {
            null
        } finally {
            try { NIOUtils.closeQuietly(channel) } catch (ignored: Throwable) {}
        }
    }

    /** Ближайший ключевой кадр не позже [frameIdx]. */
    private fun prevKeyFrameAt(frameIdx: Int, keyFrames: IntArray?): Int {
        if (keyFrames == null || keyFrames.isEmpty()) return frameIdx
        var prev = keyFrames[0]
        for (k in keyFrames) {
            if (k <= frameIdx) prev = k else break
        }
        return prev
    }

    /**
     * Кроп по метаданным кадра и масштаб под [targetShortSide].
     * Общий для палитры и кадров: если размеры разойдутся, гистограмма не совпадёт с кадром.
     */
    private fun computeGeometry(src: Picture, rgbPic: Picture, targetShortSide: Int): Geometry {
        val w = rgbPic.width
        val h = rgbPic.height
        var cropX = 0
        var cropY = 0
        var cropW = w
        var cropH = h

        try {
            val cropObj = src.crop
            if (cropObj != null) {
                val cx = cropObj.x
                val cy = cropObj.y
                val cw = cropObj.width
                val ch = cropObj.height
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
        return Geometry(cropX, cropY, cropW, cropH, outW, outH)
    }

    /** Конвертирует кадр JCodec в RGB; результат переиспользуется между кадрами. */
    fun convertPictureToRgb(src: Picture, existingRgb: Picture?): Picture? {
        return try {
            if (src.color == ColorSpace.RGB) {
                src
            } else {
                val transform = ColorUtil.getTransform(src.color, ColorSpace.RGB)
                val targetPic: Picture = if (existingRgb != null &&
                    existingRgb.width == src.width && existingRgb.height == src.height
                ) {
                    existingRgb
                } else {
                    Picture.create(src.width, src.height, ColorSpace.RGB)
                }
                transform.transform(src, targetPic)
                targetPic
            }
        } catch (e: Throwable) {
            null
        }
    }

    private fun quantizeFrame(
        src: Picture,
        rgbPic: Picture,
        targetShortSide: Int,
        encoder: AnimatedGifEncoder,
        framePool: ArrayDeque<ByteArray>
    ): ByteArray? {
        return try {
            val g = computeGeometry(src, rgbPic, targetShortSide)
            val numPixels = g.outW * g.outH
            val rgbBytes = rgbPic.data[0]

            if (!encoder.isPaletteBuilt()) {
                encoder.initFromJCodecRgb(
                    rgbBytes, rgbPic.width, rgbPic.height,
                    g.cropX, g.cropY, g.cropW, g.cropH, g.outW, g.outH
                )
            }

            val pooled = framePool.pollFirst()
            val destIndices = if (pooled != null && pooled.size == numPixels) {
                pooled
            } else {
                ByteArray(numPixels)
            }

            encoder.quantizeJCodecRgbToIndices(
                rgbBytes, rgbPic.width, rgbPic.height,
                g.cropX, g.cropY, g.cropW, g.cropH, g.outW, g.outH, destIndices
            )
            destIndices
        } catch (e: Throwable) {
            null
        }
    }
}
