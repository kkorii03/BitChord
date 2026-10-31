package com.music.bitchord


/** A full first screen of a native YouTube Music radio before AutoPlay tops it up. */
/** How much of an artist page's header scrolls away before the top fade begins. */
internal const val TOP_FADE_START = 0.55f

/** How much of the header's height the fade takes to reach full strength. */
internal const val TOP_FADE_RAMP = 0.3f

internal const val INITIAL_RADIO_TRACKS = 24

/**
 * What a tab's key is prefixed with in the content switcher above.
 *
 * The index is read back off it there rather than off `selectedTab`, so the
 * prefix has to be the one thing both the writing and the reading agree on.
 */
internal const val TAB_KEY = "tab:"
