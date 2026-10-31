package com.music.bitchord.telegram

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** The non-exportable Keystore key that wraps everything Telegram stores. */
internal object TelegramKeystore {
    fun key(context: Context, name: String): SecretKey {
        val alias = "${context.packageName}.telegram.$name"
        val store = KeyStore.getInstance("AndroidKeyStore")
        store.load(null)
        if (!store.containsAlias(alias)) {
            val generator = KeyGenerator.getInstance("AES", "AndroidKeyStore")
            generator.init(
                KeyGenParameterSpec.Builder(
                    alias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
            generator.generateKey()
        }
        return store.getKey(alias, null) as SecretKey
    }
}

/**
 * Stores the Telegram api_id/api_hash in the app's no-backup directory, sealed
 * with a Keystore key. No backup, no plain hash, no credential in logs.
 *
 * Ported from Hexclyde/bitchord-morphe-patches (GPL-3.0-or-later).
 * The file and key provider are injected so the storage contract can be tested
 * without an Android runtime.
 */
class TelegramCredentialStore(
    private val file: File,
    private val keyProvider: () -> SecretKey,
) {
    constructor(context: Context) : this(
        File(context.noBackupFilesDir, "telegram-native/application.sealed"),
        { TelegramKeystore.key(context, "application") },
    )

    val exists: Boolean get() = file.isFile

    fun load(): TelegramApplicationCredentials =
        if (exists) TelegramCredentialCipher.open(file.readText(), keyProvider())
        else TelegramApplicationCredentials.EMPTY

    fun save(value: TelegramApplicationCredentials) {
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, "${file.name}.tmp")
        temporary.writeText(TelegramCredentialCipher.seal(value, keyProvider()))
        if (!temporary.renameTo(file)) {
            file.writeText(temporary.readText())
            temporary.delete()
        }
    }

    fun clear() {
        file.delete()
        File(file.parentFile, "${file.name}.tmp").delete()
    }
}
