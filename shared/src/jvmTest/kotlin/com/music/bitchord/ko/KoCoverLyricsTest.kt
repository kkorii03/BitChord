package com.music.bitchord.ko

import com.music.bitchord.data.cover.CoverTitles
import com.music.bitchord.data.lyrics.LyricsQueryVariants
import com.music.bitchord.data.lyrics.OriginalNames
import com.music.bitchord.data.model.Song
import kotlin.system.measureTimeMillis
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Phone 11-17 QskmVRqgW68 "공백 - 타비 Cover(11.23)": the search artist was only
 * the singer, the singer's and the channel's names were retried first and the
 * 12 s deadline ran out before the title alone, or the original the title
 * resolved to, was ever searched.
 */
class KoCoverLyricsTest {

    private val singers = CoverTitles.knownSingers
    private val channels = CoverTitles.designatedChannels
    private val original = CoverTitles.knownOriginal

    @AfterTest
    fun restore() {
        CoverTitles.knownSingers = singers
        CoverTitles.designatedChannels = channels
        CoverTitles.knownOriginal = original
    }

    private fun song(title: String, channel: String, isVideo: Boolean = true) = Song(
        videoId = "QskmVRqgW68",
        title = title,
        artist = channel,
        thumbnailUrl = null,
        isVideo = isVideo,
    )

    @Test
    fun theCoverIsSearchedAsItsSongWithNoArtist() {
        CoverTitles.knownOriginal = { null }
        // As the queue holds it: shown as "공백" by "타비 커버", the upload kept alongside.
        val row = CoverTitles.applyTo(song("공백 - 타비 Cover(11.23)", "아라하시 타비"))
        assertEquals("공백", row.title)
        assertEquals("타비 커버", row.artist)
        val query = CoverTitles.lyricsQuery(row)
        assertEquals("공백", query.title)
        assertEquals("타비", query.artist)
        assertFalse(query.artistKnown)
        assertTrue(query.isVideo)
    }

    @Test
    fun aRealSongWithCoverInItsTitleIsLeftAlone() {
        val query = CoverTitles.lyricsQuery(song("Take Cover", "Artist", isVideo = false))
        assertEquals("Take Cover", query.title)
        assertEquals("Artist", query.artist)
        assertTrue(query.artistKnown)
    }

    @Test
    fun withoutTheOriginalOnlyTitleOnlyVariantsAreRetried() {
        val variants = LyricsQueryVariants.names("공백", "타비", artistAliases = listOf("Arahashi Tabi"))
        val extra = variants.drop(1)
        // The old retries: the singer and the channel joined, then the title alone.
        assertTrue(extra.any { it.artist.isNotBlank() }, "$extra")
        assertTrue(extra.any { it.artist.isBlank() && it.title == variants.first().title }, "$extra")

        val retried = KoCoverLyrics.variantsToRetry(extra, artistKnown = false)
        assertTrue(retried.isNotEmpty())
        assertTrue(retried.all { it.artist.isBlank() }, "$retried")
        assertEquals(extra.filter { it.artist.isBlank() }, retried)
    }

    @Test
    fun aKnownArtistKeepsEveryVariant() {
        val extra = listOf(
            LyricsQueryVariants.Name("song", "a, b"),
            LyricsQueryVariants.Name("song", ""),
        )
        assertEquals(extra, KoCoverLyrics.variantsToRetry(extra, artistKnown = true))
    }

    @Test
    fun theResolvedOriginalIsTriedFirstOnlyWithoutAnArtist() {
        val name = OriginalNames.Name(" 공백 ", " 원곡가수 ")
        assertEquals("공백" to "원곡가수", KoCoverLyrics.resolvedFirst(artistKnown = false, resolved = name))
        assertNull(KoCoverLyrics.resolvedFirst(artistKnown = true, resolved = name))
        assertNull(KoCoverLyrics.resolvedFirst(artistKnown = false, resolved = null))
        assertNull(KoCoverLyrics.resolvedFirst(artistKnown = false, resolved = OriginalNames.Name(" ", "x")))
    }

    @Test
    fun theLogLineNamesTheQueryAndNoArtist() {
        val line = KoCoverLyrics.logLine("공백", "타비", null)
        assertTrue(line.startsWith("cover-lyrics: "), line)
        assertTrue(line.contains("title='공백'"), line)
        assertTrue(line.contains("artist=none"), line)
        assertTrue(line.contains("original=none"), line)
        val found = KoCoverLyrics.logLine("공백", "타비", OriginalNames.Name("공백", "원곡가수"))
        assertTrue(found.contains("original='공백'/'원곡가수'"), found)
        val late = KoCoverLyrics.logLine("공백", "타비", null, late = true)
        assertTrue(late.contains("original=late"), late)
    }

    // ---- COV7 (review RG6 COV6-A): the order of the retries after round 1 ----------

    /**
     * A provider that answers some names with a sheet (here only a label) and
     * remembers every name it was asked under, in order.
     */
    private class FakeProvider(private val sheets: Map<Pair<String, String>, String>) {
        val asked = mutableListOf<Pair<String, String>>()
        suspend fun ask(name: Pair<String, String>): String? {
            asked += name
            return sheets[name]
        }
    }

    /**
     * 5nxb792c-b4: a title-only search found another song of the same name. The
     * resolved original is a title-only catalogue guess, so when the swapped
     * reading (which names an act) also hits, the swapped reading must win.
     * COV6 asked the resolved original first and returned its sheet ("other").
     */
    @Test
    fun theSwappedReadingWinsOverTheResolvedOriginalWhenBothHit() = runBlocking<Unit> {
        val swapped = "Mina" to "Night Walk"
        val resolved = OriginalNames.Name("Night Walk", "Another Act")
        val provider = FakeProvider(
            mapOf(
                swapped to "swapped",
                ("Night Walk" to "Another Act") to "other",
                ("Night Walk" to "") to "title-only",
            ),
        )
        val found = KoCoverLyrics.retryWithoutArtist(
            alternatives = listOf(swapped),
            resolve = { resolved },
            variants = listOf(LyricsQueryVariants.Name("Night Walk", "")),
            retry = { provider.ask(it) },
        )
        assertEquals("swapped", found)
        assertEquals(listOf(swapped), provider.asked)
    }

    /**
     * The COV6 phone case must not regress: "공백 - 타비 Cover(11.23)" has no
     * swapped reading, so the original the title resolved to is the first name
     * asked after round 1, before the title alone, and the singer's and the
     * channel's variants are never asked.
     */
    @Test
    fun thePhoneCoverAsksItsResolvedOriginalBeforeTheTitleAlone() = runBlocking<Unit> {
        CoverTitles.knownOriginal = { null }
        val row = CoverTitles.applyTo(song("공백 - 타비 Cover(11.23)", "아라하시 타비"))
        val query = CoverTitles.lyricsQuery(row)
        assertFalse(query.artistKnown)
        assertTrue(query.alternatives.isEmpty(), "${query.alternatives}")
        // As searchRounds builds them: round 1 is the first name, the rest go through variantsToRetry.
        val names = LyricsQueryVariants.names(query.title, query.artist, artistAliases = listOf("Arahashi Tabi"))
        val extra = KoCoverLyrics.variantsToRetry(names.drop(1), query.artistKnown)
        val titleOnly = names.first().title to ""
        val original = "공백" to "원곡가수"
        val singerNamed = names.drop(1).filter { it.artist.isNotBlank() }.map { it.title to it.artist }
        assertTrue(singerNamed.isNotEmpty(), "$names")
        val sheets = mutableMapOf(original to "original", titleOnly to "title-only")
        singerNamed.forEach { sheets[it] = "singer" }

        val provider = FakeProvider(sheets)
        var logged: Pair<OriginalNames.Name?, Boolean>? = null
        val found = KoCoverLyrics.retryWithoutArtist(
            alternatives = query.alternatives,
            resolve = { delay(50); OriginalNames.Name("공백", "원곡가수") },
            variants = extra,
            retry = { provider.ask(it) },
            onResolved = { name, late -> logged = name to late },
        )
        assertEquals("original", found)
        assertEquals(listOf(original), provider.asked)
        assertEquals(OriginalNames.Name("공백", "원곡가수") to false, logged)

        // The resolved original misses: the title alone is next, still never a singer's name.
        val missing = FakeProvider(sheets - original)
        val next = KoCoverLyrics.retryWithoutArtist(
            alternatives = query.alternatives,
            resolve = { OriginalNames.Name("공백", "원곡가수") },
            variants = extra,
            retry = { missing.ask(it) },
        )
        assertEquals("title-only", next)
        assertEquals(listOf(original, titleOnly), missing.asked)
        assertTrue(missing.asked.none { it in singerNamed }, "${missing.asked}")
    }

    /**
     * A slow YouTube Music (the KR gate) answering the title search: COV6 waited
     * for it with only the 12 s lookup deadline as a cap. Now it gets at most
     * [KoCoverLyrics.RESOLVED_WAIT_MS] more, and the title-only name follows.
     */
    @Test
    fun aHangingTitleSearchIsWaitedForAtMostTwoSeconds() = runBlocking<Unit> {
        assertEquals(2_000L, KoCoverLyrics.RESOLVED_WAIT_MS)
        val provider = FakeProvider(mapOf(("공백" to "") to "title-only"))
        var logged: Pair<OriginalNames.Name?, Boolean>? = null
        var found: String? = null
        val elapsed = measureTimeMillis {
            found = KoCoverLyrics.retryWithoutArtist(
                alternatives = emptyList(),
                resolve = {
                    delay(60_000)
                    OriginalNames.Name("공백", "원곡가수")
                },
                variants = listOf(LyricsQueryVariants.Name("공백", "")),
                retry = { provider.ask(it) },
                onResolved = { name, late -> logged = name to late },
            )
        }
        assertEquals("title-only", found)
        assertEquals(listOf("공백" to ""), provider.asked)
        assertEquals(null to true, logged)
        assertTrue(elapsed >= 1_500L, "the resolved name was not waited for at all: ${elapsed}ms")
        assertTrue(elapsed < 6_000L, "the title search held the retries for ${elapsed}ms")
    }

    @Test
    fun everyNameMissingAnswersNullForTheCatalogueStep() = runBlocking<Unit> {
        val provider = FakeProvider(emptyMap())
        val found = KoCoverLyrics.retryWithoutArtist(
            alternatives = listOf("a" to "b"),
            resolve = { null },
            variants = listOf(LyricsQueryVariants.Name("b", "a"), LyricsQueryVariants.Name("b", "")),
            retry = { provider.ask(it) },
        )
        assertNull(found)
        // The singer-named variant is filtered out even when handed in unfiltered.
        assertEquals(listOf("a" to "b", "b" to ""), provider.asked)
    }
}
