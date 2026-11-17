package com.appik.narasi

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Plays PCM-16, PCM-24 packed or IEEE float WAV directly through AudioTrack.
 * This avoids assuming MediaPlayer accepts 32-bit-float WAV masters.
 */
internal class WavAudioPlayer {
    private data class Playback(
        val alive: AtomicBoolean,
        @Volatile var track: AudioTrack? = null
    )
    private var current: Playback? = null

    @Synchronized
    fun stop() {
        val previous = current ?: return
        previous.alive.set(false)
        try { previous.track?.pause() } catch (_: Exception) {}
        try { previous.track?.flush() } catch (_: Exception) {}
        current = null
    }

    @Synchronized
    fun play(file: File, onCompleted: (Throwable?) -> Unit) {
        stop()
        val session = Playback(AtomicBoolean(true))
        current = session
        thread(name = "Narasi-PCM-Player", isDaemon = true) {
            var error: Throwable? = null
            try {
                RandomAccessFile(file, "r").use { input ->
                    val h = ByteArray(44)
                    input.readFully(h)
                    require(String(h, 0, 4, Charsets.US_ASCII) == "RIFF" &&
                        String(h, 8, 4, Charsets.US_ASCII) == "WAVE" &&
                        String(h, 36, 4, Charsets.US_ASCII) == "data") {
                        "WAV tidak dikenali"
                    }
                    val b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN)
                    val channels = b.getShort(22).toInt() and 65535
                    val rate = b.getInt(24)
                    val bits = b.getShort(34).toInt() and 65535
                    val type = b.getShort(20).toInt() and 65535
                    require(channels == 1 && rate > 0) { "Audio harus mono" }
                    val encoding = when {
                        type == 3 && bits == 32 -> AudioFormat.ENCODING_PCM_FLOAT
                        type == 1 && bits == 24 -> AudioFormat.ENCODING_PCM_24BIT_PACKED
                        type == 1 && bits == 16 -> AudioFormat.ENCODING_PCM_16BIT
                        else -> throw IllegalStateException("PCM WAV tidak didukung")
                    }
                    val minBytes = AudioTrack.getMinBufferSize(
                        rate, AudioFormat.CHANNEL_OUT_MONO, encoding
                    )
                    check(minBytes > 0) { "Playback tidak mendukung format ini" }
                    val track = AudioTrack.Builder()
                        .setAudioAttributes(
                            AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build()
                        )
                        .setAudioFormat(
                            AudioFormat.Builder()
                                .setEncoding(encoding)
                                .setSampleRate(rate)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                                .build()
                        )
                        .setBufferSizeInBytes(maxOf(minBytes * 2, 32768))
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .build()
                    session.track = track
                    check(track.state == AudioTrack.STATE_INITIALIZED) {
                        "Speaker tidak dapat dibuka"
                    }
                    track.play()
                    val chunk = ((32768 / (bits / 8)) * (bits / 8))
                    val buffer = ByteArray(chunk)
                    val direct = ByteBuffer.allocateDirect(chunk)
                    val bytes = b.getInt(40).toLong() and 0xFFFFFFFFL
                    check(bytes <= input.length() - 44L) { "Audio terpotong" }
                    var consumed = 0L
                    while (session.alive.get() && consumed < bytes) {
                        val read = minOf(chunk.toLong(), bytes - consumed).toInt()
                        input.readFully(buffer, 0, read)
                        consumed += read
                        direct.clear()
                        direct.put(buffer, 0, read)
                        direct.flip()
                        while (session.alive.get() && direct.hasRemaining()) {
                            val amount = track.write(
                                direct, direct.remaining(), AudioTrack.WRITE_BLOCKING
                            )
                            if (amount <= 0) throw IllegalStateException("Speaker gagal memutar: " + amount)
                        }
                    }
                    if (session.alive.get()) {
                        // Drain buffered audio without truncating the last part.
                        val frames = track.playbackHeadPosition.toLong()
                        val durationFrames = bytes / (bits / 8)
                        val ms = ((durationFrames - frames).coerceAtLeast(0L) *
                            1000L / rate).coerceAtMost(2500L)
                        if (ms > 0) Thread.sleep(ms)
                    }
                }
            } catch (e: Throwable) {
                if (session.alive.get()) error = e
            } finally {
                try { session.track?.stop() } catch (_: Exception) {}
                try { session.track?.release() } catch (_: Exception) {}
                synchronized(this) {
                    if (current === session) current = null
                }
                onCompleted(error)
            }
        }
    }
}
