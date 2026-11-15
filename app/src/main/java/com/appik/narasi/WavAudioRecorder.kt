package com.appik.narasi

import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import kotlin.concurrent.thread

/**
 * Records an uncompressed, editable PCM-16 mono WAV master.
 * Prefers 48 kHz; tries 44.1 kHz if the device cannot start at 48 kHz.
 * Voice enhancement must operate on a copy, never on this source file.
 */
internal class WavAudioRecorder(private val context: Context) {
    data class Result(
        val file: File,
        val sampleRate: Int,
        val pcmBytes: Long
    ) {
        val durationMs: Long
            get() = pcmBytes * 1000L / (sampleRate * 2L)
    }

    private data class Session(
        val record: AudioRecord,
        val file: File,
        val sampleRate: Int,
        val worker: Thread
    )

    @Volatile private var capturing = false
    @Volatile private var pcmBytes = 0L
    @Volatile private var writerError: Throwable? = null
    private var session: Session? = null

    @Synchronized
    fun start(file: File): Int {
        check(session == null) { "Perekaman sebelumnya belum selesai" }

        // Request microphone input before opening a new master file.
        val (record, rate) = startSupportedRecorder()
        var output: RandomAccessFile? = null
        try {
            output = RandomAccessFile(file, "rw")
            output.setLength(0L)
            writeWavHeader(output, rate, 0L)

            val wav = output
            val bufferSize = maxOf(4096, (rate / 10) * 2)
            capturing = true
            pcmBytes = 0L
            writerError = null

            val worker = thread(start = false, name = "Narasi-PCM-WAV") {
                try {
                    val buffer = ByteArray(bufferSize)
                    while (capturing) {
                        val count = record.read(
                            buffer, 0, buffer.size, AudioRecord.READ_BLOCKING
                        )
                        if (count > 0) {
                            if (pcmBytes + count > 0xFFFFFFFFL - 36L) {
                                throw IOException("Rekaman melebihi batas ukuran WAV")
                            }
                            wav.write(buffer, 0, count)
                            pcmBytes += count
                        } else if (count < 0 && capturing) {
                            throw IOException("Mikrofon gagal membaca data: " + count)
                        }
                    }
                    wav.seek(0L)
                    writeWavHeader(wav, rate, pcmBytes)
                    wav.fd.sync()
                } catch (error: Throwable) {
                    writerError = error
                } finally {
                    try { wav.close() } catch (_: Exception) {}
                }
            }
            session = Session(record, file, rate, worker)
            worker.start()
            return rate
        } catch (error: Throwable) {
            capturing = false
            session = null
            try { record.stop() } catch (_: Exception) {}
            try { record.release() } catch (_: Exception) {}
            try { output?.close() } catch (_: Exception) {}
            file.delete()
            throw error
        }
    }

    @Synchronized
    fun stop(): Result {
        val active = session ?: throw IllegalStateException("Tidak ada rekaman aktif")
        capturing = false
        try { active.record.stop() } catch (_: Exception) {}
        try {
            active.worker.join(3000L)
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        if (active.worker.isAlive) {
            active.worker.interrupt()
        }
        try { active.record.release() } catch (_: Exception) {}
        session = null

        if (active.worker.isAlive) {
            throw IOException("Penyimpanan WAV belum selesai. Rekaman tidak ditambahkan.")
        }

        val error = writerError
        val size = pcmBytes
        if (error != null || size <= 0L || !active.file.isFile ||
            active.file.length() != 44L + size
        ) {
            active.file.delete()
            throw IOException("Rekaman WAV gagal disimpan", error)
        }
        return Result(active.file, active.sampleRate, size)
    }

    @Synchronized
    fun cancel() {
        val active = session ?: return
        capturing = false
        try { active.record.stop() } catch (_: Exception) {}
        try { active.worker.join(3000L) } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        try { active.record.release() } catch (_: Exception) {}
        if (!active.worker.isAlive) active.file.delete()
        session = null
    }

    private fun startSupportedRecorder(): Pair<AudioRecord, Int> {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val supportsRaw = audioManager.getProperty(
            AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED
        ).equals("true", ignoreCase = true)

        val sources = if (supportsRaw) {
            intArrayOf(
                MediaRecorder.AudioSource.UNPROCESSED,
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                MediaRecorder.AudioSource.MIC
            )
        } else {
            intArrayOf(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                MediaRecorder.AudioSource.MIC
            )
        }

        var lastError: Throwable? = null
        for (rate in intArrayOf(48000, 44100)) {
            val minBuffer = AudioRecord.getMinBufferSize(
                rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBuffer <= 0) continue
            for (source in sources) {
                var record: AudioRecord? = null
                try {
                    record = AudioRecord.Builder()
                        .setAudioSource(source)
                        .setAudioFormat(
                            AudioFormat.Builder()
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setSampleRate(rate)
                                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                                .build()
                        )
                        .setBufferSizeInBytes(maxOf(minBuffer * 2, rate / 5 * 2))
                        .build()
                    check(record.state == AudioRecord.STATE_INITIALIZED) {
                        "Mikrofon tidak siap"
                    }
                    record.startRecording()
                    check(record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                        "Mikrofon gagal memulai"
                    }
                    return record to record.sampleRate
                } catch (error: Exception) {
                    lastError = error
                    try { record?.release() } catch (_: Exception) {}
                }
            }
        }
        throw IOException("Perangkat tidak mendukung perekaman PCM 48/44,1 kHz", lastError)
    }

    private fun writeWavHeader(file: RandomAccessFile, rate: Int, dataBytes: Long) {
        file.writeBytes("RIFF")
        writeIntLE(file, dataBytes + 36L)
        file.writeBytes("WAVE")
        file.writeBytes("fmt ")
        writeIntLE(file, 16L)
        writeShortLE(file, 1) // PCM
        writeShortLE(file, 1) // Mono
        writeIntLE(file, rate.toLong())
        writeIntLE(file, (rate * 2).toLong()) // Bytes per second
        writeShortLE(file, 2) // Block alignment
        writeShortLE(file, 16) // Bits per sample
        file.writeBytes("data")
        writeIntLE(file, dataBytes)
    }

    private fun writeIntLE(file: RandomAccessFile, value: Long) {
        for (shift in 0..24 step 8) {
            file.write(((value shr shift) and 0xFFL).toInt())
        }
    }

    private fun writeShortLE(file: RandomAccessFile, value: Int) {
        file.write(value and 0xFF)
        file.write((value shr 8) and 0xFF)
    }
}
