package com.appik.narasi

/**
 * RNNoise native library + trained weights are linked into the APK.
 * Never downloads models or transmits microphone data to servers.
 */
internal object RnNoiseNative {
    init { System.loadLibrary("narasi_ai") }
    external fun create(): Long
    external fun frameSize(): Int
    external fun processFrame(handle: Long, mono48kNormalized: FloatArray)
    external fun destroy(handle: Long)
}
