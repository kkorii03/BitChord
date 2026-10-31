package com.music.bitchord.telegram

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * The saved credential file must never contain the api hash (or the api id) in
 * plain text. Android Keystore itself cannot run on a host JVM, so the same
 * store is given a test AES key; the envelope and file paths are the real ones.
 */
class TelegramCredentialStoreTest {

    private val hash = "0123456789abcdef0123456789abcdef"
    private val credentials = TelegramApplicationCredentials.parse("1234567", hash)

    @Test
    fun savedFileContainsNoPlainCredentials() {
        val directory = Files.createTempDirectory("telegram-credentials").toFile()
        val file = File(directory, "telegram-native/application.sealed")
        val key = key()
        val store = TelegramCredentialStore(file) { key }

        store.save(credentials)

        val raw = file.readText()
        assertFalse(raw.contains(hash, ignoreCase = true))
        assertFalse(raw.contains("1234567"))
        assertEquals(credentials, store.load())
        assertTrue(store.exists)
    }

    @Test
    fun credentialsNeverPrintTheHash() {
        assertFalse(credentials.toString().contains(hash))
    }

    private fun key(): SecretKey =
        KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
}
