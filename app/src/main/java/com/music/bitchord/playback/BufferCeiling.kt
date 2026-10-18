package com.music.bitchord.playback

/**
 * How many bytes the player may hold ahead of the playhead.
 *
 * The Wi-Fi ceiling is sized for a whole track at a typical bitrate (~6
 * minutes at 160kbps), because read-ahead is also what writes the track into
 * [AudioCache] and a seek past the buffered part is the one that waits on the
 * network.
 *
 * On a metered connection the same argument is the one that caps it: every
 * byte held is a byte the listener paid for, and half a track ahead is still
 * several minutes of slack. [bufferCeilingBytes] answers for the connection in
 * hand, so the Wi-Fi path is unchanged.
 */
internal const val FAR_BUFFER_BYTES = 8 * 1024 * 1024

/** ~3 minutes at 160kbps: enough slack on cellular without a whole track held. */
internal const val CELLULAR_BUFFER_BYTES = 4 * 1024 * 1024

/**
 * The byte ceiling for the active connection.
 *
 * `null` is "offline or not known yet" and takes the Wi-Fi ceiling: taking
 * bytes away from a player that has not been told what it is on would make the
 * first play of every launch start short-buffered, and a metered callback lands
 * moments later. A metered Wi-Fi hotspot counts as cellular, matching how
 * [com.music.bitchord.data.settings.AppSettings.effectiveAudioQuality] already
 * reads a metered connection.
 */
internal fun bufferCeilingBytes(metered: Boolean?): Int =
    if (metered == true) CELLULAR_BUFFER_BYTES else FAR_BUFFER_BYTES
