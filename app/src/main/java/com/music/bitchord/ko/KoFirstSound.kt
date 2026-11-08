package com.music.bitchord.ko

import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.innertube.StreamResolver
import com.music.bitchord.data.net.FirstSoundRace
import com.music.bitchord.data.sources.SourceStream
import com.music.bitchord.data.sources.TrackMatcher
import com.music.bitchord.playback.DirectSourceGate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Waits in front of the first note that do not need to be serial (phone
 * 11-08 user log, see ship/lanes/opus-firstsound.md).
 */
internal object KoFirstSound {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * The cache-entry settle of Direct Source Stream ([com.music.bitchord.playback.AudioCache]):
     * the addon lookup and the YouTube walk run together — see [FirstSoundRace].
     * The playing row races them, as [DirectSourceGate.firstAnswer] already does
     * for the direct resolve; any other row waits the addon window as before.
     * [substitutesRefused] skips the lookup exactly as the old code did.
     */
    suspend fun settleDirect(
        videoId: String,
        target: TrackMatcher.Target,
        substitutesRefused: Boolean,
    ): FirstSoundRace.Settle<SourceStream> = FirstSoundRace.settle(
        scope = CoroutineScope(scope.coroutineContext + TrackLog.about(videoId)),
        racesYouTube = !substitutesRefused && DirectSourceGate.isPlaying(videoId),
        youTube = { StreamResolver.resolve(videoId) },
        lookup = {
            if (substitutesRefused) {
                null
            } else {
                runCatching { DirectSourceGate.lookup(videoId, target) }
                    .onFailure { if (it is CancellationException) throw it }
                    .getOrNull()
            }
        },
        race = { walk ->
            val answer = runCatching {
                DirectSourceGate.startLookup(videoId, target)
                    ?.let { DirectSourceGate.firstAnswer(it, walk, videoId) }
            }
                .onFailure { if (it is CancellationException) throw it }
                .getOrNull()
            (answer as? DirectSourceGate.FirstAnswer.Addon)?.stream
        },
    )

    /**
     * A music video held for its audio version (MusicOnlyPlayback) also
     * holds its own resolve, since the hold sits above the resolver: phone
     * 11-08 waited 1.5-2.3 s for the answer and only then spent ~2.5 s on the
     * walk. The playing row's walk is started beside the lookup instead. It is
     * coalesced and cached, so the held open reuses it; when the answer is a
     * song version (9 of 80 lookups that night) it is one spare walk.
     * Nothing plays earlier: the hold still decides which recording sounds.
     */
    fun warmHeldVideo(videoId: String) {
        scope.launch(TrackLog.about(videoId)) {
            runCatching { StreamResolver.resolve(videoId) }
                .onFailure {
                    if (it !is CancellationException) {
                        TrackLog.d("BitChord", "first-sound: warm walk for held video failed: ${it.message}", about = videoId)
                    }
                }
        }
    }
}
