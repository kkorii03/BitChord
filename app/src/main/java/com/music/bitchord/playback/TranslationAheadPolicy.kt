package com.music.bitchord.playback

/**
 * How much work the next track is worth before it starts.
 *
 * Lyrics are a cheap text lookup and are always wanted: they are what the
 * player draws the moment the track opens. Timing and translation are not —
 * they fetch audio, run measurements or call an AI service for a song the
 * listener may skip before it ever plays.
 *
 * So the heavy half runs ahead only on an unmetered connection when heavy work
 * is allowed, where it is free by the user's own definition. Low power still
 * defers it like every other background job: [allowHeavy] is false while the
 * phone saves power, and the charger is what makes it true again. Everywhere
 * else the next track gets its lyrics only, and the heavy pass is made once it
 * is actually playing (see `TranslationAhead.trackStarted`). A track AutoPlay
 * may replace is not prepared at all: its id is not the one that will be
 * current.
 */
internal object TranslationAheadPolicy {

    enum class Plan { NONE, LYRICS_ONLY, FULL }

    /**
     * @param metered true on mobile data, false on an unmetered connection,
     *   null when there is no active network at all.
     * @param allowHeavy [AppSettings.heavyWorkAllowed]: the phone is not saving
     *   power, or is plugged in and can afford the work.
     * @param autoplayUncertain the next item came from AutoPlay and may be
     *   replaced before it plays.
     */
    fun plan(
        metered: Boolean?,
        lowPower: Boolean,
        allowHeavy: Boolean,
        autoplayUncertain: Boolean,
    ): Plan = when {
        // Nothing to fetch over, or a slot that may not survive to playback.
        metered == null -> Plan.NONE
        autoplayUncertain -> Plan.NONE
        metered == true && lowPower -> Plan.NONE
        // Free enough for the whole pass: Wi-Fi (unmetered) with heavy work allowed.
        metered == false && allowHeavy -> Plan.FULL
        else -> Plan.LYRICS_ONLY
    }
}
