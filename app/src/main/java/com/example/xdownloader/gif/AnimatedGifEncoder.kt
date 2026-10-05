package com.example.xdownloader.gif

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import java.io.IOException
import java.io.OutputStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Pure Kotlin Animated GIF Encoder based on Kevin Weiner's GIF89a implementation.
 * Encodes multiple Android Bitmaps into an animated .gif file.
 */
class AnimatedGifEncoder {
    private var width: Int = 0
    private var height: Int = 0
    private var transparent: Int? = null
    private var transIndex: Int = 0
    private var repeat: Int = 0 // 0 = loop forever
    private var delay: Int = 100 // frame delay in ms
    private var started: Boolean = false
    private var out: OutputStream? = null
    private var image: Bitmap? = null
    private var pixels: ByteArray = ByteArray(0)
    private var indexedPixels: ByteArray = ByteArray(0)
    private var colorDepth: Int = 8
    private var colorTab: ByteArray = ByteArray(0)
    private val usedEntry = BooleanArray(256)
    private var palSize: Int = 7
    private var dispose: Int = -1
    private var closeStream: Boolean = false
    private var sample: Int = 10

    fun setDelay(ms: Int) {
        delay = ms
    }

    fun setRepeat(iter: Int) {
        if (iter >= 0) repeat = iter
    }

    fun setFrameRate(fps: Float) {
        if (fps > 0f) delay = (1000f / fps).toInt()
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
        closeStream = false
        started = true
        return try {
            writeString("GIF89a")
            true
        } catch (e: IOException) {
            false
        }
    }

    fun addFrame(im: Bitmap): Boolean {
        if (!started || out == null) return false
        var ok = true
        try {
            image = im
            getImagePixels()
            analyzeColors()
            if (firstFrame()) {
                writeLSD()
                writePalette()
                if (repeat >= 0) {
                    writeNetscapeExt()
                }
            }
            writeGraphicCtrlExt()
            writeImageDesc()
            if (!firstFrame()) {
                writePalette()
            }
            writePixels()
        } catch (e: IOException) {
            ok = false
        }
        return ok
    }

    fun finish(): Boolean {
        if (!started) return false
        var ok = true
        started = false
        try {
            out?.write(0x3b) // GIF trailer
            out?.flush()
            if (closeStream) {
                out?.close()
            }
        } catch (e: IOException) {
            ok = false
        }
        transIndex = 0
        out = null
        image = null
        pixels = ByteArray(0)
        indexedPixels = ByteArray(0)
        colorTab = ByteArray(0)
        return ok
    }

    private fun firstFrame(): Boolean = colorTab.isNotEmpty() && !hasWrittenFirstFrame

    private var hasWrittenFirstFrame = false

    private fun analyzeColors() {
        val nPix = pixels.size / 3
        indexedPixels = ByteArray(nPix)
        val nq = NeuQuant(pixels, pixels.size, sample)
        colorTab = nq.process()

        // convert map from BGR to RGB and find closest colors
        var k = 0
        for (i in 0 until nPix) {
            val b = pixels[k++].toInt() and 0xff
            val g = pixels[k++].toInt() and 0xff
            val r = pixels[k++].toInt() and 0xff
            val index = nq.map(b, g, r)
            usedEntry[index] = true
            indexedPixels[i] = index.toByte()
        }
        pixels = ByteArray(0)
        colorDepth = 8
        palSize = 7
    }

    private fun getImagePixels() {
        val w = image!!.width
        val h = image!!.height
        if (width == 0 || height == 0) {
            width = w
            height = h
        }
        val rgb = IntArray(w * h)
        image!!.getPixels(rgb, 0, w, 0, 0, w, h)

        pixels = ByteArray(rgb.size * 3)
        var count = 0
        for (i in rgb.indices) {
            val color = rgb[i]
            pixels[count++] = ((color shr 16) and 0xff).toByte() // R
            pixels[count++] = ((color shr 8) and 0xff).toByte()  // G
            pixels[count++] = (color and 0xff).toByte()         // B
        }
    }

    private fun writeGraphicCtrlExt() {
        out!!.write(0x21) // extension introducer
        out!!.write(0xf9) // GCE label
        out!!.write(4)    // byte size
        val transp: Int
        var disp: Int = dispose
        if (disp < 0) disp = 0
        disp = disp shl 2
        transp = 0

        out!!.write(0 or disp or 0 or transp)
        writeShort(delay / 10) // delay in hundredths of a second
        out!!.write(transIndex)
        out!!.write(0) // block terminator
    }

    private fun writeImageDesc() {
        out!!.write(0x2c) // image separator
        writeShort(0)     // x position
        writeShort(0)     // y position
        writeShort(width)
        writeShort(height)
        if (hasWrittenFirstFrame) {
            out!!.write(0x80 or 0x00 or 0 or palSize) // local color table
        } else {
            out!!.write(0)
        }
    }

    private fun writeLSD() {
        writeShort(width)
        writeShort(height)
        out!!.write(0x80 or 0x70 or 0x00 or palSize) // global color table flag
        out!!.write(0) // background color index
        out!!.write(0) // pixel aspect ratio
        hasWrittenFirstFrame = true
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
        out!!.write(colorTab, 0, colorTab.size)
        val n = 3 * 256 - colorTab.size
        for (i in 0 until n) {
            out!!.write(0)
        }
    }

    private fun writePixels() {
        val encoder = LZWEncoder(width, height, indexedPixels, colorDepth)
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
