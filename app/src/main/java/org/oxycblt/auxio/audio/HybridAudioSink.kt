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
 * Hybrid AudioSink que conmuta limpiamente entre el driver nativo UAC2 Bit-Perfect
 * y el DefaultAudioSink del sistema Android (ExoPlayer).
 *
 * REGLA FUNDAMENTAL:
 * Solo conmuta al driver nativo si este esta activamente abierto y aceptando PCM (nativeHandle != 0L).
 * En cualquier otro caso (audifonos USB normales, parlantes, bluetooth, jack 3.5mm),
 * el audio fluye por DefaultAudioSink para garantizar que NUNCA se corte el sonido ni se congele ExoPlayer.
 */
class HybridAudioSink(
    private val usbDacManager: UsbDacManager,
    private val defaultSink: DefaultAudioSink
) : AudioSink {

    private var currentFormat: Format? = null

    /** Indica si actualmente se esta usando el driver nativo de bajo nivel */
    private val isUsingBitPerfectDac: Boolean
        get() = usbDacManager.isBitPerfectActive

    override fun setListener(listener: AudioSink.Listener) {
        defaultSink.setListener(listener)
    }

    override fun supportsFormat(format: Format): Boolean {
        if (isUsingBitPerfectDac) {
            return format.sampleMimeType == null || format.sampleMimeType == "audio/raw"
        }
        return defaultSink.supportsFormat(format)
    }

    override fun getFormatSupport(format: Format): Int {
        if (isUsingBitPerfectDac) {
            return AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY
        }
        return defaultSink.getFormatSupport(format)
    }

    override fun getCurrentPositionUs(sourceEnded: Boolean): Long {
        // Siempre delegar a defaultSink para mantener la barra de progreso de ExoPlayer perfectamente sincronizada
        return defaultSink.getCurrentPositionUs(sourceEnded)
    }

    override fun configure(
        inputFormat: Format,
        specifiedBufferSize: Int,
        outputChannels: IntArray?
    ) {
        currentFormat = inputFormat
        // Siempre configurar defaultSink para que este listo inmediatamente si se desconecta o conecta un dispositivo
        defaultSink.configure(inputFormat, specifiedBufferSize, outputChannels)

        if (isUsingBitPerfectDac) {
            val sampleRate = if (inputFormat.sampleRate != Format.NO_VALUE) inputFormat.sampleRate else 44100
            val channelCount = if (inputFormat.channelCount != Format.NO_VALUE) inputFormat.channelCount else 2
            val bitDepth = when (inputFormat.pcmEncoding) {
                C.ENCODING_PCM_24BIT -> 24
                C.ENCODING_PCM_32BIT -> 32
                else -> 16
            }
            usbDacManager.startPlayback(sampleRate, bitDepth, channelCount)
        }
    }

    override fun play() {
        defaultSink.play()
    }

    override fun handleDiscontinuity() {
        defaultSink.handleDiscontinuity()
    }

    override fun handleBuffer(
        buffer: ByteBuffer,
        presentationTimeUs: Long,
        encodedAccessUnitCount: Int
    ): Boolean {
        if (isUsingBitPerfectDac) {
            val remaining = buffer.remaining()
            if (remaining > 0) {
                val written = usbDacManager.writePcmBuffer(buffer, remaining)
                if (written > 0) {
                    buffer.position(buffer.position() + written)
                    return !buffer.hasRemaining()
                }
            }
            // Si el driver nativo no pudo escribir, no congelar el reproductor: pasar por defaultSink
        }
        return defaultSink.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
    }

    override fun playToEndOfStream() {
        if (isUsingBitPerfectDac) {
            usbDacManager.stopPlayback()
        }
        defaultSink.playToEndOfStream()
    }

    override fun isEnded(): Boolean {
        return defaultSink.isEnded
    }

    override fun hasPendingData(): Boolean {
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

    override fun getAudioTrackBufferSizeUs(): Long {
        return defaultSink.audioTrackBufferSizeUs
    }

    override fun enableTunnelingV21() {
        defaultSink.enableTunnelingV21()
    }

    override fun disableTunneling() {
        defaultSink.disableTunneling()
    }

    override fun setVolume(volume: Float) {
        if (isUsingBitPerfectDac) {
            val hwVol = (volume * 100).toInt().coerceIn(0, 100)
            usbDacManager.setHardwareVolume(hwVol)
        }
        defaultSink.setVolume(volume)
    }

    override fun pause() {
        if (isUsingBitPerfectDac) {
            usbDacManager.stopPlayback()
        }
        defaultSink.pause()
    }

    override fun flush() {
        if (isUsingBitPerfectDac) {
            usbDacManager.stopPlayback()
        }
        defaultSink.flush()
    }

    override fun reset() {
        if (isUsingBitPerfectDac) {
            usbDacManager.closeDac()
        }
        defaultSink.reset()
    }
}
