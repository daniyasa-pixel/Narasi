package com.appik.narasi

import android.media.AudioFormat
import android.media.MediaRecorder
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Processes a separate copy. Preserves the full resolution, unprocessed original WAV.
 * RNNoise is always fed at 48 kHz with 480-sample / 10 ms frames.
 * High-rate recordings are band-limited with a windowed sinc before downsampling.
 */
internal object OfflineVoiceEnhancer {

    fun process(input: File, output: File, strength: Float = 0.85f): File {
        require(strength in 0f..1f) { "Invalid AI blend" }
        val format = parseWav(input)
        require(format.channels == 1) { "RNNoise hanya mendukung VO mono" }
        require(format.rate in intArrayOf(44100, 48000, 96000)) {
            "Sample rate tidak didukung"
        }
        val engine = RnNoiseNative.create()
        check(engine != 0L) { "Model RNNoise tidak berhasil dibuka" }
        var success = false
        try {
            check(RnNoiseNative.frameSize() == 480) {
                "RNNoise membutuhkan 480 sample per frame"
            }
            val framesIn = format.dataBytes / format.sampleBytes
            val framesOut = (framesIn * 48000L + format.rate / 2L) / format.rate
            require(framesOut * 4L <= 0xFFFFFFFFL - 36L) {
                "Rekaman terlalu panjang untuk WAV RIFF"
            }

            FloatSampleReader(input, format, framesIn).use { reader ->
                val resampler = SincResampler(reader, format.rate, framesIn)
                RandomAccessFile(output, "rw").use { target ->
                    target.setLength(0)
                    val resultFormat = WavAudioRecorder.Format(
                        48000, AudioFormat.ENCODING_PCM_FLOAT,
                        32, 4, 3, MediaRecorder.AudioSource.MIC
                    )
                    WavAudioRecorder.writeWavHeader(target, resultFormat, 0)
                    val frame = FloatArray(480)
                    val dry = FloatArray(480)
                    val outBytes = ByteBuffer.allocate(480 * 4).order(ByteOrder.LITTLE_ENDIAN)
                    val processor = VoiceMastering()
                    var done = 0L
                    while (done < framesOut) {
                        val samples = min(480L, framesOut - done).toInt()
                        for (n in 0 until samples) {
                            val original = resampler.sample(done + n)
                            dry[n] = original
                            frame[n] = original
                        }
                        for (n in samples until 480) { frame[n] = 0f; dry[n] = 0f }
                        RnNoiseNative.processFrame(engine, frame)
                        outBytes.clear()
                        for (n in 0 until samples) {
                            val wet = dry[n] * (1f - strength) + frame[n] * strength
                            outBytes.putFloat(processor.apply(wet))
                        }
                        target.write(outBytes.array(), 0, samples * 4)
                        done += samples
                    }
                    target.seek(0)
                    WavAudioRecorder.writeWavHeader(target, resultFormat, framesOut * 4L)
                    target.fd.sync()
                }
            }
            success = true
            return output
        } finally {
            RnNoiseNative.destroy(engine)
            if (!success) output.delete()
        }
    }

    private data class WavInfo(
        val rate: Int,
        val channels: Int,
        val bits: Int,
        val type: Int,
        val dataBytes: Long
    ) {
        val sampleBytes: Int get() = bits / 8
    }

    private fun parseWav(file: File): WavInfo {
        RandomAccessFile(file, "r").use { r ->
            require(file.length() >= 44L) { "File terlalu pendek" }
            val header = ByteArray(44)
            r.readFully(header)
            val b = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            require(String(header, 0, 4, Charsets.US_ASCII) == "RIFF" &&
                String(header, 8, 4, Charsets.US_ASCII) == "WAVE" &&
                String(header, 12, 4, Charsets.US_ASCII) == "fmt " &&
                String(header, 36, 4, Charsets.US_ASCII) == "data") {
                "WAV master tidak sesuai"
            }
            require(b.getInt(16) == 16) { "Header WAV tidak dikenal" }
            val type = b.getShort(20).toInt() and 65535
            val channels = b.getShort(22).toInt() and 65535
            val rate = b.getInt(24)
            val bits = b.getShort(34).toInt() and 65535
            val bytes = b.getInt(40).toLong() and 0xFFFFFFFFL
            require((type == 3 && bits == 32) ||
                (type == 1 && (bits == 16 || bits == 24))) {
                "Encoding master tidak didukung"
            }
            require(bytes % (bits / 8) == 0L && bytes <= file.length() - 44L) {
                "Data audio terpotong"
            }
            return WavInfo(rate, channels, bits, type, bytes)
        }
    }

    private class FloatSampleReader(
        file: File,
        private val info: WavInfo,
        private val total: Long
    ) : AutoCloseable {
        private val stream = BufferedInputStream(FileInputStream(file), 131072)
        private val data = ByteArray(16384)
        private var pos = 0
        private var available = 0
        private var index = 0L
        init {
            var header = 44L
            while (header > 0) {
                val skipped = stream.skip(header)
                if (skipped <= 0L) throw IOException("Tidak bisa melewati header WAV")
                header -= skipped
            }
        }
        private fun nextByte(): Int {
            if (pos == available) {
                available = stream.read(data)
                pos = 0
                if (available <= 0) throw IOException("Audio terpotong")
            }
            return data[pos++].toInt() and 255
        }
        fun next(): Float {
            if (index++ >= total) return 0f
            val a = nextByte()
            val b = nextByte()
            val c = if (info.bits >= 24) nextByte() else 0
            val d = if (info.bits == 32) nextByte() else 0
            val value = when (info.bits) {
                16 -> ((b shl 8 or a).toShort().toInt()) / 32768f
                24 -> {
                    var x = a or (b shl 8) or (c shl 16)
                    if ((x and 0x800000) != 0) x = x or -0x1000000
                    x / 8388608f
                }
                else -> Float.fromBits(a or (b shl 8) or (c shl 16) or (d shl 24))
            }
            return if (value.isFinite()) value.coerceIn(-1f, 1f) else 0f
        }
        override fun close() = stream.close()
    }

    private class SincResampler(
        private val source: FloatSampleReader,
        private val sourceRate: Int,
        private val totalSamples: Long
    ) {
        private val ring = FloatArray(128)
        private var readIndex = -1L
        private val radius = 24
        private val phases = if (sourceRate == 44100) 160 else 1
        private val ratio = sourceRate.toDouble() / 48000.0
        private val taps: Array<FloatArray> = Array(phases) { phase ->
            val fraction = phase.toDouble() / phases
            val cutoff = min(1.0, 48000.0 / sourceRate) * 0.93
            val weights = FloatArray(radius * 2 + 1)
            var sum = 0.0
            for (k in -radius..radius) {
                val diff = k - fraction
                val z = cutoff * diff
                val sinc = if (abs(z) < 0.0000001) 1.0 else sin(PI * z) / (PI * z)
                val window = 0.5 + 0.5 * cos(PI * k / radius)
                val weight = cutoff * sinc * window
                weights[k + radius] = weight.toFloat()
                sum += weight
            }
            for (k in weights.indices) weights[k] = (weights[k] / sum).toFloat()
            weights
        }

        fun sample(frameOut: Long): Float {
            if (sourceRate == 48000) {
                // Pass-through at original quality: no resampling of 48 kHz masters.
                return source.next()
            }
            val position = frameOut * ratio
            val center = floor(position).toLong()
            val fraction = position - center
            val phase = if (phases == 1) 0
                else (fraction * phases).toInt().coerceIn(0, phases - 1)
            val needed = min(totalSamples - 1, center + radius)
            while (readIndex < needed) {
                readIndex++
                ring[(readIndex and 127L).toInt()] = source.next()
            }
            val weights = taps[phase]
            var sum = 0.0
            for (k in -radius..radius) {
                val n = center + k
                if (n >= 0 && n < totalSamples) {
                    check(n <= readIndex && readIndex - n < ring.size) {
                        "Resampler buffer invalid"
                    }
                    sum += ring[(n and 127L).toInt()] * weights[k + radius]
                }
            }
            return sum.toFloat().coerceIn(-1f, 1f)
        }
    }

    /**
     * Conservative VO finishing: 70 Hz high-pass, 2:1 light compressor,
     * a modest 0.6 dB lift and -1 dBFS limiter. Not a voice cloning effect.
     */
    private class VoiceMastering {
        private val hpAlpha = exp(-2.0 * PI * 70.0 / 48000.0).toFloat()
        private var previousX = 0f
        private var previousY = 0f
        private var envelope = 0f
        private val attack = exp(-1.0 / (0.010 * 48000.0)).toFloat()
        private val release = exp(-1.0 / (0.140 * 48000.0)).toFloat()

        fun apply(x: Float): Float {
            val hp = hpAlpha * (previousY + x - previousX)
            previousX = x
            previousY = hp
            val amplitude = abs(hp)
            val smoothing = if (amplitude > envelope) attack else release
            envelope = smoothing * envelope + (1f - smoothing) * amplitude
            val threshold = 0.12589254f // -18 dBFS
            val gain = if (envelope > threshold) {
                sqrt(threshold / envelope)
            } else 1f
            val output = hp * gain * 1.0715f
            return output.coerceIn(-0.89125f, 0.89125f) // -1 dBFS
        }
    }
}
