package com.music.bitchord.telegram

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-GCM envelope used for Telegram secrets. The key itself always lives in
 * Android Keystore; only this envelope is written to disk.
 *
 * Ported from Hexclyde/bitchord-morphe-patches (GPL-3.0-or-later).
 * Kept free of Android storage so the envelope can be tested on a host JVM with
 * a temporary key, exactly as the upstream CredentialCipher was.
 */
internal object TelegramSealedBox {

    private const val VERSION = "v1"
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
    private const val AAD = "bitchord-telegram-v1"

    fun seal(plain: ByteArray, key: SecretKey): String {
        val iv = ByteArray(IV_BYTES).also(SecureRandom()::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(AAD.toByteArray(Charsets.UTF_8))
        val encrypted = cipher.doFinal(plain)
        return buildString {
            append(VERSION)
            append(':')
            append(Base64.getEncoder().encodeToString(iv))
            append(':')
            append(Base64.getEncoder().encodeToString(encrypted))
        }
    }

    fun open(sealed: String, key: SecretKey): ByteArray {
        val parts = sealed.trim().split(':')
        require(parts.size == 3 && parts[0] == VERSION) {
            "Unsupported Telegram secret envelope."
        }
        val iv = Base64.getDecoder().decode(parts[1])
        val encrypted = Base64.getDecoder().decode(parts[2])
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(AAD.toByteArray(Charsets.UTF_8))
        return cipher.doFinal(encrypted)
    }
}

/** Encrypts the api_id/api_hash pair; the hash never appears in plain text on disk. */
internal object TelegramCredentialCipher {

    fun seal(value: TelegramApplicationCredentials, key: SecretKey): String {
        val plain = "${value.apiId}\t${value.apiHash}".toByteArray(Charsets.UTF_8)
        return try {
            TelegramSealedBox.seal(plain, key)
        } finally {
            plain.fill(0)
        }
    }

    fun open(sealed: String, key: SecretKey): TelegramApplicationCredentials {
        val plain = TelegramSealedBox.open(sealed, key)
        return try {
            val text = plain.toString(Charsets.UTF_8)
            val tab = text.indexOf('\t')
            require(tab > 0) { "Malformed Telegram credentials." }
            TelegramApplicationCredentials.parse(text.substring(0, tab), text.substring(tab + 1))
        } finally {
            plain.fill(0)
        }
    }
}
