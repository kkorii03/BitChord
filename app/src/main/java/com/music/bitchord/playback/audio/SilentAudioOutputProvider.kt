package com.music.bitchord.playback.audio

import android.media.AudioDeviceInfo
import androidx.media3.common.C
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.audio.AudioOutput
import androidx.media3.exoplayer.audio.AudioOutputProvider
import com.music.bitchord.data.TrackLog
import java.nio.ByteBuffer

/**
 * An output that accepts decoded PCM without opening an AudioTrack.
 *
 * The quality-upgrade audition only has to prove that a candidate stream
 * decodes and buffers; nobody hears it. A stock sink still initialises an
 * AudioTrack as soon as its first buffer arrives, even with `playWhenReady`
 * false, so each high-quality candidate opened a second hardware track at its
 * own rate — 88.2 kHz for a hi-res FLAC — beside the one the listener was
 * using. Between hi-res tracks that repeated track creation is the failure this
 * exists to remove.
 *
 * The provider answers format support and consumes every write, so Media3's
 * decoder and load-control behaviour stay exactly as they were. Only the
 * hardware output is replaced.
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal class SilentAudioOutputProvider : AudioOutputProvider {

    override fun getFormatSupport(
        formatConfig: AudioOutputProvider.FormatConfig,
    ): AudioOutputProvider.FormatSupport {
        val format = formatConfig.format
        val supported = Util.isEncodingLinearPcm(format.pcmEncoding) &&
            format.sampleRate > 0 &&
            format.channelCount > 0 &&
            Util.getAudioTrackChannelConfig(format) != 0
        return AudioOutputProvider.FormatSupport.Builder()
            .setFormatSupportLevel(
                if (supported) {
                    AudioOutputProvider.FORMAT_SUPPORTED_DIRECTLY
                } else {
                    AudioOutputProvider.FORMAT_UNSUPPORTED
                },
            )
            .build()
    }

    override fun getOutputConfig(
        formatConfig: AudioOutputProvider.FormatConfig,
    ): AudioOutputProvider.OutputConfig {
        val format = formatConfig.format
        val channelMask = Util.getAudioTrackChannelConfig(format)
        if (!Util.isEncodingLinearPcm(format.pcmEncoding) ||
            format.sampleRate <= 0 ||
            format.channelCount <= 0 ||
            channelMask == 0
        ) {
            throw AudioOutputProvider.ConfigurationException("audition sink needs linear PCM")
        }
        return AudioOutputProvider.OutputConfig.Builder()
            .setEncoding(format.pcmEncoding)
            .setSampleRate(format.sampleRate)
            .setChannelMask(channelMask)
            .setIsTunneling(false)
            .setIsOffload(false)
            .setBufferSize(formatConfig.preferredBufferSize)
            .setAudioAttributes(formatConfig.audioAttributes)
            .setAudioSessionId(formatConfig.audioSessionId)
            .setVirtualDeviceId(formatConfig.virtualDeviceId)
            .setUsePlaybackParameters(formatConfig.enablePlaybackParameters)
            .setUseOffloadGapless(false)
            .build()
    }

    override fun getAudioOutput(
        outputConfig: AudioOutputProvider.OutputConfig,
    ): AudioOutput {
        TrackLog.d(
            "AUDIO_OUT",
            "audition output ${outputConfig.sampleRate}Hz encoding=${outputConfig.encoding} without AudioTrack",
        )
        return SilentAudioOutput(outputConfig)
    }

    override fun addListener(listener: AudioOutputProvider.Listener) = Unit

    override fun removeListener(listener: AudioOutputProvider.Listener) = Unit

    override fun release() = Unit
}

@androidx.annotation.OptIn(UnstableApi::class)
private class SilentAudioOutput(
    private val config: AudioOutputProvider.OutputConfig,
) : AudioOutput {

    private val channelCount = Integer.bitCount(config.channelMask).coerceAtLeast(1)
    private val bytesPerFrame = Util.getPcmFrameSize(config.encoding, channelCount)

    private var playbackParameters = PlaybackParameters.DEFAULT
    private var framesWritten = 0L

    override fun play() = Unit

    override fun pause() = Unit

    override fun write(
        buffer: ByteBuffer,
        encodedAccessUnitCount: Int,
        presentationTimeUs: Long,
    ): Boolean {
        val remaining = buffer.remaining()
        if (remaining > 0 && bytesPerFrame > 0) {
            framesWritten += remaining / bytesPerFrame
        }
        buffer.position(buffer.limit())
        return true
    }

    override fun flush() {
        framesWritten = 0L
    }

    override fun stop() {
        framesWritten = 0L
    }

    override fun release() = Unit

    override fun setVolume(volume: Float) = Unit

    override fun isOffloadedPlayback(): Boolean = false

    override fun getAudioSessionId(): Int = C.AUDIO_SESSION_ID_UNSET

    override fun getSampleRate(): Int = config.sampleRate

    override fun getBufferSizeInFrames(): Long = 0L

    override fun getPositionUs(): Long =
        if (config.sampleRate > 0) framesWritten * 1_000_000L / config.sampleRate else 0L

    override fun getPlaybackParameters(): PlaybackParameters = playbackParameters

    override fun isStalled(): Boolean = false

    override fun addListener(listener: AudioOutput.Listener) = Unit

    override fun removeListener(listener: AudioOutput.Listener) = Unit

    override fun setPlaybackParameters(playbackParameters: PlaybackParameters) {
        this.playbackParameters = playbackParameters
    }

    override fun setOffloadDelayPadding(delayInFrames: Int, paddingInFrames: Int) = Unit

    override fun setOffloadEndOfStream() = Unit

    override fun attachAuxEffect(effectId: Int) = Unit

    override fun setAuxEffectSendLevel(level: Float) = Unit

    override fun setPreferredDevice(preferredDevice: AudioDeviceInfo?) = Unit
}
