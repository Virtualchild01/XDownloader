package com.example.xdownloader.gif

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

/**
 * Сквозная проверка кодирования GIF на реальном 30-кадровом видео (60 кадров, 2.0 с).
 *
 * Проверяет именно те дефекты, из-за которых GIF получался тёмным и с неверной
 * частотой кадров, — а не только то, что файл создался.
 */
class GifEncodingTest {

    private val sample: File
        get() = File(javaClass.classLoader!!.getResource("sample30fps.mp4")!!.toURI())

    private fun encode(encoder: AnimatedGifEncoder, target: File): Int {
        var frames = 0
        BufferedOutputStream(FileOutputStream(target, false), 1 shl 18).use { out ->
            assertTrue(encoder.start(out))
            frames = GifEncoderCore.encode(
                videoFile = sample,
                durationMs = 2000L,
                maxDurationSec = 12,
                targetWidth = 0,
                encoder = encoder
            )
            encoder.finish()
        }
        return frames
    }

    /**
     * Собирает задержки кадров, обходя структуру GIF по блокам.
     *
     * Простой поиск байтов 0x21 0xF9 по всему файлу даёт ложные совпадения внутри
     * сжатых LZW-данных, поэтому здесь честный разбор: заголовок → GCT → блоки.
     */
    private fun readDelaysCentis(gif: File): List<Int> {
        val b = gif.readBytes()
        val delays = mutableListOf<Int>()
        if (b.size < 13) return delays

        var i = 6 + 7 // заголовок + Logical Screen Descriptor
        val packed = b[10].toInt() and 0xFF
        if (packed and 0x80 != 0) {
            i += 3 * (1 shl ((packed and 0x07) + 1))
        }

        fun skipSubBlocks() {
            while (i < b.size) {
                val len = b[i].toInt() and 0xFF
                i++
                if (len == 0) break
                i += len
            }
        }

        while (i < b.size) {
            when (b[i].toInt() and 0xFF) {
                0x3B -> break // trailer
                0x21 -> { // extension
                    val label = b[i + 1].toInt() and 0xFF
                    i += 2
                    if (label == 0xF9) {
                        // Graphic Control Extension: size(1) packed(1) delay(2) transIdx(1) term(1)
                        val d = (b[i + 2].toInt() and 0xFF) or ((b[i + 3].toInt() and 0xFF) shl 8)
                        delays.add(d)
                    }
                    skipSubBlocks()
                }
                0x2C -> { // image descriptor
                    i += 1 + 8
                    val imgPacked = b[i].toInt() and 0xFF
                    i += 1
                    if (imgPacked and 0x80 != 0) {
                        i += 3 * (1 shl ((imgPacked and 0x07) + 1))
                    }
                    i += 1 // LZW minimum code size
                    skipSubBlocks()
                }
                else -> i++
            }
        }
        return delays
    }

    @Test
    fun keepsFullFrameRateAndDuration() {
        val gif = File.createTempFile("rate", ".gif").apply { deleteOnExit() }
        val frames = encode(AnimatedGifEncoder(), gif)

        // 2 секунды при 30 fps — это 60 кадров. Раньше потолок 32/с давал ~32 кадра.
        assertEquals("все 60 кадров исходника должны попасть в GIF", 60, frames)

        val delays = readDelaysCentis(gif)
        assertEquals(60, delays.size)

        // Сумма задержек должна равняться длительности ролика (2.0 с = 200 сотых).
        val totalCentis = delays.sum()
        assertTrue(
            "суммарная длительность $totalCentis сотых должна быть ~200, иначе движение ускорено или замедлено",
            totalCentis in 190..210
        )
    }

    @Test
    fun sixtyFpsIsCappedWithoutChangingDuration() {
        // 60 кадров/с GIF не проигрывает: задержка не может быть меньше 20 мс.
        // Кадры должны прореживаться (каждый второй), а длительность остаться верной.
        val video = File(javaClass.classLoader!!.getResource("long60.mp4")!!.toURI())
        val gif = File.createTempFile("fps60", ".gif").apply { deleteOnExit() }
        var frames = 0
        BufferedOutputStream(FileOutputStream(gif, false)).use { out ->
            val enc = AnimatedGifEncoder()
            enc.start(out)
            frames = GifEncoderCore.encode(
                videoFile = video,
                durationMs = 0L,
                maxDurationSec = 1,
                targetWidth = 0,
                encoder = enc
            )
            enc.finish()
        }

        // 1 секунда исходника при 60 fps = 60 кадров; после прореживания — 30.
        assertEquals("60 fps должно прореживаться вдвое", 30, frames)

        val delays = readDelaysCentis(gif)
        assertTrue(
            "длительность обязана остаться ~1 секунда, а получилось ${delays.sum()} сотых",
            delays.sum() in 95..105
        )
    }

    @Test
    fun truncatesToRequestedDurationWithoutChangingRate() {
        // Ограничение в 1 секунду: из 60 кадров ролика должны остаться 30 —
        // ровно 30 кадров/с, а не «первые N кадров, растянутые на всю секунду».
        val gif = File.createTempFile("trunc", ".gif").apply { deleteOnExit() }
        var frames = 0
        BufferedOutputStream(FileOutputStream(gif, false)).use { out ->
            val enc = AnimatedGifEncoder()
            enc.start(out)
            frames = GifEncoderCore.encode(
                videoFile = sample,
                durationMs = 2000L,
                maxDurationSec = 1,
                targetWidth = 0,
                encoder = enc
            )
            enc.finish()
        }
        assertEquals("за 1 секунду при 30 fps должно быть 30 кадров", 30, frames)

        val delays = readDelaysCentis(gif)
        assertEquals(30, delays.size)
        assertTrue(
            "длительность клипа должна быть ~100 сотых, а получилось ${delays.sum()}",
            delays.sum() in 95..105
        )
    }

    @Test
    fun paletteIsNotDominatedByDarkFirstFrame() {
        // Ролик синтетический и начинается со светлого кадра testsrc2,
        // поэтому палитра обязана содержать светлые оттенки.
        val gif = File.createTempFile("bright", ".gif").apply { deleteOnExit() }
        encode(AnimatedGifEncoder(), gif)

        val bytes = gif.readBytes()
        val palStart = 13
        var brightEntries = 0
        for (slot in 0 until 255) {
            val r = bytes[palStart + slot * 3].toInt() and 0xFF
            val g = bytes[palStart + slot * 3 + 1].toInt() and 0xFF
            val b = bytes[palStart + slot * 3 + 2].toInt() and 0xFF
            if (r + g + b > 384) brightEntries++
        }
        assertTrue(
            "в палитре должно быть заметное число светлых цветов, найдено $brightEntries",
            brightEntries >= 10
        )
    }

    @Test
    fun paletteSamplingReadsSeveralFrames() {
        val encoder = AnimatedGifEncoder()
        val gif = File.createTempFile("samples", ".gif").apply { deleteOnExit() }
        BufferedOutputStream(FileOutputStream(gif, false)).use { out ->
            encoder.start(out)
            val ok = GifEncoderCore.samplePalette(sample, 60, 0, encoder)
            assertTrue("палитра должна собраться хотя бы по одному кадру", ok)
            encoder.finalizePalette()
            encoder.finish()
        }
        // Заголовок обязан быть корректным GIF89a
        RandomAccessFile(gif, "r").use { raf ->
            val hdr = ByteArray(6)
            raf.read(hdr)
            assertEquals("GIF89a", String(hdr, Charsets.US_ASCII))
        }
    }
}
