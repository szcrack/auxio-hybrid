package com.truesample.usbaudio

import java.nio.ByteBuffer

/**
 * JNI Native Bridge to TrueSample's high-resolution bit-perfect USB Audio driver (libusbaudio.so).
 * Communicates directly with USB Audio Class 2 (UAC2) endpoints, bypassing Android AudioFlinger resampling.
 */
object NativeBridge {
    init {
        try {
            System.loadLibrary("usb-1.0")
            System.loadLibrary("decoder")
            System.loadLibrary("usbaudio")
        } catch (e: Throwable) {
            e.printStackTrace()
        }
    }

    external fun nativeOpen(fileDescriptor: Int, rawDescriptors: ByteArray): Long

    external fun nativeClose(handle: Long)

    external fun nativeDescribe(handle: Long): String

    external fun nativeFormats(handle: Long): IntArray

    external fun nativeModes(handle: Long): IntArray

    external fun nativePlan(
        handle: Long,
        sampleRate: Int,
        bitDepth: Int,
        channels: Int,
        bufferSize: Int,
        flags: Int
    ): IntArray

    external fun nativeStart(
        handle: Long,
        sampleRate: Int,
        bitDepth: Int,
        channels: Int,
        bufferSize: Int,
        flags: Int,
        mode: Int
    ): IntArray

    external fun nativeWrite(handle: Long, buffer: ByteBuffer, length: Int): Int

    external fun nativeDrain(handle: Long)

    external fun nativeStop(handle: Long)

    external fun nativeVolumeRange(handle: Long): IntArray

    external fun nativeSetVolume(handle: Long, volume: Int): Boolean

    external fun nativeStats(handle: Long): LongArray

    external fun nativeSetEqualizer(handle: Long, bands: FloatArray, preamp: Float)

    external fun nativeSetLogFile(path: String)
}
