package com.example.xdownloader.gif

import org.jcodec.common.model.ColorSpace
import org.jcodec.common.model.Picture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Размеры GIF-кадра.
 *
 * Длинная сторона обязана упираться в 1280: замер полного декода показал, что
 * 3840x2160 проигрывается с запасом всего 1.5x реального времени на десктопе
 * (телефон примерно втрое медленнее), а 1280x720 — с 9x. Раньше ограничение
 * срабатывало только при заданном targetShortSide, поэтому «GIF (Оригинал)»
 * с 4K-ролика выдавал 3840x2160 на 31 МБ.
 */
class GifGeometryTest {

    private fun geometry(w: Int, h: Int, targetShortSide: Int): GifEncoderCore.Geometry {
        val pic = Picture.create(w, h, ColorSpace.RGB)
        return GifEncoderCore.computeGeometry(pic, pic, targetShortSide)
    }

    @Test
    fun capsLongSideAt1280EvenForOriginalOption() {
        // targetShortSide = 0 — это вариант «Оригинал», но 4K всё равно ужимается.
        val g = geometry(3840, 2160, 0)
        assertEquals("длинная сторона обязана упираться в 1280", 1280, maxOf(g.outW, g.outH))
        assertTrue("пропорции должны сохраняться", g.outW * 9 / 16 == g.outH || kotlin.math.abs(g.outW.toDouble() / g.outH - 16.0 / 9.0) < 0.02)
    }

    @Test
    fun capsFullHdToo() {
        val g = geometry(1920, 1080, 0)
        assertEquals(1280, maxOf(g.outW, g.outH))
    }

    @Test
    fun leaves720pUntouched() {
        // 1280x720 уже в пределах лимита: «Оригинал» не должен ничего пересчитывать,
        // иначе каждый GIF потеряет качество без причины.
        val g = geometry(1280, 720, 0)
        assertEquals(1280, g.outW)
        assertEquals(720, g.outH)
    }

    @Test
    fun honoursSmallerShortSide() {
        // 720p-источник с запросом 540 по короткой стороне -> 960x540.
        val g = geometry(1280, 720, 540)
        assertEquals(540, minOf(g.outW, g.outH))
        assertEquals(960, maxOf(g.outW, g.outH))
    }

    @Test
    fun doesNotUpscaleSmallVideo() {
        val g = geometry(320, 192, 720)
        assertEquals("маленькое видео нельзя растягивать", 320, g.outW)
        assertEquals(192, g.outH)
    }

    @Test
    fun portraitVideoIsCappedByHeight() {
        // Вертикальное 1080x1920: длинная сторона — высота, её и ограничиваем.
        val g = geometry(1080, 1920, 0)
        assertEquals(1280, maxOf(g.outW, g.outH))
    }

    @Test
    fun outputDimensionsAreEven() {
        // Нечётные размеры ломают LZW-кодирование GIF.
        for ((w, h, t) in listOf(
            Triple(1920, 1080, 0),
            Triple(1280, 720, 540),
            Triple(1081, 1921, 0),
            Triple(3840, 2160, 720)
        )) {
            val g = geometry(w, h, t)
            assertEquals("ширина должна быть чётной для $w x $h", 0, g.outW % 2)
            assertEquals("высота должна быть чётной для $w x $h", 0, g.outH % 2)
        }
    }
}
