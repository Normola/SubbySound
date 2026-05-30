package com.subbysound

import java.io.File
import java.io.RandomAccessFile

class WavWriter(file: File, private val sampleRate: Int, private val channels: Int = 1) {
    private val raf = RandomAccessFile(file, "rw")
    private var dataBytes = 0

    init {
        raf.setLength(0)
        writeHeader(0)
    }

    fun write(samples: ShortArray, len: Int = samples.size) {
        for (i in 0 until len) {
            val v = samples[i].toInt()
            raf.write(v and 0xFF)
            raf.write((v shr 8) and 0xFF)
        }
        dataBytes += len * 2
    }

    fun close() {
        raf.seek(0)
        writeHeader(dataBytes)
        raf.close()
    }

    private fun writeHeader(dataSize: Int) {
        val blockAlign = channels * 2
        val byteRate = sampleRate * blockAlign
        fun Int.le4() = ByteArray(4).also { b ->
            b[0] = (this and 0xFF).toByte(); b[1] = (this shr 8 and 0xFF).toByte()
            b[2] = (this shr 16 and 0xFF).toByte(); b[3] = (this shr 24 and 0xFF).toByte()
        }
        fun Int.le2() = ByteArray(2).also { b ->
            b[0] = (this and 0xFF).toByte(); b[1] = (this shr 8 and 0xFF).toByte()
        }
        raf.writeBytes("RIFF"); raf.write((36 + dataSize).le4())
        raf.writeBytes("WAVE")
        raf.writeBytes("fmt "); raf.write(16.le4())
        raf.write(1.le2()); raf.write(channels.le2())
        raf.write(sampleRate.le4()); raf.write(byteRate.le4())
        raf.write(blockAlign.le2()); raf.write(16.le2())
        raf.writeBytes("data"); raf.write(dataSize.le4())
    }
}

class WavReader(file: File) {
    private val raf = RandomAccessFile(file, "r")
    var sampleRate: Int = 44100
        private set
    var channels: Int = 1
        private set
    private var dataOffset: Long = 44L
    var totalSamples: Long = 0L
        private set

    init {
        parseHeader()
    }

    private fun parseHeader() {
        try {
            raf.seek(24); sampleRate = readInt32LE()
            raf.seek(22); channels = readInt16LE()
            raf.seek(40)
            val dataSize = readInt32LE().toLong()
            dataOffset = 44L
            totalSamples = dataSize / 2
        } catch (e: Exception) {
            dataOffset = 44L
        }
    }

    private fun readInt32LE(): Int {
        val b = ByteArray(4); raf.readFully(b)
        return (b[0].toInt() and 0xFF) or ((b[1].toInt() and 0xFF) shl 8) or
               ((b[2].toInt() and 0xFF) shl 16) or ((b[3].toInt() and 0xFF) shl 24)
    }

    private fun readInt16LE(): Int {
        val b = ByteArray(2); raf.readFully(b)
        return (b[0].toInt() and 0xFF) or ((b[1].toInt() and 0xFF) shl 8)
    }

    fun read(buffer: ShortArray, offset: Int = 0, count: Int = buffer.size - offset): Int {
        val bytes = ByteArray(count * 2)
        val nBytes = try { raf.read(bytes) } catch (e: Exception) { -1 }
        if (nBytes <= 0) return -1
        val nSamples = nBytes / 2
        for (i in 0 until nSamples) {
            buffer[offset + i] = ((bytes[i * 2].toInt() and 0xFF) or
                                  ((bytes[i * 2 + 1].toInt() and 0xFF) shl 8)).toShort()
        }
        return nSamples
    }

    fun seekToStart() = raf.seek(dataOffset)

    fun close() = raf.close()
}
