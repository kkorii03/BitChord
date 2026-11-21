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
 * phone saves power, and turning power saving off is what makes it true again.
 * Everywhere else the next track gets its lyrics only, and the heavy pass is
 * made once it is actually playing (see `TranslationAhead.trackStarted`).
 *
 * A track AutoPlay added gets only its translation ahead, and only where the
 * whole pass would be allowed. It used to get nothing, on the grounds that
 * AutoPlay may replace it — but the user's 11-07/08 log had all 21 natural
 * transitions land on an AutoPlay row, each the very row the next-song bundle
 * had prepared, so the translation was made at every song start instead of
 * before it. Timing stays with [TranslationAhead.trackStarted] for those rows
 * (the next-song bundle already times them); only the text request moves.
 */
internal object TranslationAheadPolicy {

    enum class Plan { NONE, LYRICS_ONLY, TRANSLATION_ONLY, FULL }

    /**
     * @param metered true on mobile data, false on an unmetered connection,
     *   null when there is no active network at all.
     * @param allowHeavy [AppSettings.heavyWorkAllowed]: the phone is not saving
     *   power.
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
        autoplayUncertain -> if (metered == false && allowHeavy) Plan.TRANSLATION_ONLY else Plan.NONE
        metered == true && lowPower -> Plan.NONE
        // Free enough for the whole pass: Wi-Fi (unmetered) with heavy work allowed.
        metered == false && allowHeavy -> Plan.FULL
        else -> Plan.LYRICS_ONLY
    }

    /**
     * [KO] l1121-prepai: why a lookup made for a row that is not playing yet must not have
     * Gemini write its words down (the row's audio pulled, decoded and uploaded), or null when
     * it may. Only on an unmetered connection, with heavy work allowed, once the current track
     * passed the 30 s AI gate the paid sync engines wait for; otherwise the row's own start
     * transcribes it, as a row that never plays costs nothing.
     */
    fun transcribeAheadHeld(heavyAllowed: Boolean, metered: Boolean?, playedMs: Long): String? = when {
        !heavyAllowed -> "low-power"
        metered == null -> "offline"
        metered == true -> "metered"
        !NextPrepPolicy.aiGateOpen(playedMs) -> "ai-too-early"
        else -> null
    }

    /**
     * [KO] l1121-prepai: the providers first, without a transcription; Gemini only when they all
     * missed, [transcribeWanted] and [paidGate] opens. A found sheet never waits on the gate.
     */
    suspend fun <T : Any> lookUpThenTranscribe(
        transcribeWanted: Boolean,
        paidGate: suspend () -> Boolean,
        lookUp: suspend (transcribe: Boolean) -> T?,
    ): T? = lookUp(false) ?: if (transcribeWanted && paidGate()) lookUp(true) else null
}
