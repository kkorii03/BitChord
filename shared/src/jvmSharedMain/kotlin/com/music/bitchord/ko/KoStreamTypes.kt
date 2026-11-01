package com.music.bitchord.ko

import java.io.IOException

/** YouTube's "sign in to prove you are not a bot" challenge. */
class BotChallengeException(reason: String) : IOException(reason)

/** A URL googlevideo refused, and enough about the rendition it served to ask for it again. */
class RefusedStream(
    val videoId: String,
    val url: String?,
    val mimeType: String?,
    val kbps: Int,
    val proxyGeneration: Long? = null,
    val mintedOnAllScope: Boolean = false,
)

/** A replacement stream for a refusal in the middle of a track. */
class MidPlayStream(
    val videoId: String,
    val url: String,
    val headers: Map<String, String>,
    val viaProxy: Boolean,
    val client: String,
)

/** A video-only rendition of an upload, for a muted loop in the cover's place. */
class VideoLoop(val url: String, val durationMs: Long, val expiresAtMs: Long)
