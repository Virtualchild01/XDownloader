package com.example.xdownloader.gif

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.Arrays

/**
 * Full-Frame Animated GIF89a Encoder:
 * - Кодирует 100% пикселей каждого кадра (dispose = 0, без дельта-прозрачности и заморозки пикселей)
 * - Использует 4x4 упорядоченный дизеринг Байера (Bayer Ordered Dithering) для идеальной плавности
 *   микродвижений камеры, полутонов и освещения на каждом кадре (24-30 fps)
 * - Хранит в памяти только одну строку пикселей (IntArray(width) ~ 7.6 КБ) и массив индексов,
 *   полностью исключая риск OutOfMemoryError и пропуск кадров даже на разрешении 1920x2160.
 */
class AnimatedGifEncoder {
    private var width: Int = 0
    private var height: Int = 0
    private var repeat: Int = 0 // 0 = бесконечный цикл
    private var delay: Int = 40 // задержка кадра в мс
    private var started: Boolean = false
    private var out: OutputStream? = null
    private var firstFrame: Boolean = true
    private var paletteBuilt: Boolean = false
    private var sample: Int = 5

    private var colorTab: ByteArray = ByteArray(256 * 3)
    private val palR = IntArray(256)
    private val palG = IntArray(256)
    private val palB = IntArray(256)
    private val rgb555Lut = IntArray(32768) { -1 }

    private var rowBuffer: IntArray = IntArray(0)
    private var indexedPixels: ByteArray = ByteArray(0)

    companion object {
        // Матрица упорядоченного дизеринга Байера 4x4 (значения -4..+3)
        // Обеспечивает плавную передачу субпиксельного движения камеры и градиентов от кадра к кадру
        private val BAYER_4X4 = intArrayOf(
            -4,  0, -3,  1,
             2, -2,  3, -1,
            -2,  1, -3,  0,
             3,  0,  2, -1
        )

        // 39 гарантированных насыщенных хроматических и базовых цветов (слоты 217..255),
        // чтобы мелкие яркие детали (например, красные глаза, блики, неон) никогда не теряли цвет
        private val CHROMATIC_ANCHORS = arrayOf(
            intArrayOf(0, 0, 0), intArrayOf(255, 255, 255), intArrayOf(128, 128, 128),
            // Красные и багровые оттенки
            intArrayOf(108, 0, 0), intArrayOf(144, 0, 0), intArrayOf(180, 0, 0),
            intArrayOf(216, 0, 0), intArrayOf(252, 0, 0), intArrayOf(252, 36, 0),
            intArrayOf(216, 36, 0), intArrayOf(180, 36, 0), intArrayOf(252, 36, 36),
            intArrayOf(252, 72, 72), intArrayOf(216, 72, 85), intArrayOf(252, 72, 85),
            // Зелёные оттенки
            intArrayOf(0, 144, 0), intArrayOf(0, 180, 0), intArrayOf(0, 216, 0),
            intArrayOf(0, 252, 0), intArrayOf(72, 252, 72),
            // Синие и голубые оттенки
            intArrayOf(0, 0, 170), intArrayOf(0, 0, 255), intArrayOf(36, 108, 255),
            intArrayOf(72, 180, 255), intArrayOf(0, 216, 255), intArrayOf(0, 252, 252),
            // Жёлтые, оранжевые, пурпурные и фиолетовые оттенки
            intArrayOf(252, 144, 0), intArrayOf(252, 180, 0), intArrayOf(252, 216, 0),
            intArrayOf(252, 252, 0), intArrayOf(252, 216, 108), intArrayOf(180, 0, 180),
            intArrayOf(252, 0, 252), intArrayOf(252, 72, 170), intArrayOf(144, 72, 255),
            // Тёплые телесные и древесные полутона
            intArrayOf(252, 216, 170), intArrayOf(216, 144, 108),
            intArrayOf(108, 72, 36), intArrayOf(72, 36, 0)
        )
    }

    fun setDelay(ms: Int) {
        delay = ms.coerceAtLeast(20)
    }

    fun setRepeat(iter: Int) {
        if (iter >= 0) repeat = iter
    }

    fun setFrameRate(fps: Float) {
        if (fps > 0f) delay = (1000f / fps).toInt().coerceAtLeast(20)
    }

    fun setQuality(quality: Int) {
        sample = if (quality < 1) 1 else quality
    }

    fun setSize(w: Int, h: Int) {
        width = w
        height = h
    }

    fun start(os: OutputStream): Boolean {
        out = os
        started = true
        firstFrame = true
        paletteBuilt = false
        return try {
            writeString("GIF89a")
            true
        } catch (e: IOException) {
            false
        }
    }

    /**
     * Инициализирует глобальную палитру из 256 цветов по первому кадру и записывает заголовок GIF (LSD + GCT + Netscape).
     */
    fun ensureHeaderAndPalette(im: Bitmap) {
        if (paletteBuilt) return
        width = im.width
        height = im.height
        val numPixels = width * height
        if (rowBuffer.size < width) {
            rowBuffer = IntArray(width)
        }
        if (indexedPixels.size != numPixels) {
            indexedPixels = ByteArray(numPixels)
        }
        Arrays.fill(rgb555Lut, -1)

        buildGlobalHybridPaletteFromBitmap(im)
        writeLSD()
        writePalette()
        if (repeat >= 0) {
            writeNetscapeExt()
        }
        paletteBuilt = true
        firstFrame = false
    }

    /**
     * Квантует кадр (100% пикселей с дизерингом Байера 4x4) и сжимает его в компактный LZW-массив байт.
     * Позволяет хранить в буфере переупорядочивания B-кадров сжатые ~300 КБ вместо 16.6 МБ Bitmap!
     */
    fun encodeFrameToLzwBytes(im: Bitmap): ByteArray {
        ensureHeaderAndPalette(im)
        var frameBmp = im
        if (im.width != width || im.height != height) {
            frameBmp = Bitmap.createScaledBitmap(im, width, height, true)
        }

        quantizeBitmapToIndexedPixels(frameBmp)

        if (frameBmp !== im) {
            frameBmp.recycle()
        }

        val baos = ByteArrayOutputStream(width * height / 3)
        val lzw = LZWEncoder(width, height, indexedPixels, 8)
        lzw.encode(baos)
        return baos.toByteArray()
    }

    /**
     * Записывает ранее сжатый LZW-кадр в выходной GIF-поток с текущей задержкой delay.
     */
    fun writePreEncodedFrame(lzwBytes: ByteArray): Boolean {
        if (!started || out == null || !paletteBuilt) return false
        return try {
            writeGraphicCtrlExt()
            writeImageDesc()
            out!!.write(lzwBytes)
            true
        } catch (e: IOException) {
            false
        }
    }

    /**
     * Прямое добавление кадра в поток (используется для последовательной записи).
     */
    fun addFrame(im: Bitmap): Boolean {
        if (!started || out == null) return false
        return try {
            ensureHeaderAndPalette(im)
            var frameBmp = im
            if (im.width != width || im.height != height) {
                frameBmp = Bitmap.createScaledBitmap(im, width, height, true)
            }

            quantizeBitmapToIndexedPixels(frameBmp)

            if (frameBmp !== im) {
                frameBmp.recycle()
            }

            writeGraphicCtrlExt()
            writeImageDesc()
            writePixels()
            true
        } catch (e: IOException) {
            false
        }
    }

    fun finish(): Boolean {
        if (!started) return false
        var ok = true
        started = false
        try {
            out?.write(0x3b) // GIF trailer
            out?.flush()
        } catch (e: IOException) {
            ok = false
        }
        out = null
        rowBuffer = IntArray(0)
        indexedPixels = ByteArray(0)
        firstFrame = true
        paletteBuilt = false
        return ok
    }

    private fun quantizeBitmapToIndexedPixels(bmp: Bitmap) {
        val w = width
        val h = height
        if (rowBuffer.size < w) {
            rowBuffer = IntArray(w)
        }
        val row = rowBuffer
        val outIdx = indexedPixels
        var outPos = 0

        for (y in 0 until h) {
            bmp.getPixels(row, 0, w, 0, y, w, 1)
            val bayerRowOffset = (y and 3) shl 2
            for (x in 0 until w) {
                val c = row[x]
                val dither = BAYER_4X4[bayerRowOffset or (x and 3)]
                val r = (((c shr 16) and 0xFF) + dither).coerceIn(0, 255)
                val g = (((c shr 8) and 0xFF) + dither).coerceIn(0, 255)
                val b = ((c and 0xFF) + dither).coerceIn(0, 255)
                val bIdx = ((r shr 3) shl 10) or ((g shr 3) shl 5) or (b shr 3)
                var pIdx = rgb555Lut[bIdx]
                if (pIdx < 0) {
                    pIdx = computeNearestSlot(bIdx)
                }
                outIdx[outPos++] = pIdx.toByte()
            }
        }
    }

    private fun buildGlobalHybridPaletteFromBitmap(bmp: Bitmap) {
        val counts = IntArray(32768)
        val w = width
        val h = height
        val row = rowBuffer

        for (y in 0 until h) {
            bmp.getPixels(row, 0, w, 0, y, w, 1)
            for (x in 0 until w) {
                val c = row[x]
                val bIdx = (((c shr 19) and 31) shl 10) or (((c shr 11) and 31) shl 5) or ((c shr 3) and 31)
                counts[bIdx]++
            }
        }

        val packed = LongArray(32768)
        for (b in 0 until 32768) {
            packed[b] = (counts[b].toLong() shl 15) or b.toLong()
        }
        Arrays.sort(packed)

        // Слоты 0..216: 217 самых частых оттенков сцены из первого кадра
        for (slot in 0 until 217) {
            val pk = packed[32767 - slot]
            val bIdx = (pk and 32767L).toInt()
            val r = ((bIdx shr 10) and 31) * 8 + 4
            val g = ((bIdx shr 5) and 31) * 8 + 4
            val b = (bIdx and 31) * 8 + 4
            palR[slot] = r
            palG[slot] = g
            palB[slot] = b
            colorTab[slot * 3] = r.toByte()
            colorTab[slot * 3 + 1] = g.toByte()
            colorTab[slot * 3 + 2] = b.toByte()
            rgb555Lut[bIdx] = slot
        }

        // Слоты 217..255: 39 насыщенных хроматических акцентов (красные глаза, неон, базовые тона)
        for (a in 0 until 39) {
            val slot = 217 + a
            val anchor = CHROMATIC_ANCHORS[a]
            val r = anchor[0]
            val g = anchor[1]
            val b = anchor[2]
            palR[slot] = r
            palG[slot] = g
            palB[slot] = b
            colorTab[slot * 3] = r.toByte()
            colorTab[slot * 3 + 1] = g.toByte()
            colorTab[slot * 3 + 2] = b.toByte()
            val bIdx = ((r shr 3) shl 10) or ((g shr 3) shl 5) or (b shr 3)
            if (rgb555Lut[bIdx] < 0) {
                rgb555Lut[bIdx] = slot
            }
        }
    }

    private fun computeNearestSlot(bIdx: Int): Int {
        val r = ((bIdx shr 10) and 31) * 8 + 4
        val g = ((bIdx shr 5) and 31) * 8 + 4
        val b = (bIdx and 31) * 8 + 4
        var bestSlot = 0
        var bestDist = Int.MAX_VALUE
        for (s in 0 until 256) {
            val dr = r - palR[s]
            val dg = g - palG[s]
            val db = b - palB[s]
            val d = dr * dr + dg * dg + db * db
            if (d < bestDist) {
                bestDist = d
                bestSlot = s
            }
        }
        rgb555Lut[bIdx] = bestSlot
        return bestSlot
    }

    private fun writeGraphicCtrlExt() {
        out!!.write(0x21) // extension introducer
        out!!.write(0xf9) // GCE label
        out!!.write(4)    // byte size
        out!!.write(0)    // dispose = 0, transp = 0 (полный кадр без пропуска пикселей)
        writeShort((delay + 5) / 10) // задержка в сотых долях секунды (10 мс)
        out!!.write(0)    // transparent index (не используется)
        out!!.write(0)    // block terminator
    }

    private fun writeImageDesc() {
        out!!.write(0x2c) // image separator
        writeShort(0)     // x position
        writeShort(0)     // y position
        writeShort(width)
        writeShort(height)
        out!!.write(0)    // LCT = 0 (используется глобальная таблица цветов GCT)
    }

    private fun writeLSD() {
        writeShort(width)
        writeShort(height)
        out!!.write(0x80 or 0x70 or 0x00 or 7) // global color table flag, 256 colors
        out!!.write(0) // background color index
        out!!.write(0) // pixel aspect ratio
    }

    private fun writeNetscapeExt() {
        out!!.write(0x21)
        out!!.write(0xff)
        out!!.write(11)
        writeString("NETSCAPE2.0")
        out!!.write(3)
        out!!.write(1)
        writeShort(repeat)
        out!!.write(0)
    }

    private fun writePalette() {
        out!!.write(colorTab, 0, 256 * 3)
    }

    private fun writePixels() {
        val encoder = LZWEncoder(width, height, indexedPixels, 8)
        encoder.encode(out!!)
    }

    private fun writeShort(value: Int) {
        out!!.write(value and 0xff)
        out!!.write((value shr 8) and 0xff)
    }

    private fun writeString(s: String) {
        for (ch in s) {
            out!!.write(ch.code)
        }
    }
}
