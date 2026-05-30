package com.subbysound

import android.media.*
import android.os.Handler
import android.os.Looper
import java.io.File
import kotlin.math.abs

class AudioEngine {

    companion object {
        const val SAMPLE_RATE = 44100
        const val FFT_SIZE = 2048
        const val HOP_SIZE = FFT_SIZE / 2
    }

    enum class State { IDLE, RECORDING, PLAYING }

    @Volatile var state = State.IDLE
        private set

    // Playback filter params — written from UI thread, read from audio thread
    @Volatile var filterLowHz: Float = 0f
    @Volatile var filterHighHz: Float = SAMPLE_RATE / 2f
    @Volatile var freqShiftHz: Float = 0f
    @Volatile var filterEnabled: Boolean = false

    var onFFTData: ((FloatArray) -> Unit)? = null
    var onPlaybackComplete: (() -> Unit)? = null

    private val fft = FFTProcessor(FFT_SIZE, SAMPLE_RATE)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var recordThread: Thread? = null
    private var playThread: Thread? = null
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null

    @Volatile private var running = false

    fun startRecording(file: File) {
        if (state != State.IDLE) return
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val bufSize = maxOf(minBuf, HOP_SIZE * 2)

        audioRecord = AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize)
        audioRecord!!.startRecording()
        state = State.RECORDING
        running = true

        recordThread = Thread {
            val wavWriter = WavWriter(file, SAMPLE_RATE)
            val buffer = ShortArray(HOP_SIZE)
            try {
                while (running) {
                    val n = audioRecord!!.read(buffer, 0, HOP_SIZE)
                    if (n <= 0) continue
                    wavWriter.write(buffer, n)
                    val mags = fft.computeMagnitudesDb(buffer, 0, n)
                    mainHandler.post { onFFTData?.invoke(mags) }
                }
            } finally {
                wavWriter.close()
            }
        }.also { it.start() }
    }

    fun startPlayback(file: File) {
        if (state != State.IDLE) return
        val minBuf = AudioTrack.getMinBufferSize(SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val bufSize = maxOf(minBuf, HOP_SIZE * 4)

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build())
            .setAudioFormat(AudioFormat.Builder()
                .setSampleRate(SAMPLE_RATE)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build())
            .setBufferSizeInBytes(bufSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        audioTrack!!.play()
        state = State.PLAYING
        running = true

        playThread = Thread {
            val wavReader = WavReader(file)
            wavReader.seekToStart()

            val inputHistory = DoubleArray(FFT_SIZE)
            val olaBuffer = FloatArray(FFT_SIZE)
            val readBuf = ShortArray(HOP_SIZE)

            try {
                while (running) {
                    val n = wavReader.read(readBuf, 0, HOP_SIZE)
                    if (n <= 0) break

                    // Shift input history and append new samples
                    System.arraycopy(inputHistory, HOP_SIZE, inputHistory, 0, HOP_SIZE)
                    for (i in 0 until n) inputHistory[HOP_SIZE + i] = readBuf[i] / 32768.0
                    for (i in n until HOP_SIZE) inputHistory[HOP_SIZE + i] = 0.0

                    // Windowed FFT
                    val real = DoubleArray(FFT_SIZE) { i -> inputHistory[i] * fft.window[i] }
                    val imag = DoubleArray(FFT_SIZE)
                    fft.fft(real, imag)

                    // Frequency domain processing
                    if (filterEnabled) {
                        val binLow = fft.hzToBin(filterLowHz)
                        val binHigh = fft.hzToBin(filterHighHz)
                        val shiftBins = (freqShiftHz / fft.freqResolution).toInt()
                        fft.applyFilterAndShift(real, imag, binLow, binHigh, shiftBins)
                    }

                    // IFFT and overlap-add
                    fft.ifft(real, imag)
                    for (i in 0 until FFT_SIZE) olaBuffer[i] += real[i].toFloat()

                    // Output first HOP_SIZE samples
                    val output = ShortArray(HOP_SIZE) { i ->
                        (olaBuffer[i] * 32768.0).coerceIn(-32767.0, 32767.0).toInt().toShort()
                    }
                    audioTrack!!.write(output, 0, HOP_SIZE)

                    // Post FFT of what's being played for spectrogram display
                    val dispMags = fft.computeMagnitudesDb(output)
                    mainHandler.post { onFFTData?.invoke(dispMags) }

                    // Shift OLA buffer
                    System.arraycopy(olaBuffer, HOP_SIZE, olaBuffer, 0, HOP_SIZE)
                    olaBuffer.fill(0f, HOP_SIZE, FFT_SIZE)
                }
            } finally {
                wavReader.close()
                mainHandler.post { onPlaybackComplete?.invoke() }
            }
        }.also { it.start() }
    }

    fun stop() {
        running = false
        audioRecord?.let { it.stop(); it.release(); audioRecord = null }
        audioTrack?.let { it.stop(); it.release(); audioTrack = null }
        recordThread?.join(500)
        playThread?.join(500)
        recordThread = null; playThread = null
        state = State.IDLE
    }

    fun release() = stop()
}
