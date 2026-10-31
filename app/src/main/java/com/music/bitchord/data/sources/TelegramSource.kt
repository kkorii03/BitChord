package com.music.bitchord.data.sources

import com.music.bitchord.data.TrackLog
import com.music.bitchord.data.model.Song
import com.music.bitchord.telegram.TelegramAuthStage
import com.music.bitchord.telegram.TelegramClientManager
import com.music.bitchord.telegram.TelegramLog
import com.music.bitchord.telegram.TelegramStreamUri
import com.music.bitchord.telegram.TelegramTrackMapper

private const val TAG = "BitChord"

/**
 * The listener's own Telegram chats as a music source.
 *
 * Ported from Hexclyde/bitchord-morphe-patches (GPL-3.0-or-later), reduced to
 * server-side search and progressive playback. It holds no index and starts no
 * background work: a search opens the TDLib client for that request, and the
 * stream's DataSource keeps it open only while a Telegram track is playing.
 */
class TelegramSource(
    override val config: SourceConfig,
) : MusicSource, SourceRegistry.ConfigBacked {

    override val configId: String get() = config.id
    override val kind: SourceKind get() = SourceKind.TELEGRAM
    override val displayName: String get() = config.label.ifBlank { SourceKind.TELEGRAM.label }

    override suspend fun health(): SourceHealth {
        val state = TelegramClientManager.state.value
        return when {
            state.stage == TelegramAuthStage.READY -> SourceHealth.Ok()
            state.stage == TelegramAuthStage.WAIT_CREDENTIALS ||
                state.stage == TelegramAuthStage.CLOSED ->
                SourceHealth.Unreachable("로그인이 필요합니다")
            state.error != null -> SourceHealth.Unreachable("로그인이 필요합니다")
            else -> SourceHealth.Unreachable("아직 로그인하지 않았습니다")
        }
    }

    override suspend fun search(
        query: String,
        limit: Int,
        waitForAll: Boolean,
        request: StreamRequest?,
    ): List<Song> {
        if (query.isBlank()) return emptyList()
        return try {
            TelegramClientManager.searchTracks(query, limit)
                .map { TelegramTrackMapper.toSong(it, config.id) }
        } catch (failure: Throwable) {
            TrackLog.w(TAG, "telegram: search failed — ${failure.message}")
            emptyList()
        }
    }

    override suspend fun stream(trackId: String, request: StreamRequest): SourceStream? {
        val track = try {
            TelegramClientManager.trackForStream(trackId)
        } catch (failure: Throwable) {
            TelegramLog.stream("unavailable", trackId.hashCode())
            return null
        }
        return SourceStream(
            url = TelegramStreamUri.of(track.id),
            format = TelegramTrackMapper.streamFormat(track),
            durationSec = track.durationSec.takeIf { it > 0 },
            trackTitle = track.title,
            trackArtist = track.artist,
        )
    }
}
