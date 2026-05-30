package com.subbysound

object AudioConfig {
    // Note: at 44100 Hz the Nyquist limit is ~22.05 kHz. Frequencies above this
    // cannot be captured by the microphone pipeline, so the frequency-shift feature
    // operates on recorded content up to this limit (not on true ultrasound above 20 kHz).
    const val SAMPLE_RATE = 44100
    const val FFT_SIZE = 2048
    const val HOP_SIZE = FFT_SIZE / 2
}
