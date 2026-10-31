package com.music.bitchord.data.sources

/**
 * Which enabled sources the Search tab's Addons page asks.
 *
 * Kept as one predicate rather than a `filterIsInstance<AddonSource>()` at the
 * call site: Telegram is not an addon, but it is a catalogue that answers the
 * same search box and must appear on the same page. Addons keep their old
 * behaviour; module sources deliberately stay off this tab.
 */
object SearchTabSources {
    fun searchable(sources: List<MusicSource>): List<MusicSource> =
        sources.filter { it.kind == SourceKind.ADDON || it.kind == SourceKind.TELEGRAM }
}
