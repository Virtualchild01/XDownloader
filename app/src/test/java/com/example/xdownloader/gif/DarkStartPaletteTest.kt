package com.example.xdownloader.gif

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream

/**
 * Регрессия на «тёмный GIF».
 *
 * Ролик darkstart.mp4 начинается с чёрного кадра и выходит из темноты за 0.5 с.
 * Старый код строил палитру по первому кадру, поэтому палитра почти целиком
 * состояла из чёрных оттенков и весь GIF получался тёмным. Здесь проверяется,
 * что палитра, собранная по кадрам по всему ролику, содержит светлые цвета.
 */
class DarkStartPaletteTest {

    private fun resource(name: String): File =
        File(javaClass.classLoader!!.getResource(name)!!.toURI())

    /**
     * Сколько СЦЕННЫХ слотов палитры светлее середины (сумма каналов > 384).
     *
     * Считаем только слоты 0..215. Слоты 216..254 — это фиксированные
     * хроматические акценты (белый, жёлтый, голубой и т. п.), они есть в палитре
     * всегда и к цветам ролика отношения не имеют.
     */
    private fun brightSceneSlots(gif: File): Int {
        val b = gif.readBytes()
        val i = 13
        val packed = b[10].toInt() and 0xFF
        if (packed and 0x80 == 0) return 0
        var bright = 0
        for (slot in 0 until 216) {
            val r = b[i + slot * 3].toInt() and 0xFF
            val g = b[i + slot * 3 + 1].toInt() and 0xFF
            val bl = b[i + slot * 3 + 2].toInt() and 0xFF
            if (r + g + bl > 384) bright++
        }
        return bright
    }

    private fun encodeWith(video: File, sampleCount: Int): File {
        val gif = File.createTempFile("pal", ".gif").apply { deleteOnExit() }
        BufferedOutputStream(FileOutputStream(gif, false)).use { out ->
            val enc = AnimatedGifEncoder()
            enc.start(out)
            val meta = GifConverter.parseMp4Meta(video)
            // sampleCount == 1 воспроизводит старое поведение: палитра по первому кадру
            GifEncoderCore.samplePalette(video, if (sampleCount == 1) 1 else meta.videoFrames, 0, enc)
            enc.finalizePalette()
            enc.finish()
        }
        return gif
    }

    @Test
    fun multiFramePaletteSeesBrightColorsThatFirstFrameMisses() {
        val video = resource("darkstart.mp4")

        val firstFrameOnly = encodeWith(video, 1)
        val multiFrame = encodeWith(video, 0)

        val brightFirst = brightSceneSlots(firstFrameOnly)
        val brightMulti = brightSceneSlots(multiFrame)

        // Первый кадр чёрный, поэтому палитра по нему почти без светлых сценных цветов.
        assertTrue(
            "ожидалось, что палитра по первому (чёрному) кадру почти не содержит светлых цветов, а их $brightFirst",
            brightFirst <= 6
        )
        // Палитра по всему ролику обязана «увидеть» светлую часть.
        assertTrue(
            "палитра по нескольким кадрам должна содержать светлые цвета, а их $brightMulti",
            brightMulti >= 20
        )
        assertTrue(
            "многокадровая палитра обязана быть светлее однокадровой ($brightMulti против $brightFirst)",
            brightMulti > brightFirst
        )
    }

    @Test
    fun encodedGifIsNotDark() {
        val video = resource("darkstart.mp4")
        val gif = File.createTempFile("dark", ".gif").apply { deleteOnExit() }
        var frames = 0
        BufferedOutputStream(FileOutputStream(gif, false)).use { out ->
            val enc = AnimatedGifEncoder()
            enc.start(out)
            frames = GifEncoderCore.encode(video, 2000L, 12, 0, enc) { }
            enc.finish()
        }
        assertEquals("в GIF должны попасть все 60 кадров ролика", 60, frames)
        assertTrue(
            "палитра готового GIF должна содержать светлые цвета",
            brightSceneSlots(gif) >= 20
        )
    }
}
