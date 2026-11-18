package com.music.bitchord.ko

import com.music.bitchord.data.model.Song
import com.music.bitchord.data.videoradio.AutoplayMode
import com.music.bitchord.playback.videoRadioModeFor

/**
 * Whether the ⋮ menu offers "이 채널을 커버 채널로 지정" for [song].
 *
 * Only a row that could be a cover gets it: the artist's own upload (an
 * official MV typed OMV, a `- Topic` row, an "Official MV" on the artist's
 * channel) is the release, not a cover. That is the same call video radio
 * already makes from the row alone, so no lookup is needed. A channel
 * already marked always keeps the row, so the mark can be undone.
 */
internal fun offersCoverChannelMark(song: Song, designated: Boolean): Boolean =
    designated || videoRadioModeFor(song) == AutoplayMode.VIDEO_RADIO
