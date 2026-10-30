package com.music.bitchord.data.stats

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A backup is a document the listener picked, so its size is not something
 * this app wrote and can rely on. Reading it whole would let a large file take
 * the process down before [Backup.decode] ever gets a chance to refuse it.
 */
class BackupImportBoundsTest {

    @Test
    fun `an oversized backup is refused instead of being read whole`() {
        val oversized = ByteArrayInputStream(
            ByteArray(Backup.MAX_IMPORT_BYTES + 1) { 'x'.code.toByte() },
        )

        val refused = runCatching {
            with(Backup) { oversized.readTextBounded() }
        }

        assertTrue(refused.isFailure)
        assertTrue(refused.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun `a backup exactly at the cap is still read`() {
        val text = "x".repeat(Backup.MAX_IMPORT_BYTES)
        val input = ByteArrayInputStream(text.toByteArray())

        assertEquals(text, with(Backup) { input.readTextBounded() })
    }
}
