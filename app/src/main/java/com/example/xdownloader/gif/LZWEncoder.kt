package com.example.xdownloader.gif

import java.io.IOException
import java.io.OutputStream
import kotlin.math.min

/**
 * LZW encoder for GIF89a. Adapted for Kotlin.
 */
class LZWEncoder(
    private val imgW: Int,
    private val imgH: Int,
    private val pixAry: ByteArray,
    private val initCodeSize: Int
) {
    companion object {
        private const val EOF = -1
        private const val BITS = 12
        private const val HSIZE = 5003 // 80% occupancy
    }

    private var nBits: Int = 0
    private var maxbits = BITS
    private var maxcode: Int = 0
    private var maxmaxcode = 1 shl BITS

    private val htab = IntArray(HSIZE)
    private val codetab = IntArray(HSIZE)
    private var hsize = HSIZE
    private var freeEnt = 0
    private var clearFlg = false

    private var gInitBits: Int = 0
    private var ClearCode: Int = 0
    private var EOFCode: Int = 0

    private var curAccum = 0
    private var curBits = 0
    private val masks = intArrayOf(
        0x0000, 0x0001, 0x0003, 0x0007, 0x000F,
        0x001F, 0x003F, 0x007F, 0x00FF,
        0x01FF, 0x03FF, 0x07FF, 0x0FFF,
        0x1FFF, 0x3FFF, 0x7FFF, 0xFFFF
    )

    private var aCount = 0
    private val accum = ByteArray(256)
    private var curPixel = 0

    @Throws(IOException::class)
    fun encode(os: OutputStream) {
        os.write(initCodeSize)
        val remaining = imgW * imgH
        curPixel = 0
        compress(initCodeSize + 1, os)
        os.write(0)
    }

    private fun nextPixel(): Int {
        if (curPixel >= pixAry.size) return EOF
        return pixAry[curPixel++].toInt() and 0xff
    }

    @Throws(IOException::class)
    private fun compress(initBits: Int, outs: OutputStream) {
        gInitBits = initBits
        clearFlg = false
        nBits = gInitBits
        maxcode = (1 shl nBits) - 1

        ClearCode = 1 shl (initBits - 1)
        EOFCode = ClearCode + 1
        freeEnt = ClearCode + 2

        aCount = 0
        var ent = nextPixel()
        var hshift = 0
        var fcode = hsize
        while (fcode < 65536) {
            hshift++
            fcode *= 2
        }
        hshift = 8 - hshift

        val hsizeReg = hsize
        for (i in 0 until hsizeReg) htab[i] = -1

        output(ClearCode, outs)

        var c: Int
        while (nextPixel().also { c = it } != EOF) {
            fcode = (c shl maxbits) + ent
            var i = (c shl hshift) xor ent

            if (htab[i] == fcode) {
                ent = codetab[i]
                continue
            } else if (htab[i] >= 0) {
                var disp = hsizeReg - i
                if (i == 0) disp = 1
                var found = false
                do {
                    i -= disp
                    if (i < 0) i += hsizeReg
                    if (htab[i] == fcode) {
                        ent = codetab[i]
                        found = true
                        break
                    }
                } while (htab[i] >= 0)
                if (found) continue
            }

            output(ent, outs)
            ent = c
            if (freeEnt < maxmaxcode) {
                codetab[i] = freeEnt++
                htab[i] = fcode
            } else {
                clBlock(outs)
            }
        }
        output(ent, outs)
        output(EOFCode, outs)
    }

    @Throws(IOException::class)
    private fun output(code: Int, outs: OutputStream) {
        curAccum = curAccum and masks[curBits]
        if (curBits > 0) curAccum = curAccum or (code shl curBits)
        else curAccum = code

        curBits += nBits

        while (curBits >= 8) {
            charOut((curAccum and 0xff).toByte(), outs)
            curAccum = curAccum shr 8
            curBits -= 8
        }

        if (freeEnt > maxcode || clearFlg) {
            if (clearFlg) {
                nBits = gInitBits
                maxcode = (1 shl nBits) - 1
                clearFlg = false
            } else {
                nBits++
                maxcode = if (nBits == maxbits) maxmaxcode else (1 shl nBits) - 1
            }
        }

        if (code == EOFCode) {
            while (curBits > 0) {
                charOut((curAccum and 0xff).toByte(), outs)
                curAccum = curAccum shr 8
                curBits -= 8
            }
            flushChar(outs)
        }
    }

    @Throws(IOException::class)
    private fun clBlock(outs: OutputStream) {
        for (i in 0 until hsize) htab[i] = -1
        freeEnt = ClearCode + 2
        clearFlg = true
        output(ClearCode, outs)
    }

    @Throws(IOException::class)
    private fun charOut(c: Byte, outs: OutputStream) {
        accum[aCount++] = c
        if (aCount >= 254) flushChar(outs)
    }

    @Throws(IOException::class)
    private fun flushChar(outs: OutputStream) {
        if (aCount > 0) {
            outs.write(aCount)
            outs.write(accum, 0, aCount)
            aCount = 0
        }
    }
}
