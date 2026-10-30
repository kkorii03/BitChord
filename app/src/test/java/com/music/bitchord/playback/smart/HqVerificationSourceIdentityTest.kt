package com.music.bitchord.playback.smart

import com.music.bitchord.data.sources.SourceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which source a current-playing verification is looked up under.
 *
 * A decoder snapshot names the stream being read, but it does not always carry
 * a source config id. Treating that as "no source" made the clock unknown even
 * though [com.music.bitchord.playback.StreamChoice] still knew which source was
 * serving the track.
 */
class HqVerificationSourceIdentityTest {

    @Test
    fun `a snapshot without a source id falls back to the recorded choice`() {
        val identity = HqVerificationStore.sourceIdentity(
            snapshotSourceConfigId = null,
            snapshotTrackId = null,
            choiceSourceConfigId = "qobuz",
            choiceTrackId = "12345",
        )

        assertEquals("qobuz", identity?.sourceConfigId)
        assertEquals("12345", identity?.trackId)
    }

    @Test
    fun `a snapshot that names its source wins over the choice`() {
        val identity = HqVerificationStore.sourceIdentity(
            snapshotSourceConfigId = "tidal",
            snapshotTrackId = "snapshot-track",
            choiceSourceConfigId = "qobuz",
            choiceTrackId = "choice-track",
        )

        assertEquals("tidal", identity?.sourceConfigId)
        assertEquals("snapshot-track", identity?.trackId)
    }

    @Test
    fun `no source in either place is still unknown`() {
        assertNull(
            HqVerificationStore.sourceIdentity(
                snapshotSourceConfigId = null,
                snapshotTrackId = null,
                choiceSourceConfigId = null,
                choiceTrackId = null,
            ),
        )
    }

    @Test
    fun `a silent snapshot may fall back to the recorded choice`() {
        assertTrue(
            HqVerificationStore.shouldFallBackToChoice(
                snapshotSourceConfigId = null,
                snapshotKind = null,
                snapshotSourceName = null,
            ),
        )
    }

    @Test
    fun `a snapshot that names YouTube never borrows a substitute choice`() {
        assertFalse(
            HqVerificationStore.shouldFallBackToChoice(
                snapshotSourceConfigId = null,
                snapshotKind = null,
                snapshotSourceName = "YouTube",
            ),
        )
        assertFalse(
            HqVerificationStore.shouldFallBackToChoice(
                snapshotSourceConfigId = null,
                snapshotKind = SourceKind.YOUTUBE,
                snapshotSourceName = "YouTube",
            ),
        )
    }

    @Test
    fun `a snapshot with a source id has spoken and does not fall back`() {
        assertFalse(
            HqVerificationStore.shouldFallBackToChoice(
                snapshotSourceConfigId = "qobuz",
                snapshotKind = SourceKind.MODULE,
                snapshotSourceName = "Qobuz",
            ),
        )
    }
}
