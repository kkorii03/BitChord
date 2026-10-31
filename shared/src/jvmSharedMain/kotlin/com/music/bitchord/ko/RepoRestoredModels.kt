package com.music.bitchord.ko

import com.music.bitchord.data.innertube.InnertubeParser
import com.music.bitchord.data.model.Song
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Korean video-radio flag that the v1.9 merge dropped from `Song`.
 *
 * A member declared by the merged model shadows this, so the real tag wins
 * once it is restored; until then a row simply is not a video-radio row.
 */
val Song.videoRadio: Boolean
    get() = false

/**
 * The uploader channel id a player response states.
 *
 * A `next`/search row can omit the owner id, and searching the uploader's
 * name can open a different channel that shares it. `videoDetails.channelId`
 * is the upload's own owner; the microformat's `externalChannelId` is the same
 * fact for responses that omit `videoDetails`.
 */
internal fun InnertubeParser.videoChannelId(player: JsonElement?): String? {
    val root = player as? JsonObject ?: return null
    val details = root["videoDetails"] as? JsonObject
    (details?.get("channelId") as? JsonPrimitive)?.contentOrNull
        ?.takeIf { it.isNotBlank() }
        ?.let { return it }
    val microformat = root["microformat"] as? JsonObject
    val renderer = microformat?.get("playerMicroformatRenderer") as? JsonObject
    return (renderer?.get("externalChannelId") as? JsonPrimitive)?.contentOrNull
        ?.takeIf { it.isNotBlank() }
}
