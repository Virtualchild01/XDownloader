package com.example.xdownloader.gif

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Проверка разбора MP4 на реальном файле.
 *
 * Эталон снят ffprobe: 60 кадров, 30 fps, длительность 2.000 с.
 * Если разбор возвращает 0 кадров или 0 мс, GifConverter уходит в резервную
 * ветку с фиксированными 25 fps — отсюда и «неправильная частота кадров».
 */
class Mp4MetaTest {

    private val sample: File
        get() = File(javaClass.classLoader!!.getResource("sample30fps.mp4")!!.toURI())

    @Test
    fun parsesFrameCountAndDuration() {
        val meta = GifConverter.parseMp4Meta(sample)

        assertEquals("кадров должно быть столько же, сколько в источнике", 60, meta.videoFrames)
        assertTrue(
            "длительность ~2000 мс, получено ${meta.durationMs}",
            meta.durationMs in 1900L..2100L
        )
    }

    @Test
    fun displayRankIsAPermutation() {
        val meta = GifConverter.parseMp4Meta(sample)

        assertEquals(60, meta.displayRank.size)
        // Порядок показа обязан быть перестановкой: каждый индекс ровно один раз.
        assertEquals(
            "displayRank должен быть перестановкой 0..59",
            (0 until 60).toSet(),
            meta.displayRank.toSet()
        )
        assertEquals(60, meta.displayRank.toSet().size)
    }

    @Test
    fun displayRankReordersBframes() {
        val meta = GifConverter.parseMp4Meta(sample)

        // В файле есть B-кадры, поэтому порядок декодирования не совпадает с показом.
        // Если массив оказался тождественным, переупорядочивание не работает.
        val identity = (0 until 60).toList()
        assertTrue(
            "ожидалось переупорядочивание B-кадров, а получен тождественный порядок",
            meta.displayRank.toList() != identity
        )
    }
}
