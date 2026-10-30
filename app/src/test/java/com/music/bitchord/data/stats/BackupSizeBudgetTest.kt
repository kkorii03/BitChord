package com.music.bitchord.data.stats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.time.YearMonth

/**
 * A backup this app writes has to be a backup it can read back. The import
 * guard used to be settings-sized while the history kept years of per-day rows,
 * so a long-time listener could export a file that was then refused.
 */
class BackupSizeBudgetTest {

    @Test
    fun `a full-size history fits the shared export and import limit`() {
        val first = YearMonth.of(2023, 1)
        val buckets = (0 until 36).map { index ->
            // Every bucket carries its full per-day map: a real 36-month
            // history fills all of them, and only the first bucket was tested
            // before, understating the largest legitimate export.
            bucket(first.plusMonths(index.toLong()), withDays = true)
        }
        val file = Backup.BackupFile(app = "bitchord", version = 2, listening = buckets)

        val bytes = Backup.encode(file).toByteArray()

        assertTrue(
            "full export was ${bytes.size} bytes, cap is ${Backup.MAX_BACKUP_BYTES}",
            bytes.size <= Backup.MAX_BACKUP_BYTES,
        )
        val restored = with(Backup) {
            Backup.decode(ByteArrayInputStream(bytes).readTextBounded())
        }
        assertEquals(36, restored.listening.size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a file past the shared cap is refused before decoding`() {
        val tooBig = ByteArray(17) { 'x'.code.toByte() }
        with(Backup) { ByteArrayInputStream(tooBig).readTextBounded(maxBytes = 16) }
    }

    private fun bucket(month: YearMonth, withDays: Boolean): StoredBucket {
        val dayMap = if (withDays) {
            (1..month.lengthOfMonth()).associate { day ->
                "%04d-%02d-%02d".format(month.year, month.monthValue, day) to
                    DayStats(ms = 1_000L, plays = 1)
            }
        } else {
            emptyMap()
        }
        // Every row in the bucket names the same day map: the serialized shape
        // is identical to giving each row its own copy, without spending the
        // test JVM's heap on fifty thousand duplicated maps.
        val days = dayMap.toMutableMap()
        val bucketDays = if (withDays) {
            dayMap.mapValues { DayStats(ms = 1_000L, plays = 1) }
        } else {
            null
        }
        return StoredBucket(
            month = month.toString(),
            tracks = (0 until 600).map { index ->
                TrackEntry(
                    id = "track$index",
                    title = "Track title number $index with a realistic length",
                    artist = "An artist name $index",
                    album = "An album name $index",
                    albumId = "album$index",
                    artistId = "artist$index",
                    art = "https://example.test/art/$index.jpg",
                    durationText = "3:45",
                    ms = 225_000L + index,
                    plays = 2,
                    last = 1_700_000_000_000L + index,
                    days = days,
                )
            },
            artists = (0 until 400).map { index ->
                NameEntry(
                    name = "An artist name $index",
                    sub = "Artist",
                    art = "https://example.test/art/artist$index.jpg",
                    id = "artist$index",
                    ms = 225_000L + index,
                    plays = 2,
                    key = "artist$index",
                    days = days,
                )
            },
            albums = (0 until 400).map { index ->
                NameEntry(
                    name = "An album name $index",
                    sub = "An artist name $index",
                    art = "https://example.test/art/album$index.jpg",
                    id = "album$index",
                    ms = 225_000L + index,
                    plays = 2,
                    key = "album$index",
                    days = days,
                )
            },
            trackDays = bucketDays,
            artistDays = bucketDays,
            albumDays = bucketDays,
        )
    }
}
