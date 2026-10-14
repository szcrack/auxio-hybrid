package org.oxycblt.auxio.audio

import androidx.media3.common.AudioAttributes
import androidx.media3.common.AuxEffectInfo
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import java.nio.ByteBuffer

/**
 * Hybrid AudioSink that dynamically routes decoded PCM audio:
 * - If USB DAC is connected: Routes directly to TrueSample bit-perfect native UAC2 driver.
 * - If no DAC is connected: Routes cleanly to Android's DefaultAudioSink (speakers, 3.5mm, bluetooth).
 */
class HybridAudioSink(
    private val usbDacManager: UsbDacManager,
    private val defaultSink: DefaultAudioSink
) : AudioSink {

    private var currentFormat: Format? = null
    private var isUsingDac: Boolean = false

    override fun setListener(listener: AudioSink.Listener) {
        defaultSink.setListener(listener)
    }

    override fun supportsFormat(format: Format): Boolean {
        if (usbDacManager.isDacConnected) {
            return format.sampleMimeType == null || format.sampleMimeType == "audio/raw"
        }
        return defaultSink.supportsFormat(format)
    }

    override fun getFormatSupport(format: Format): Int {
        if (usbDacManager.isDacConnected) {
            return AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY
        }
        return defaultSink.getFormatSupport(format)
    }

    override fun getCurrentPositionUs(sourceEnded: Boolean): Long {
        if (isUsingDac) {
            return C.TIME_UNSET
        }
        return defaultSink.getCurrentPositionUs(sourceEnded)
    }

    override fun configure(
        inputFormat: Format,
        specifiedBufferSize: Int,
        outputChannels: IntArray?
    ) {
        currentFormat = inputFormat
        if (usbDacManager.isDacConnected) {
            isUsingDac = true
            val sampleRate = if (inputFormat.sampleRate != Format.NO_VALUE) inputFormat.sampleRate else 44100
            val channelCount = if (inputFormat.channelCount != Format.NO_VALUE) inputFormat.channelCount else 2
            val bitDepth = when (inputFormat.pcmEncoding) {
                C.ENCODING_PCM_24BIT -> 24
                C.ENCODING_PCM_32BIT -> 32
                else -> 16
            }
            usbDacManager.startPlayback(sampleRate, bitDepth, channelCount)
        } else {
            isUsingDac = false
            defaultSink.configure(inputFormat, specifiedBufferSize, outputChannels)
        }
    }

    override fun play() {
        if (!isUsingDac) {
            defaultSink.play()
        }
    }

    override fun handleDiscontinuity() {
        if (!isUsingDac) {
            defaultSink.handleDiscontinuity()
        }
    }

    override fun handleBuffer(
        buffer: ByteBuffer,
        presentationTimeUs: Long,
        encodedAccessUnitCount: Int
    ): Boolean {
        if (usbDacManager.isDacConnected) {
            isUsingDac = true
            val remaining = buffer.remaining()
            if (remaining > 0) {
                val written = usbDacManager.writePcmBuffer(buffer, remaining)
                if (written > 0) {
                    buffer.position(buffer.position() + written)
                }
            }
            return !buffer.hasRemaining()
        } else {
            if (isUsingDac) {
                // DAC disconnected mid-playback; fallback to default sink
                isUsingDac = false
                currentFormat?.let { format ->
                    defaultSink.configure(format, 0, null)
                    defaultSink.play()
                }
            }
            return defaultSink.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        }
    }

    override fun playToEnd() {
        if (isUsingDac) {
            usbDacManager.stopPlayback()
        } else {
            defaultSink.playToEnd()
        }
    }

    override fun isEnded(): Boolean {
        if (isUsingDac) {
            return true
        }
        return defaultSink.isEnded
    }

    override fun hasPendingData(): Boolean {
        if (isUsingDac) {
            return false
        }
        return defaultSink.hasPendingData()
    }

    override fun setPlaybackParameters(playbackParameters: PlaybackParameters) {
        defaultSink.playbackParameters = playbackParameters
    }

    override fun getPlaybackParameters(): PlaybackParameters {
        return defaultSink.playbackParameters
    }

    override fun setSkipSilenceEnabled(skipSilenceEnabled: Boolean) {
        defaultSink.skipSilenceEnabled = skipSilenceEnabled
    }

    override fun getSkipSilenceEnabled(): Boolean {
        return defaultSink.skipSilenceEnabled
    }

    override fun setAudioAttributes(audioAttributes: AudioAttributes) {
        defaultSink.setAudioAttributes(audioAttributes)
    }

    override fun getAudioAttributes(): AudioAttributes? {
        return defaultSink.audioAttributes
    }

    override fun setAudioSessionId(audioSessionId: Int) {
        defaultSink.setAudioSessionId(audioSessionId)
    }

    override fun setAuxEffectInfo(auxEffectInfo: AuxEffectInfo) {
        defaultSink.setAuxEffectInfo(auxEffectInfo)
    }

    override fun enableTunneling() {
        defaultSink.enableTunneling()
    }

    override fun disableTunneling() {
        defaultSink.disableTunneling()
    }

    override fun setVolume(volume: Float) {
        if (isUsingDac) {
            val hwVol = (volume * 100).toInt().coerceIn(0, 100)
            usbDacManager.setHardwareVolume(hwVol)
        }
        defaultSink.setVolume(volume)
    }

    override fun pause() {
        if (isUsingDac) {
            usbDacManager.stopPlayback()
        } else {
            defaultSink.pause()
        }
    }

    override fun flush() {
        if (isUsingDac) {
            usbDacManager.stopPlayback()
        } else {
            defaultSink.flush()
        }
    }

    override fun reset() {
        if (isUsingDac) {
            usbDacManager.closeDac()
        }
        defaultSink.reset()
    }
}
