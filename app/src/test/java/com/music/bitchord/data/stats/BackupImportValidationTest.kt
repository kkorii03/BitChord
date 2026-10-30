package com.music.bitchord.data.stats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

/**
 * A backup the user picks is untrusted input. It has to be refused before the
 * first write if it is too large, from an unknown schema, or names a month that
 * cannot be restored.
 */
class BackupImportValidationTest {

    private fun backup(vararg buckets: StoredBucket) = Backup.BackupFile(
        app = "bitchord",
        version = 2,
        listening = buckets.toList(),
    )

    @Test
    fun `an oversized backup is refused instead of being read whole`() {
        val oversized = ByteArrayInputStream(
            ByteArray(Backup.MAX_IMPORT_BYTES + 1) { 'x'.code.toByte() },
        )

        val refused = runCatching { with(Backup) { oversized.readTextBounded() } }

        assertTrue(refused.isFailure)
        assertTrue(refused.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun `a backup exactly at the cap is still read`() {
        val text = "x".repeat(Backup.MAX_IMPORT_BYTES)
        val input = ByteArrayInputStream(text.toByteArray())

        assertEquals(text, with(Backup) { input.readTextBounded() })
    }

    @Test(expected = IllegalArgumentException::class)
    fun `an unknown older schema is refused before any write`() {
        Backup.validate(Backup.BackupFile(app = "bitchord", version = 0))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a listening bucket whose month cannot be parsed is refused`() {
        Backup.validate(backup(StoredBucket(month = "2026-13")))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `two listening buckets for the same month are refused rather than silently merged`() {
        Backup.validate(
            backup(
                StoredBucket(month = "2026-10"),
                StoredBucket(month = "2026-10"),
            ),
        )
    }

    @Test
    fun `a valid listening month still validates`() {
        Backup.validate(backup(StoredBucket(month = "2026-10")))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `import preparation never silently drops a bad month`() {
        ListeningStats.prepareImport(listOf(StoredBucket(month = "not-a-month")))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `import preparation refuses duplicate months`() {
        ListeningStats.prepareImport(
            listOf(
                StoredBucket(month = "2026-10"),
                StoredBucket(month = "2026-10"),
            ),
        )
    }
}
