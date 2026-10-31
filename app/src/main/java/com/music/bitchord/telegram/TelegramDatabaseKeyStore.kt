package com.music.bitchord.telegram

import java.io.File
import java.security.SecureRandom
import javax.crypto.SecretKey

/**
 * The key TDLib encrypts its local database with. It is generated once and
 * kept sealed by Android Keystore in the same no-backup directory as the
 * session it protects, so restoring a backup cannot bring a database without
 * the key that opens it.
 *
 * Ported from Hexclyde/bitchord-morphe-patches (GPL-3.0-or-later).
 */
internal class TelegramDatabaseKeyStore(
    private val file: File,
    private val keyProvider: () -> SecretKey,
    private val sessionExists: () -> Boolean = { false },
) {
    fun loadOrCreate(): ByteArray {
        if (file.isFile) {
            return try {
                TelegramSealedBox.open(file.readText(), keyProvider())
            } catch (error: Throwable) {
                // Never rotate a key that already protects a session: replacing
                // it would make the encrypted db/files permanently unreadable.
                if (sessionExists()) {
                    throw TelegramDatabaseKeyException(
                        "The Telegram database key cannot be opened.",
                        error,
                    )
                }
                // With no session on disk there is nothing to strand, so a
                // corrupt leftover key may be replaced.
                return replace()
            }
        }
        // A session without its key can never be opened, and minting a fresh
        // key would strand it silently. Surface the loss instead of rotating.
        if (sessionExists()) {
            throw TelegramDatabaseKeyException(
                "The Telegram session exists without its database key.",
            )
        }
        return replace()
    }

    fun clear() {
        file.delete()
        File(file.parentFile, "${file.name}.tmp").delete()
    }

    private fun replace(): ByteArray {
        val key = ByteArray(32).also(SecureRandom()::nextBytes)
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, "${file.name}.tmp")
        temporary.writeText(TelegramSealedBox.seal(key, keyProvider()))
        if (!temporary.renameTo(file)) {
            file.writeText(temporary.readText())
            temporary.delete()
        }
        return key
    }
}

/** A sealed key that exists but cannot be read, so no session can be opened. */
internal class TelegramDatabaseKeyException(
    message: String,
    cause: Throwable? = null,
) : java.io.IOException(message, cause)
