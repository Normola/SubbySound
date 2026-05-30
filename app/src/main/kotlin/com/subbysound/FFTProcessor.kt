package com.subbysound

import kotlin.math.*

class FFTProcessor(val size: Int, val sampleRate: Int = AudioConfig.SAMPLE_RATE) {

    private val log2Size = Integer.numberOfTrailingZeros(size)
    private val bitReverse = IntArray(size) { reverseBits(it, log2Size) }
    private val cosTable = DoubleArray(size / 2) { cos(-2.0 * PI * it / size) }
    private val sinTable = DoubleArray(size / 2) { sin(-2.0 * PI * it / size) }
    val window = FloatArray(size) { (0.5 * (1.0 - cos(2.0 * PI * it / (size - 1)))).toFloat() }

    val freqResolution: Float = sampleRate.toFloat() / size
    val numBins: Int = size / 2

    private fun reverseBits(n: Int, bits: Int): Int {
        var x = n
        var r = 0
        repeat(bits) { r = (r shl 1) or (x and 1); x = x ushr 1 }
        return r
    }

    fun fft(real: DoubleArray, imag: DoubleArray) {
        val n = real.size
        for (i in 0 until n) {
            val j = bitReverse[i]
            if (i < j) {
                var t = real[i]; real[i] = real[j]; real[j] = t
                t = imag[i]; imag[i] = imag[j]; imag[j] = t
            }
        }
        var halfLen = 1
        while (halfLen < n) {
            val step = n / (halfLen * 2)
            var i = 0
            while (i < n) {
                for (j in 0 until halfLen) {
                    val twIdx = j * step
                    val wr = cosTable[twIdx]; val wi = sinTable[twIdx]
                    val ur = real[i + j + halfLen]; val ui = imag[i + j + halfLen]
                    val tr = wr * ur - wi * ui; val ti = wr * ui + wi * ur
                    val er = real[i + j]; val ei = imag[i + j]
                    real[i + j] = er + tr; imag[i + j] = ei + ti
                    real[i + j + halfLen] = er - tr; imag[i + j + halfLen] = ei - ti
                }
                i += halfLen * 2
            }
            halfLen *= 2
        }
    }

    fun ifft(real: DoubleArray, imag: DoubleArray) {
        for (i in imag.indices) imag[i] = -imag[i]
        fft(real, imag)
        val scale = 1.0 / size
        for (i in real.indices) {
            real[i] *= scale
            imag[i] = -imag[i] * scale
        }
    }

    fun computeMagnitudesDb(samples: ShortArray, offset: Int = 0, count: Int = size): FloatArray {
        val real = DoubleArray(size)
        val imag = DoubleArray(size)
        val n = minOf(count, size, samples.size - offset)
        for (i in 0 until n) real[i] = samples[offset + i] / 32768.0 * window[i]
        fft(real, imag)
        return FloatArray(numBins) { i ->
            val mag = sqrt(real[i] * real[i] + imag[i] * imag[i])
            (20.0 * log10(mag.coerceAtLeast(1e-10))).toFloat()
        }
    }

    fun applyFilterAndShift(
        real: DoubleArray, imag: DoubleArray,
        binLow: Int, binHigh: Int, shiftBins: Int
    ) {
        val n = real.size
        val half = n / 2
        val lo = binLow.coerceIn(0, half - 1)
        val hi = binHigh.coerceIn(0, half - 1)

        val nr = DoubleArray(n)
        val ni = DoubleArray(n)
        for (i in lo..hi) {
            val t = i + shiftBins
            if (t in 0 until half) {
                nr[t] += real[i]; ni[t] += imag[i]
            }
        }
        // Maintain conjugate symmetry for real output
        for (i in 1 until half) {
            nr[n - i] = nr[i]; ni[n - i] = -ni[i]
        }
        nr.copyInto(real); ni.copyInto(imag)
    }

    fun hzToBin(hz: Float): Int = (hz / freqResolution).roundToInt().coerceIn(0, numBins - 1)
    fun binToHz(bin: Int): Float = bin * freqResolution
}
