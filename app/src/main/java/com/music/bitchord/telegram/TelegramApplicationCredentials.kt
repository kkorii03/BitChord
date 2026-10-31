package com.music.bitchord.telegram

import java.util.Locale

/**
 * The Telegram application credentials a user obtains from my.telegram.org.
 *
 * Ported from Hexclyde/bitchord-morphe-patches (GPL-3.0-or-later), which is
 * itself derived from BitChord's Telegram extension. The upstream class never
 * exposes the hash through toString, and neither does this one: diagnostics and
 * crash reports must never carry it.
 */
data class TelegramApplicationCredentials(
    val apiId: Int,
    val apiHash: String,
) {
    val configured: Boolean get() = apiId > 0 && apiHash.isNotEmpty()

    override fun toString(): String = "TelegramApplicationCredentials(apiId=$apiId, hash=hidden)"

    companion object {
        val EMPTY = TelegramApplicationCredentials(0, "")

        fun parse(id: String?, hash: String?): TelegramApplicationCredentials {
            val number = id?.trim().orEmpty()
            val secret = hash?.trim().orEmpty()
            require(number.matches(Regex("[0-9]+"))) { "Enter the numeric App API ID." }
            val value = number.toIntOrNull()
                ?: throw IllegalArgumentException("The App API ID is outside the supported range.")
            require(value > 0) { "Enter an App API ID greater than zero." }
            require(secret.matches(Regex("[a-fA-F0-9]{32}"))) {
                "App API hash must contain exactly 32 hexadecimal characters."
            }
            return TelegramApplicationCredentials(value, secret.lowercase(Locale.ROOT))
        }
    }
}
