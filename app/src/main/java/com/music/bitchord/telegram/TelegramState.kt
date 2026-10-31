package com.music.bitchord.telegram

/** The step the Telegram login is waiting on. */
enum class TelegramAuthStage {
    CLOSED,
    STARTING,
    WAIT_CREDENTIALS,
    WAIT_PHONE,
    WAIT_CODE,
    WAIT_PASSWORD,
    READY,
    CLOSING,
    UNSUPPORTED,
}

/** A login failure, mapped to copy in the settings screen. */
enum class TelegramError {
    INVALID_CREDENTIALS,
    INVALID_PHONE,
    INVALID_CODE,
    CODE_EXPIRED,
    INVALID_PASSWORD,
    RATE_LIMITED,
    UNSUPPORTED,
    GENERIC,
}

data class TelegramState(
    val stage: TelegramAuthStage = TelegramAuthStage.CLOSED,
    val configured: Boolean = false,
    val clientOpen: Boolean = false,
    val accountName: String? = null,
    val error: TelegramError? = null,
)
