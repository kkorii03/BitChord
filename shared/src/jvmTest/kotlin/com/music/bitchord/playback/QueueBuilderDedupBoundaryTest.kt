package com.music.bitchord.playback

import com.music.bitchord.data.model.Song
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class QueueBuilderDedupBoundaryTest {

    private fun song(
        id: String,
        title: String,
        artist: String = "Artist",
        duration: String? = null,
        video: Boolean = false,
    ) = Song(
        videoId = id,
        title = title,
        artist = artist,
        thumbnailUrl = null,
        durationText = duration,
        isVideo = video,
    )

    private fun ids(songs: List<Song>) = songs.map { it.videoId }

    @Test
    fun normalisedTitlesFoldCasingNoiseAndTheVideoTail() {
        assertEquals("kesariya", QueueBuilder.normalisedTitle("Kesariya"))
        assertEquals("kesariya", QueueBuilder.normalisedTitle("KESARIYA"))
        assertEquals("kesariya", QueueBuilder.normalisedTitle("Kesariya (Official Video)"))
        assertEquals("kesariya", QueueBuilder.normalisedTitle("Kesariya [Lyrics]"))
        assertEquals(
            "kesariya",
            QueueBuilder.normalisedTitle("Kesariya (From \"Brahmastra\") | Official Video"),
        )
        assertEquals("a", QueueBuilder.normalisedTitle("A | B"))
        assertEquals("", QueueBuilder.normalisedTitle("   "))
    }

    @Test
    fun normalisedTitlesKeepMarkersThatNameADifferentRecording() {
        assertEquals("kesariya remix", QueueBuilder.normalisedTitle("Kesariya (Remix)"))
        assertEquals("kesariya live", QueueBuilder.normalisedTitle("Kesariya (Live)"))
        assertEquals("song topic", QueueBuilder.normalisedTitle("Song - Topic"))
    }

    @Test
    fun punctuationBecomesASingleSpace() {
        assertEquals("don t stop me now", QueueBuilder.normalisedTitle("Don't Stop Me Now"))
        assertEquals("ac dc", QueueBuilder.normalisedTitle("AC/DC"))
        assertEquals("song", QueueBuilder.normalisedTitle("  Song!!!  "))
    }

    @Test
    fun baseTitlesStripBracketsAndDashTails() {
        assertEquals("helena", QueueBuilder.baseTitle("Helena (Live for BBC Radio 1)"))
        assertEquals("helena", QueueBuilder.baseTitle("Helena - Live"))
        assertEquals("helena", QueueBuilder.baseTitle("Helena [Remastered]"))
        assertEquals("", QueueBuilder.baseTitle(""))
    }

    @Test
    fun artistCreditsSplitOnEverySeparatorAndFoldCase() {
        assertEquals(setOf("pritam", "arijit singh"), QueueBuilder.artistSet("Pritam, Arijit Singh"))
        assertEquals(setOf("pritam", "arijit singh"), QueueBuilder.artistSet("Pritam & Arijit Singh"))
        assertEquals(setOf("pritam", "arijit singh"), QueueBuilder.artistSet("Pritam feat. Arijit Singh"))
        assertEquals(setOf("pritam", "arijit singh"), QueueBuilder.artistSet("Pritam ft. Arijit Singh"))
        assertEquals(setOf("pritam", "arijit singh"), QueueBuilder.artistSet("Pritam x Arijit Singh"))
        assertEquals(setOf("pritam", "arijit singh"), QueueBuilder.artistSet("Pritam with Arijit Singh"))
        assertEquals(setOf("a", "b", "c", "d"), QueueBuilder.artistSet("A·B•C;D"))
    }

    @Test
    fun topicChannelsAndParenthesisedAliasesBothAnswer() {
        assertEquals(setOf("arijit singh"), QueueBuilder.artistSet("Arijit Singh - Topic"))
        val aliases = QueueBuilder.artistSet("My Chemical Romance(마이 케미컬 로맨스)")
        assertTrue("my chemical romance" in aliases)
        assertTrue("마이 케미컬 로맨스" in aliases)
    }

    @Test
    fun aBlankCreditHasNoArtists() {
        assertEquals(emptySet(), QueueBuilder.artistSet(""))
        assertEquals(emptySet(), QueueBuilder.artistSet("   "))
        assertEquals(emptySet(), QueueBuilder.artistSet(" , & ; "))
    }

    @Test
    fun theSameVideoIdIsAlwaysTheSameRecording() {
        assertTrue(
            QueueBuilder.isSameRecording(
                song("same", "One"),
                song("same", "Completely Different"),
            ),
        )
    }

    @Test
    fun aDifferentIdWithTheSameTitleAndArtistIsOneRecording() {
        assertTrue(
            QueueBuilder.isSameRecording(
                song("a", "Kesariya", "Arijit Singh"),
                song("b", "Kesariya", "Arijit Singh"),
            ),
        )
    }

    @Test
    fun aDifferentIdWithTheSameTitleAndNoArtistsIsStillDeduplicated() {
        assertTrue(
            QueueBuilder.isSameRecording(
                song("a", "Untitled", ""),
                song("b", "Untitled", ""),
            ),
        )
    }

    @Test
    fun aDifferentIdWithOneBlankCreditIsStillDeduplicated() {
        assertTrue(
            QueueBuilder.isSameRecording(
                song("a", "Untitled", ""),
                song("b", "Untitled", "Someone"),
            ),
        )
    }

    @Test
    fun twoBlankTitlesAreNotTheSameRecordingByTitleAlone() {
        assertFalse(
            QueueBuilder.isSameRecording(
                song("a", "", "Artist"),
                song("b", "", "Artist"),
            ),
        )
    }

    @Test
    fun aDifferentTitleByTheSameArtistIsNotTheSameRecording() {
        assertFalse(
            QueueBuilder.isSameRecording(
                song("a", "Perfect", "Ed Sheeran"),
                song("b", "Photograph", "Ed Sheeran"),
            ),
        )
    }

    @Test
    fun aDifferentArtistInTheSameScriptIsNotTheSameRecording() {
        assertFalse(
            QueueBuilder.isSameRecording(
                song("a", "Perfect", "Ed Sheeran"),
                song("b", "Perfect", "One Direction"),
            ),
        )
    }

    @Test
    fun aDifferentScriptNeedsTheLengthsToAgree() {
        assertTrue(
            QueueBuilder.isSameRecording(
                song("a", "My Play", "AJR", duration = "3:10"),
                song("b", "My Play", "에이제이알", duration = "3:16"),
            ),
        )
        assertFalse(
            QueueBuilder.isSameRecording(
                song("a", "My Play", "AJR", duration = "3:10"),
                song("b", "My Play", "에이제이알", duration = "3:17"),
            ),
        )
    }

    @Test
    fun aDifferentScriptWithAnUnknownLengthIsTakenAsOneRecording() {
        assertTrue(
            QueueBuilder.isSameRecording(
                song("a", "My Play", "AJR", duration = "3:10"),
                song("b", "My Play", "에이제이알"),
            ),
        )
        assertTrue(
            QueueBuilder.isSameRecording(
                song("a", "My Play", "AJR"),
                song("b", "My Play", "에이제이알"),
            ),
        )
    }

    @Test
    fun theSameScriptIgnoresLengthEntirely() {
        assertTrue(
            QueueBuilder.isSameRecording(
                song("a", "Perfect", "Ed Sheeran", duration = "2:00"),
                song("b", "Perfect", "Ed Sheeran", duration = "9:59"),
            ),
        )
    }

    @Test
    fun aLiveOrRemixTakeIsAVersionOfTheSameSong() {
        assertTrue(
            QueueBuilder.isVersionOf(
                song("a", "Helena", "My Chemical Romance"),
                song("b", "Helena (Live for BBC Radio 1)", "My Chemical Romance"),
            ),
        )
        assertTrue(
            QueueBuilder.isVersionOf(
                song("a", "Helena", "My Chemical Romance"),
                song("b", "Helena - Remix", "My Chemical Romance"),
            ),
        )
    }

    @Test
    fun aVersionStillNeedsTheSameArtistAndBaseTitle() {
        assertFalse(
            QueueBuilder.isVersionOf(
                song("a", "Helena", "My Chemical Romance"),
                song("b", "Helena (Live)", "Another Band"),
            ),
        )
        assertFalse(
            QueueBuilder.isVersionOf(
                song("a", "Helena", "My Chemical Romance"),
                song("b", "Heaven Help Us (Live)", "My Chemical Romance"),
            ),
        )
        assertFalse(
            QueueBuilder.isVersionOf(
                song("a", "", "My Chemical Romance"),
                song("b", "", "My Chemical Romance"),
            ),
        )
    }

    @Test
    fun aNonPositiveLimitAddsNothing() {
        val candidates = listOf(song("v1", "One"), song("v2", "Two"))
        assertEquals(emptyList(), QueueBuilder.extend(emptyList(), candidates, limit = 0))
        assertEquals(emptyList(), QueueBuilder.extend(emptyList(), candidates, limit = -3))
    }

    @Test
    fun noCandidatesAddsNothing() {
        assertEquals(
            emptyList(),
            QueueBuilder.extend(listOf(song("seed", "Seed")), emptyList(), limit = 10),
        )
    }

    @Test
    fun videosOnlyStandInWhenThereIsNoCatalogueCut() {
        val mixed = listOf(
            song("video", "One", video = true),
            song("audio", "Two"),
        )
        assertEquals(
            listOf("audio"),
            ids(QueueBuilder.extend(emptyList(), mixed, limit = 10)),
        )

        val videosOnly = listOf(
            song("video1", "One", video = true),
            song("video2", "Two", video = true),
        )
        assertEquals(
            listOf("video1", "video2"),
            ids(QueueBuilder.extend(emptyList(), videosOnly, limit = 10)),
        )
    }

    @Test
    fun aCandidateThatIsTheQueuedRecordingIsDropped() {
        val extra = QueueBuilder.extend(
            existing = listOf(song("seed", "Kesariya", "Arijit Singh")),
            candidates = listOf(
                song("other", "Kesariya (Official Video)", "Arijit Singh"),
                song("fresh", "Tum Hi Ho", "Arijit Singh"),
            ),
            limit = 10,
        )
        assertEquals(listOf("fresh"), ids(extra))
    }

    @Test
    fun aCandidateThatIsAVersionOfAQueuedSongIsDropped() {
        val extra = QueueBuilder.extend(
            existing = listOf(song("seed", "Helena", "My Chemical Romance")),
            candidates = listOf(
                song("live", "Helena (Live)", "My Chemical Romance"),
                song("fresh", "Heaven Help Us", "My Chemical Romance"),
            ),
            limit = 10,
        )
        assertEquals(listOf("fresh"), ids(extra))
    }

    @Test
    fun duplicatesInsideTheCandidateBatchAreAddedOnce() {
        val extra = QueueBuilder.extend(
            existing = emptyList(),
            candidates = listOf(
                song("a", "Kesariya", "Arijit Singh"),
                song("b", "Kesariya (Official Video)", "Arijit Singh"),
                song("c", "Tum Hi Ho", "Arijit Singh"),
            ),
            limit = 10,
        )
        assertEquals(listOf("a", "c"), ids(extra))
    }

    @Test
    fun theOrdinaryArtistCapIsExactlyTwo() {
        val candidates = listOf(
            song("v1", "One", "Badshah"),
            song("v2", "Two", "Badshah"),
            song("v3", "Three", "Badshah"),
        )
        assertEquals(
            listOf("v1", "v2"),
            ids(QueueBuilder.extend(listOf(song("seed", "Seed", "Someone")), candidates, limit = 10)),
        )
    }

    @Test
    fun theSeedArtistCapIsExactlyFour() {
        val candidates = (1..5).map { song("v$it", "Song $it", "Diljit Dosanjh") }
        assertEquals(
            4,
            QueueBuilder.extend(
                existing = listOf(song("seed", "Seed", "Diljit Dosanjh")),
                candidates = candidates,
                limit = 10,
            ).size,
        )
    }

    @Test
    fun anExplicitSeedArtistOverridesTheLastQueuedArtist() {
        val candidates = (1..5).map { song("v$it", "Song $it", "Seed Artist") }
        val extra = QueueBuilder.extend(
            existing = listOf(song("last", "Last", "Other Artist")),
            candidates = candidates,
            limit = 10,
            seedArtist = "Seed Artist",
        )
        assertEquals(4, extra.size)
    }

    @Test
    fun aBlankExplicitSeedArtistFallsBackToTheLastQueuedArtist() {
        val candidates = (1..5).map { song("v$it", "Song $it", "Last Artist") }
        val extra = QueueBuilder.extend(
            existing = listOf(song("last", "Last", "Last Artist")),
            candidates = candidates,
            limit = 10,
            seedArtist = "   ",
        )
        assertEquals(4, extra.size)
    }

    @Test
    fun aCandidateWithNoArtistDoesNotConsumeAnArtistSlot() {
        val candidates = listOf(
            song("v1", "One", ""),
            song("v2", "Two", ""),
            song("v3", "Three", ""),
        )
        assertEquals(
            3,
            QueueBuilder.extend(listOf(song("seed", "Seed", "Someone")), candidates, limit = 10).size,
        )
    }

    @Test
    fun theCapCountsACollaborationByOneSharedArtist() {
        val candidates = listOf(
            song("v1", "One", "Pritam, Arijit Singh"),
            song("v2", "Two", "Arijit Singh, Pritam"),
            song("v3", "Three", "Pritam & Arijit Singh"),
        )
        assertEquals(
            listOf("v1", "v2"),
            ids(QueueBuilder.extend(listOf(song("seed", "Seed", "Nucleya")), candidates, limit = 10)),
        )
    }
}
