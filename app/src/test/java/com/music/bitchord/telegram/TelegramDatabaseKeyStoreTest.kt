package com.music.bitchord.telegram

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * A key is only minted for a session that does not exist yet. A corrupted or
 * missing key must never be silently replaced, because that would strand the
 * encrypted TDLib database and files that are still on disk.
 */
class TelegramDatabaseKeyStoreTest {

    private val directory: File = Files.createTempDirectory("telegram-db-key").toFile()
    private val file = File(directory, "database.key.sealed")
    private val database = File(directory, "db")

    @Test
    fun existingKeyIsReused() {
        val expected = key()
        file.writeText(TelegramSealedBox.seal(byteArrayOf(1, 2, 3, 4), expected))

        val loaded = TelegramDatabaseKeyStore(file, { expected }).loadOrCreate()

        assertArrayEquals(byteArrayOf(1, 2, 3, 4), loaded)
    }

    @Test
    fun corruptKeyIsNotReplacedWhenASessionExists() {
        database.mkdirs()
        file.writeText("v1:not-a-real-iv:not-a-real-body")
        val before = file.readText()
        val store = TelegramDatabaseKeyStore(file, { key() }) { true }

        try {
            store.loadOrCreate()
            fail("A corrupt key must not be replaced silently.")
        } catch (_: TelegramDatabaseKeyException) {
            // Expected: the caller reports the failure instead of rotating.
        }

        assertEquals(before, file.readText())
        assertTrue(database.exists())
    }

    @Test
    fun missingKeyDoesNotCreateANewOneWhenASessionExists() {
        database.mkdirs()
        val store = TelegramDatabaseKeyStore(file, { key() }) { true }

        try {
            store.loadOrCreate()
            fail("A session without its key must not get a fresh key.")
        } catch (_: TelegramDatabaseKeyException) {
            // Expected: surface the loss rather than write an unusable key.
        }

        assertFalse(file.exists())
        assertTrue(database.exists())
    }

    @Test
    fun corruptLeftoverKeyIsReplacedWhenThereIsNoSession() {
        file.writeText("v1:not-a-real-iv:not-a-real-body")
        val key = key()
        val store = TelegramDatabaseKeyStore(file, { key }) { false }

        val created = store.loadOrCreate()

        assertEquals(32, created.size)
        assertArrayEquals(created, TelegramDatabaseKeyStore(file, { key }).loadOrCreate())
    }

    @Test
    fun aFreshSessionGetsAKey() {
        val key = key()
        val store = TelegramDatabaseKeyStore(file, { key }) { false }

        val created = store.loadOrCreate()

        assertEquals(32, created.size)
        assertTrue(file.isFile)
        assertArrayEquals(created, TelegramDatabaseKeyStore(file, { key }).loadOrCreate())
    }

    private fun key(): SecretKey =
        KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
}
