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
) {
    fun loadOrCreate(): ByteArray {
        if (file.isFile) {
            return runCatching { TelegramSealedBox.open(file.readText(), keyProvider()) }
                .getOrElse { replace() }
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
