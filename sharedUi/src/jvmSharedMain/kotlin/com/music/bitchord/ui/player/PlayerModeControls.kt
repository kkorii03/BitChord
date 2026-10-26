package com.music.bitchord.ui.player

/** The glyph a playback-mode segment draws. */
internal enum class PlayerModeGlyph { Repeat, Shuffle, Autoplay }

/**
 * How strongly a segment shows that it is on.
 *
 * The active pill is a bright, nearly opaque capsule with a dark glyph; an
 * inactive segment keeps the translucent capsule and pale glyph. The two have
 * to be unmistakable on top of album-coloured artwork.
 */
internal enum class PlayerModeHighlight { None, Strong }

/** The state an accessibility label announces for a segment. */
internal enum class PlayerModeDescription {
    RepeatOff,
    RepeatAll,
    RepeatOne,
    ShuffleOff,
    ShuffleOn,
    AutoplayOff,
    AutoplayOn,
}

/** Everything one playback-mode segment needs to draw and announce itself. */
internal data class PlayerModeControl(
    val active: Boolean,
    val glyph: PlayerModeGlyph,
    val badge: String?,
    val description: PlayerModeDescription,
    val highlight: PlayerModeHighlight,
)

/**
 * The repeat segment: always the loop glyph, with a small "1" badge for
 * single-song repeat. Off and all-repeat are told apart by the highlight —
 * not by dropping the glyph for a bare "1", which is what made all-repeat
 * look exactly like repeat off.
 */
internal fun repeatModeControl(repeatMode: Int): PlayerModeControl {
    val active = repeatMode != RepeatModes.OFF
    return PlayerModeControl(
        active = active,
        glyph = PlayerModeGlyph.Repeat,
        badge = if (repeatMode == RepeatModes.ONE) "1" else null,
        description = when (repeatMode) {
            RepeatModes.ALL -> PlayerModeDescription.RepeatAll
            RepeatModes.ONE -> PlayerModeDescription.RepeatOne
            else -> PlayerModeDescription.RepeatOff
        },
        highlight = if (active) PlayerModeHighlight.Strong else PlayerModeHighlight.None,
    )
}

internal fun shuffleModeControl(enabled: Boolean): PlayerModeControl = PlayerModeControl(
    active = enabled,
    glyph = PlayerModeGlyph.Shuffle,
    badge = null,
    description = if (enabled) PlayerModeDescription.ShuffleOn else PlayerModeDescription.ShuffleOff,
    highlight = if (enabled) PlayerModeHighlight.Strong else PlayerModeHighlight.None,
)

internal fun autoplayModeControl(enabled: Boolean): PlayerModeControl = PlayerModeControl(
    active = enabled,
    glyph = PlayerModeGlyph.Autoplay,
    badge = null,
    description = if (enabled) PlayerModeDescription.AutoplayOn else PlayerModeDescription.AutoplayOff,
    highlight = if (enabled) PlayerModeHighlight.Strong else PlayerModeHighlight.None,
)
