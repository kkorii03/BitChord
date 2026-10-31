package com.music.bitchord.telegram

/**
 * When Telegram may join an automatic quality walk.
 *
 * Stage two left Telegram out of those walks on purpose; stage three lets it in
 * only when there is something to ask — the listener is signed in, at least one
 * chat is selected, and the source itself is switched on. Everything else keeps
 * the stage-two behaviour, so an automatic upgrade never opens TDLib for an
 * account that has not chosen where to search.
 */
object TelegramUpgradeGate {

    /** The verdict for an account that is not signed in, as the `hq-search` line spells it. */
    const val SIGNED_OUT = "signed-out"

    /**
     * Why Telegram may not be asked, or null when it may.
     *
     * [enabled] is the source switch. [stage] is [TelegramClientManager.state]'s
     * authorization step, and [selectedChats] is how many chats search may look
     * in. The order matters only for the reason reported: a switched-off source
     * is quiet, while a source left on without a session says `signed-out` once.
     */
    fun ineligibleReason(
        stage: TelegramAuthStage,
        selectedChats: Int,
        enabled: Boolean,
    ): String? = when {
        !enabled -> DISABLED
        // Before a login there is no chat to select. Once there is one, CLOSED
        // and STARTING mean only that the client is resting or reopening —
        // stage one keeps the session on disk and opens it for the next
        // request — so the account is still signed in.
        selectedChats <= 0 -> if (stage == TelegramAuthStage.READY) NO_CHATS else SIGNED_OUT
        stage == TelegramAuthStage.READY ||
            stage == TelegramAuthStage.STARTING ||
            stage == TelegramAuthStage.CLOSED -> null
        else -> SIGNED_OUT
    }

    fun isEligible(
        stage: TelegramAuthStage,
        selectedChats: Int,
        enabled: Boolean,
    ): Boolean = ineligibleReason(stage, selectedChats, enabled) == null

    /** Internal reasons; only [SIGNED_OUT] is written to the hq-search log. */
    const val DISABLED = "disabled"
    const val NO_CHATS = "no-chats"
}
