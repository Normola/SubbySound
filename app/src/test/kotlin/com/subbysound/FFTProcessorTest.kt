package com.subbysound

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class FFTProcessorTest {

    private val fft = FFTProcessor(2048)

    @Test
    fun silenceProducesNegativeDbMagnitudes() {
        val samples = ShortArray(2048)
        val mags = fft.computeMagnitudesDb(samples)
        assertTrue("Silence should be very quiet", mags.all { it < -60f })
    }

    @Test
    fun pureTonePeaksAtCorrectBin() {
        val hz = 1000f
        val targetBin = fft.hzToBin(hz)
        val samples = ShortArray(2048) { n ->
            (Short.MAX_VALUE * sin(2.0 * PI * hz * n / AudioConfig.SAMPLE_RATE)).toInt().toShort()
        }
        val mags = fft.computeMagnitudesDb(samples)

        // Find the peak bin in the magnitudes
        val peakBin = mags.indices.maxByOrNull { mags[it] }!!

        // Peak should be within ±2 bins of target
        assertTrue(
            "Peak at bin $peakBin, expected near $targetBin",
            abs(peakBin - targetBin) <= 2
        )
    }

    @Test
    fun roundTripFftIfftApproxIdentity() {
        val real = DoubleArray(2048) { sin(2.0 * PI * 440.0 * it / AudioConfig.SAMPLE_RATE) }
        val imag = DoubleArray(2048)
        val original = real.copyOf()

        fft.fft(real, imag)
        fft.ifft(real, imag)

        val maxError = real.indices.maxOf { abs(real[it] - original[it]) }
        assertTrue("Round-trip error $maxError should be < 1e-9", maxError < 1e-9)
    }

    @Test
    fun hzToBinRoundTrip() {
        for (hz in listOf(100f, 440f, 1000f, 4000f, 10000f, 20000f)) {
            val bin = fft.hzToBin(hz)
            val recovered = fft.binToHz(bin)
            val error = abs(recovered - hz)
            assertTrue(
                "hzToBin round-trip error ${error}Hz for ${hz}Hz, should be < freqRes",
                error <= fft.freqResolution
            )
        }
    }

    @Test
    fun filterAndShiftZerosOutOfBandContent() {
        // Tone at 1000 Hz; filter should remove it when range is 5000–8000 Hz
        val hz = 1000f
        val samples = ShortArray(2048) { n ->
            (Short.MAX_VALUE * sin(2.0 * PI * hz * n / AudioConfig.SAMPLE_RATE)).toInt().toShort()
        }
        val real = DoubleArray(2048) { samples[it] / 32768.0 }
        val imag = DoubleArray(2048)
        fft.fft(real, imag)

        fft.applyFilterAndShift(real, imag,
            binLow  = fft.hzToBin(5000f),
            binHigh = fft.hzToBin(8000f),
            shiftBins = 0
        )
        fft.ifft(real, imag)

        val rms = sqrt(real.map { it * it }.average())
        assertTrue("Filtered-out tone should have near-zero energy, got rms=$rms", rms < 0.01)
    }

    @Test
    fun filterAndShiftPreservesInBandContent() {
        // Tone at 1000 Hz; filter centred on that frequency should let it through
        val hz = 1000f
        val samples = ShortArray(2048) { n ->
            (Short.MAX_VALUE * sin(2.0 * PI * hz * n / AudioConfig.SAMPLE_RATE)).toInt().toShort()
        }
        val real = DoubleArray(2048) { samples[it] / 32768.0 }
        val imag = DoubleArray(2048)
        fft.fft(real, imag)

        fft.applyFilterAndShift(real, imag,
            binLow  = fft.hzToBin(800f),
            binHigh = fft.hzToBin(1200f),
            shiftBins = 0
        )
        fft.ifft(real, imag)

        val rms = sqrt(real.map { it * it }.average())
        assertTrue("In-band tone should have significant energy, got rms=$rms", rms > 0.1)
    }
}
