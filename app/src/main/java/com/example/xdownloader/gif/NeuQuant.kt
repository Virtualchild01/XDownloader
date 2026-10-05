package com.example.xdownloader.gif

import kotlin.math.abs

/**
 * NeuQuant Neural-Net Quantization Algorithm
 * Adapted for Kotlin.
 */
class NeuQuant(
    private val thepicture: ByteArray,
    private val lengthcount: Int,
    private val samplefac: Int
) {
    companion object {
        private const val ncycles = 100
        private const val netsize = 256
        private const val maxnetpos = netsize - 1
        private const val netbiasshift = 4
        private const val intbiasshift = 16
        private const val intbias = 1 shl intbiasshift
        private const val gammashift = 10
        private const val gamma = 1 shl gammashift
        private const val betashift = 10
        private const val beta = intbias shr betashift
        private const val betagamma = intbias shl (gammashift - betashift)
        private const val initrad = netsize shr 3
        private const val radiusbiasshift = 6
        private const val radiusbias = 1 shl radiusbiasshift
        private const val initradius = initrad * radiusbias
        private const val radiusdec = 30
        private const val alphabiasshift = 10
        private const val initalpha = 1 shl alphabiasshift
        private const val radbiasshift = 8
        private const val radbias = 1 shl radbiasshift
        private const val alpharadbise = alphabiasshift + radbiasshift
        private const val alpharadbias = 1 shl alpharadbise
    }

    private val network = Array(netsize) { IntArray(4) }
    private val netindex = IntArray(256)
    private val bias = IntArray(netsize)
    private val freq = IntArray(netsize)
    private val radpower = IntArray(initrad)

    init {
        for (i in 0 until netsize) {
            network[i][0] = (i shl (netbiasshift + 8)) / netsize
            network[i][1] = (i shl (netbiasshift + 8)) / netsize
            network[i][2] = (i shl (netbiasshift + 8)) / netsize
            freq[i] = intbias / netsize
            bias[i] = 0
        }
    }

    private fun colorMap(): ByteArray {
        val map = ByteArray(3 * netsize)
        val index = IntArray(netsize)
        for (i in 0 until netsize) index[network[i][3]] = i
        var k = 0
        for (i in 0 until netsize) {
            val j = index[i]
            // Standard GIF palette is RGB: byte 0 = R, byte 1 = G, byte 2 = B
            map[k++] = (network[j][2] shr netbiasshift).toByte()
            map[k++] = (network[j][1] shr netbiasshift).toByte()
            map[k++] = (network[j][0] shr netbiasshift).toByte()
        }
        return map
    }

    private fun inxbuild() {
        var previouscol = 0
        var startpos = 0
        for (i in 0 until netsize) {
            val p = network[i]
            var smallpos = i
            var smallval = p[1]
            for (j in i + 1 until netsize) {
                val q = network[j]
                if (q[1] < smallval) {
                    smallpos = j
                    smallval = q[1]
                }
            }
            val q = network[smallpos]
            if (i != smallpos) {
                var j = q[0]; q[0] = p[0]; p[0] = j
                j = q[1]; q[1] = p[1]; p[1] = j
                j = q[2]; q[2] = p[2]; p[2] = j
                j = q[3]; q[3] = p[3]; p[3] = j
            }
            if (smallval != previouscol) {
                netindex[previouscol] = (startpos + i) shr 1
                for (j in previouscol + 1 until smallval) netindex[j] = i
                previouscol = smallval
                startpos = i
            }
        }
        netindex[previouscol] = (startpos + maxnetpos) shr 1
        for (j in previouscol + 1 until 256) netindex[j] = maxnetpos
    }

    private fun learn() {
        if (lengthcount < 3) return
        val length = lengthcount
        val samplepixels = length / (3 * samplefac)
        var delta = samplepixels / ncycles
        if (delta == 0) delta = 1
        var alpha = initalpha
        var radius = initradius
        var rad = radius shr radiusbiasshift
        if (rad <= 1) rad = 0
        for (i in 0 until rad) {
            radpower[i] = alpha * (((rad * rad - i * i) * radbias) / (rad * rad))
        }

        var step = if (length < 499 * 3) 3
        else if (length % 499 != 0) 499 * 3
        else if (length % 491 != 0) 491 * 3
        else if (length % 487 != 0) 487 * 3
        else 503 * 3

        var pix = 0
        var i = 0
        while (i < samplepixels) {
            val b = (thepicture[pix].toInt() and 0xff) shl netbiasshift
            val g = (thepicture[pix + 1].toInt() and 0xff) shl netbiasshift
            val r = (thepicture[pix + 2].toInt() and 0xff) shl netbiasshift
            var j = contest(b, g, r)

            altersingle(alpha, j, b, g, r)
            if (rad != 0) alterconv(rad, j, b, g, r)

            pix += step
            if (pix >= length) pix -= length

            i++
            if (delta == 0) delta = 1
            if (i % delta == 0) {
                alpha -= alpha / 30
                radius -= radius / radiusdec
                rad = radius shr radiusbiasshift
                if (rad <= 1) rad = 0
                for (k in 0 until rad) {
                    radpower[k] = alpha * (((rad * rad - k * k) * radbias) / (rad * rad))
                }
            }
        }
    }

    fun map(b: Int, g: Int, r: Int): Int {
        var bestd = 1000
        var best = -1
        var i = netindex[g]
        var j = i - 1

        while (i < netsize || j >= 0) {
            if (i < netsize) {
                val p = network[i]
                var dist = p[1] - g
                if (dist >= bestd) i = netsize
                else {
                    i++
                    if (dist < 0) dist = -dist
                    var a = p[0] - b
                    if (a < 0) a = -a
                    dist += a
                    if (dist < bestd) {
                        a = p[2] - r
                        if (a < 0) a = -a
                        dist += a
                        if (dist < bestd) {
                            bestd = dist
                            best = p[3]
                        }
                    }
                }
            }
            if (j >= 0) {
                val p = network[j]
                var dist = g - p[1]
                if (dist >= bestd) j = -1
                else {
                    j--
                    if (dist < 0) dist = -dist
                    var a = p[0] - b
                    if (a < 0) a = -a
                    dist += a
                    if (dist < bestd) {
                        a = p[2] - r
                        if (a < 0) a = -a
                        dist += a
                        if (dist < bestd) {
                            bestd = dist
                            best = p[3]
                        }
                    }
                }
            }
        }
        return best
    }

    fun process(): ByteArray {
        learn()
        unbiasnet()
        inxbuild()
        return colorMap()
    }

    private fun unbiasnet() {
        for (i in 0 until netsize) {
            network[i][0] = network[i][0] shr netbiasshift
            network[i][1] = network[i][1] shr netbiasshift
            network[i][2] = network[i][2] shr netbiasshift
            network[i][3] = i
        }
    }

    private fun altersingle(alpha: Int, i: Int, b: Int, g: Int, r: Int) {
        network[i][0] -= (alpha * (network[i][0] - b)) / initalpha
        network[i][1] -= (alpha * (network[i][1] - g)) / initalpha
        network[i][2] -= (alpha * (network[i][2] - r)) / initalpha
    }

    private fun alterconv(rad: Int, i: Int, b: Int, g: Int, r: Int) {
        var lo = i - rad
        if (lo < -1) lo = -1
        var hi = i + rad
        if (hi > netsize) hi = netsize

        var j = i + 1
        var k = i - 1
        var m = 1
        while (j < hi || k > lo) {
            val a = radpower[m++]
            if (j < hi) {
                val p = network[j++]
                try {
                    p[0] -= (a * (p[0] - b)) / alpharadbias
                    p[1] -= (a * (p[1] - g)) / alpharadbias
                    p[2] -= (a * (p[2] - r)) / alpharadbias
                } catch (_: Exception) {}
            }
            if (k > lo) {
                val p = network[k--]
                try {
                    p[0] -= (a * (p[0] - b)) / alpharadbias
                    p[1] -= (a * (p[1] - g)) / alpharadbias
                    p[2] -= (a * (p[2] - r)) / alpharadbias
                } catch (_: Exception) {}
            }
        }
    }

    private fun contest(b: Int, g: Int, r: Int): Int {
        var bestd = Int.MAX_VALUE
        var bestbiasd = bestd
        var bestpos = -1
        var bestbiaspos = bestpos

        for (i in 0 until netsize) {
            val p = network[i]
            var dist = abs(p[0] - b) + abs(p[1] - g) + abs(p[2] - r)
            if (dist < bestd) {
                bestd = dist
                bestpos = i
            }
            val biasdist = dist - (bias[i] shr (intbiasshift - netbiasshift))
            if (biasdist < bestbiasd) {
                bestbiasd = biasdist
                bestbiaspos = i
            }
            val betafreq = freq[i] shr betashift
            freq[i] -= betafreq
            bias[i] += betafreq shl gammashift
        }
        freq[bestpos] += beta
        bias[bestpos] -= betagamma
        return bestbiaspos
    }
}
