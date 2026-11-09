package com.music.bitchord.ko

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

/**
 * The pure half of the per-output lyric offset: which route a device is, and
 * the stored offsets by route. No Android types, so a JVM test can cover it.
 *
 * Earbuds report a latency to the player that is not always the delay the
 * listener actually hears, so the manual lyric offset belongs to the output
 * rather than to the whole app. The built-in speaker keeps the old global
 * preference ([DEFAULT_KEY]); every other output has an entry in one JSON
 * object preference, route key to milliseconds.
 */
internal object KoLyricsRouteKey {
    /** The built-in speaker, and anything unknown: the old single offset. */
    const val DEFAULT_KEY = "speaker"

    /** One JSON object: route key -> offset in milliseconds. */
    const val PREF_KEY = "lyrics_offset_by_route"

    const val MIN_OFFSET_MS = -5_000
    const val MAX_OFFSET_MS = 5_000

    /** More routes than anybody owns; the oldest entries go first past this. */
    const val MAX_ROUTES = 32

    /**
     * [kind] is the lower-case route kind (phone, wired, usb, bluetooth, hdmi,
     * other). A Bluetooth device is told apart by its address, which survives
     * a rename and tells two pairs of the same model apart; the name stands in
     * where the framework gives no address. Wired outputs report the phone's
     * own model as a name, so they share one key.
     */
    fun routeKey(kind: String, address: String?, name: String?): String {
        val tag = kind.trim().lowercase()
        return when (tag) {
            "", "phone" -> DEFAULT_KEY
            "wired" -> "wired"
            else -> {
                val id = address?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
                    ?: name?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
                    ?: "unknown"
                "$tag:$id"
            }
        }
    }

    /** "이 기기(JBL Tour Pro 3) 전용" — null for the speaker, which has no such caption. */
    fun label(key: String, kind: String, name: String?): String? {
        if (key == DEFAULT_KEY) return null
        val shown = name?.trim()?.takeIf { it.isNotEmpty() } ?: when (kind.trim().lowercase()) {
            "wired" -> "유선 이어폰"
            "usb" -> "USB"
            "hdmi" -> "HDMI"
            "bluetooth" -> "블루투스"
            else -> "외부 기기"
        }
        return "이 기기($shown) 전용"
    }

    /** The stored map; anything unreadable or out of range reads as absent. */
    fun parse(json: String?): Map<String, Int> {
        if (json.isNullOrBlank()) return emptyMap()
        val obj = runCatching { Json.parseToJsonElement(json).jsonObject }.getOrNull()
            ?: return emptyMap()
        val out = LinkedHashMap<String, Int>()
        for ((key, value) in obj) {
            if (key == DEFAULT_KEY || key.isBlank()) continue
            val ms = (value as? JsonPrimitive)?.intOrNull ?: continue
            out[key] = ms.coerceIn(MIN_OFFSET_MS, MAX_OFFSET_MS)
        }
        return out
    }

    fun encode(map: Map<String, Int>): String =
        JsonObject(map.mapValues { JsonPrimitive(it.value) }).toString()

    /** [key]'s offset, or 0 for an output never adjusted. The speaker is not in the map. */
    fun offsetFor(map: Map<String, Int>, key: String): Int = map[key] ?: 0

    /** [map] with [key] set; a re-set route moves to the newest slot and the oldest are dropped. */
    fun withOffset(map: Map<String, Int>, key: String, offsetMs: Int): Map<String, Int> {
        if (key == DEFAULT_KEY) return map
        val next = LinkedHashMap(map)
        next.remove(key)
        next[key] = offsetMs.coerceIn(MIN_OFFSET_MS, MAX_OFFSET_MS)
        while (next.size > MAX_ROUTES) next.remove(next.keys.first())
        return next
    }
}
