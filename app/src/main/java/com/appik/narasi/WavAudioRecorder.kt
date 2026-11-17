package com.appik.narasi

import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import kotlin.concurrent.thread

/**
 * Non-destructive recording master. Always writes the original samples as received
 * from AudioRecord; never applies automatic enhancement or lossy compression.
 *
 * Prefer 96kHz/32-bit IEEE float, then 48kHz/32-bit float, then 24-bit packed PCM.
 * The Android route may resample internally: we report the *negotiated API format*,
 * not an unsupported claim about the microphone's physical converter resolution.
 */
internal class WavAudioRecorder(private val context: Context) {
    data class Format(
        val rate: Int,
        val encoding: Int,
        val bits: Int,
        val bytesPerSample: Int,
        val wavType: Int,
        val source: Int
    ) {
        val label: String get() =
            (rate / 1000).toString() + " kHz / " + bits + "-bit " +
                (if (wavType == 3) "Float" else "PCM") + " WAV"
        val sourceLabel: String get() = when (source) {
            MediaRecorder.AudioSource.UNPROCESSED -> "UNPROCESSED"
            MediaRecorder.AudioSource.VOICE_RECOGNITION -> "VOICE_RECOGNITION"
            else -> "MIC"
        }
    }

    data class Result(val file: File, val format: Format, val pcmBytes: Long) {
        val sampleRate: Int get() = format.rate
        val durationMs: Long get() = pcmBytes * 1000L /
            (format.rate.toLong() * format.bytesPerSample)
    }

    private data class Session(
        val record: AudioRecord,
        val file: File,
        val format: Format,
        val worker: Thread
    )

    @Volatile private var capturing = false
    @Volatile private var pcmBytes = 0L
    @Volatile private var writerError: Throwable? = null
    private var session: Session? = null
    @Volatile var activeFormat: Format? = null
        private set

    @Synchronized
    fun start(file: File): Int {
        check(session == null) { "Sesi perekaman sebelumnya belum selesai" }
        val (recorder, format) = openBestRecorder()
        var output: RandomAccessFile? = null
        try {
            file.parentFile?.mkdirs()
            val wav = RandomAccessFile(file, "rw")
            output = wav
            wav.setLength(0)
            writeWavHeader(wav, format, 0)
            val buffer = ByteBuffer.allocateDirect(
                maxOf(16384, AudioRecord.getMinBufferSize(
                    format.rate, AudioFormat.CHANNEL_IN_MONO, format.encoding
                ) * 2)
            )
            val scratch = ByteArray(16384)
            pcmBytes = 0
            writerError = null
            capturing = true
            activeFormat = format
            val worker = thread(start = false, name = "Narasi-HiRes-WAV") {
                try {
                    while (capturing) {
                        buffer.clear()
                        val read = recorder.read(buffer, buffer.capacity(), AudioRecord.READ_BLOCKING)
                        if (read > 0) {
                            val aligned = read - read % format.bytesPerSample
                            if (aligned <= 0) continue
                            if (pcmBytes + aligned > 0xFFFFFFFFL - 36L) {
                                throw IOException("Mencapai batas WAV 4 GB. Hentikan rekaman.")
                            }
                            buffer.position(0)
                            buffer.limit(aligned)
                            var remaining = aligned
                            while (remaining > 0) {
                                val amount = minOf(remaining, scratch.size)
                                buffer.get(scratch, 0, amount)
                                wav.write(scratch, 0, amount)
                                remaining -= amount
                            }
                            pcmBytes += aligned.toLong()
                        } else if (read < 0 && capturing) {
                            throw IOException("Gagal membaca mikrofon, kode " + read)
                        }
                    }
                    wav.seek(0)
                    writeWavHeader(wav, format, pcmBytes)
                    wav.fd.sync()
                } catch (error: Throwable) {
                    writerError = error
                } finally {
                    try { wav.close() } catch (_: Exception) {}
                }
            }
            session = Session(recorder, file, format, worker)
            worker.start()
            return format.rate
        } catch (error: Throwable) {
            capturing = false
            activeFormat = null
            session = null
            try { recorder.stop() } catch (_: Exception) {}
            try { recorder.release() } catch (_: Exception) {}
            try { output?.close() } catch (_: Exception) {}
            file.delete()
            throw error
        }
    }

    @Synchronized
    fun stop(): Result {
        val current = session ?: error("Tidak ada perekaman aktif")
        capturing = false
        try { current.record.stop() } catch (_: Exception) {}
        try { current.worker.join(5000L) } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        try { current.record.release() } catch (_: Exception) {}
        session = null
        activeFormat = null
        if (current.worker.isAlive) {
            current.worker.interrupt()
            throw IOException("Penulisan WAV belum selesai")
        }
        if (writerError != null || pcmBytes <= 0 ||
            current.file.length() != 44L + pcmBytes
        ) {
            val error = writerError
            current.file.delete()
            throw IOException("File WAV tidak lengkap", error)
        }
        return Result(current.file, current.format, pcmBytes)
    }

    @Synchronized
    fun cancel() {
        val current = session ?: return
        capturing = false
        try { current.record.stop() } catch (_: Exception) {}
        try { current.worker.join(5000L) } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        try { current.record.release() } catch (_: Exception) {}
        if (!current.worker.isAlive) current.file.delete()
        session = null
        activeFormat = null
    }

    private fun openBestRecorder(): Pair<AudioRecord, Format> {
        val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val supportsRaw = manager.getProperty(
            AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED
        ).equals("true", ignoreCase = true)
        val sources = if (supportsRaw)
            intArrayOf(MediaRecorder.AudioSource.UNPROCESSED,
                MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC)
        else intArrayOf(MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.MIC)

        val profiles = mutableListOf(
            Triple(96000, AudioFormat.ENCODING_PCM_FLOAT, 32),
            Triple(48000, AudioFormat.ENCODING_PCM_FLOAT, 32)
        )
        if (Build.VERSION.SDK_INT >= 31) {
            profiles += Triple(96000, AudioFormat.ENCODING_PCM_24BIT_PACKED, 24)
            profiles += Triple(48000, AudioFormat.ENCODING_PCM_24BIT_PACKED, 24)
        }
        profiles += Triple(48000, AudioFormat.ENCODING_PCM_16BIT, 16)
        profiles += Triple(44100, AudioFormat.ENCODING_PCM_16BIT, 16)

        var lastError: Throwable? = null
        for ((rate, encoding, bits) in profiles) {
            val minSize = AudioRecord.getMinBufferSize(
                rate, AudioFormat.CHANNEL_IN_MONO, encoding
            )
            if (minSize <= 0) continue
            for (source in sources) {
                var record: AudioRecord? = null
                try {
                    record = AudioRecord.Builder()
                        .setAudioSource(source)
                        .setAudioFormat(
                            AudioFormat.Builder()
                                .setEncoding(encoding)
                                .setSampleRate(rate)
                                .setChannelMask(AudioFormat.CHANNEL_IN_MONO).build()
                        )
                        .setBufferSizeInBytes(maxOf(minSize * 3, rate / 5 * (bits / 8)))
                        .build()
                    check(record.state == AudioRecord.STATE_INITIALIZED) {
                        "Mikrofon gagal inisialisasi"
                    }
                    record.startRecording()
                    check(record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                        "Mikrofon tidak merekam"
                    }
                    val actual = record.format
                    check(actual.encoding == encoding) { "Bit depth tidak sesuai" }
                    check(actual.sampleRate == rate) { "Sample rate tidak sesuai" }
                    val format = Format(rate, encoding, bits, bits / 8,
                        if (encoding == AudioFormat.ENCODING_PCM_FLOAT) 3 else 1, source)
                    return record to format
                } catch (error: Exception) {
                    lastError = error
                    try { record?.stop() } catch (_: Exception) {}
                    try { record?.release() } catch (_: Exception) {}
                }
            }
        }
        throw IOException("Mikrofon tidak mendukung format WAV yang diperlukan", lastError)
    }

    companion object {
        internal fun writeWavHeader(file: RandomAccessFile, format: Format, bytes: Long) {
            file.writeBytes("RIFF")
            writeIntLE(file, bytes + 36L)
            file.writeBytes("WAVEfmt ")
            writeIntLE(file, 16L)
            writeShortLE(file, format.wavType)
            writeShortLE(file, 1)
            writeIntLE(file, format.rate.toLong())
            writeIntLE(file, format.rate.toLong() * format.bytesPerSample)
            writeShortLE(file, format.bytesPerSample)
            writeShortLE(file, format.bits)
            file.writeBytes("data")
            writeIntLE(file, bytes)
        }
        private fun writeIntLE(file: RandomAccessFile, value: Long) {
            for (shift in 0..24 step 8) file.write(((value shr shift) and 255L).toInt())
        }
        private fun writeShortLE(file: RandomAccessFile, value: Int) {
            file.write(value and 255)
            file.write((value shr 8) and 255)
        }
    }
}
