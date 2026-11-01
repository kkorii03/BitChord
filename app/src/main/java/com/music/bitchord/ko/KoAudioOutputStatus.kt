package com.music.bitchord.ko

import android.media.AudioFormat
import com.music.bitchord.data.settings.OutputPcmMode
import com.music.bitchord.playback.AudioOutputStatus
import com.music.bitchord.playback.audio.FallbackReason

/**
 * [KO] audio-output-status
 *
 * Korean labels and the fork's extra AudioTrack fallback bookkeeping for
 * [AudioOutputStatus]. Kept out of the upstream file so the next upstream
 * merge only has to re-add the `// [KO]` hook calls.
 */
object KoAudioOutputStatus {
    const val defaultDeviceName = "시스템 기본값"
    const val hearingAidProfile = "보청기"
    const val speakerFallbackDetail = "제조사 믹서 왜곡 방지를 위해 스피커 출력을 16비트로 제한"
    const val usbFallbackDetail = "USB 경로가 Float32 출력을 지원하지 않음"
    const val rejectedFloatDetail = "기기가 Float32 AudioTrack을 거부해 16비트 PCM으로 대체"
    const val encodingFallbackLabel = "16비트 대체"

    fun onAudioTrackPublished(
        snapshot: AudioOutputStatus.Snapshot,
        encoding: Int,
    ): AudioOutputStatus.Snapshot {
        val rejectedFloat = snapshot.requestedPcmMode == OutputPcmMode.FLOAT_32 &&
            encoding == AudioFormat.ENCODING_PCM_16BIT
        if (!rejectedFloat) return snapshot
        return snapshot.copy(
            fallbackReason = FallbackReason.ROUTE_LIMITATION,
            fallbackDetail = rejectedFloatDetail,
        )
    }
}
