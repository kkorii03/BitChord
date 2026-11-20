package com.music.bitchord.ko

import com.music.bitchord.data.lyrics.LyricsQueryVariants
import com.music.bitchord.data.lyrics.OriginalNames
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.rules.Timeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Phone 11-19 oZ4Tr4bINNY "히어로 - 타비 커버" and pip-QThrNwE "개화": YouTube Music
 * resolved the titles to LUCY's English rows ("Hero", "Flowering"), the retry asked
 * only those names, every Korean catalogue missed, and the lookup ended with no
 * lyrics. The Hangul row title under the resolved artist is what Bugs and VIBE file.
 */
class KoCoverHangulTitleTest {

    @get:Rule
    val timeout: Timeout = Timeout.seconds(60)

    private class FakeProvider(private val sheets: Map<Pair<String, String>, String>) {
        val asked = mutableListOf<Pair<String, String>>()
        suspend fun ask(name: Pair<String, String>): String? {
            asked += name
            return sheets[name]
        }
    }

    private fun retry(rowTitle: String, resolved: OriginalNames.Name, provider: FakeProvider): String? =
        runBlocking {
            KoCoverLyrics.retryWithoutArtist(
                alternatives = emptyList(),
                resolve = { resolved },
                variants = listOf(LyricsQueryVariants.Name(rowTitle, "")),
                retry = { provider.ask(it) },
                rowTitle = rowTitle,
            )
        }

    @Test
    fun theHangulTitleIsAskedUnderTheResolvedArtistForBothPhoneSongs() {
        for ((row, english) in listOf("히어로" to "Hero", "개화" to "Flowering")) {
            // Only the Hangul title under LUCY is filed; the English row and the title alone miss.
            val provider = FakeProvider(mapOf((row to "lucy") to "sheet"))
            val found = retry(row, OriginalNames.Name(english, "lucy"), provider)
            assertEquals("sheet", found, "$row: ${provider.asked}")
            // A Latin resolved title: the Hangul one goes first, nothing else was needed.
            assertEquals(listOf(row to "lucy"), provider.asked)

            // Both miss: the English row is still asked, then the title alone.
            val missing = FakeProvider(emptyMap())
            assertNull(retry(row, OriginalNames.Name(english, "lucy"), missing))
            assertEquals(listOf(row to "lucy", english to "lucy", row to ""), missing.asked)
        }
    }

    @Test
    fun aJapaneseOriginalKeepsItsOwnNameFirst() {
        val provider = FakeProvider(emptyMap())
        assertNull(retry("만찬가", OriginalNames.Name("晩餐歌", "tuki."), provider))
        assertEquals(listOf("晩餐歌" to "tuki.", "만찬가" to "tuki.", "만찬가" to ""), provider.asked)
    }

    /**
     * [KO] lyricsmiss, phone 11-20 RVtVN18Kf4U "180도": YouTube Music resolved BEN's song as
     * "180 Degree (180도)". The Hangul in brackets made the row's own title look already asked,
     * so only the bracketed name was retried and every Korean catalogue missed.
     */
    @Test
    fun aBracketedHangulTitleStillAsksTheRowsOwnTitle() {
        assertEquals(
            listOf("180도" to "ben", "180 Degree (180도)" to "ben"),
            KoCoverLyrics.resolvedNames("180도", OriginalNames.Name("180 Degree (180도)", "ben")),
        )
        val provider = FakeProvider(mapOf(("180도" to "ben") to "sheet"))
        assertEquals("sheet", retry("180도", OriginalNames.Name("180 Degree (180도)", "ben"), provider))
        assertEquals(listOf("180도" to "ben"), provider.asked)
    }

    /**
     * [KO] lyricsmiss, phone 11-20 ySQCgv_uw-A "창귀": one provider held the singer-name round to
     * its 9 s budget and the resolved original was cut by the 12 s deadline. The round is cut at
     * half the deadline and what was already delivered is kept; a round that ends in time is as before.
     */
    @Test
    fun aSlowFirstRoundIsCutAtHalfTheDeadline() = runBlocking<Unit> {
        assertEquals(6_000L, KoCoverLyrics.firstRoundMs(12_000L))
        var cuts = 0
        val cut = KoCoverLyrics.cappedFirstRound(
            capMs = 50L,
            round = { kotlinx.coroutines.delay(60_000L); "late" },
            onCut = { cuts++; "delivered" },
        )
        assertEquals("delivered", cut)
        assertEquals(1, cuts)
        assertNull(KoCoverLyrics.cappedFirstRound<String>(50L, round = { null }, onCut = { cuts++; "x" }))
        assertEquals("found", KoCoverLyrics.cappedFirstRound(50L, round = { "found" }, onCut = { cuts++; "x" }))
        assertEquals(1, cuts)
        assertNull(KoCoverLyrics.cappedFirstRound(50L, round = { kotlinx.coroutines.delay(60_000L); "late" }, onCut = { null }))
        assertEquals(
            "cover-lyrics: first round cut after 6000ms (singer's name), kept=n",
            KoCoverLyrics.cutLine(6_000L, kept = "n"),
        )
    }

    /**
     * [KO] lookupfix (review of lyricsmiss): the cut also stopped the sources keyed on the video
     * (YouTube's own captions and lyrics), which later rounds never ask again, and its log said
     * `kept=n` while a plain sheet was kept as the fallback.
     */
    @Test
    fun aCutRoundOwesTheVideoSourcesItStoppedAndSaysWhatItKept() {
        assertEquals("synced", KoCoverLyrics.keptKind(synced = true, plain = true))
        assertEquals("plain", KoCoverLyrics.keptKind(synced = false, plain = true))
        assertEquals("n", KoCoverLyrics.keptKind(synced = false, plain = false))
        // Only the video-keyed sources the cut stopped are owed; one that answered (or a name source) is not.
        val owed = KoCoverLyrics.owedAfterCut(setOf("YT_TRANSCRIPT", "YT_MUSIC"), setOf("YT_TRANSCRIPT", "LRCLIB"))
        assertEquals(setOf("YT_TRANSCRIPT"), owed)
        assertEquals(emptySet<String>(), KoCoverLyrics.owedAfterCut(setOf("YT_MUSIC"), emptySet<String>()))
        assertEquals(
            "cover-lyrics: first round cut after 6000ms (singer's name), kept=plain, asked again later: YT_TRANSCRIPT",
            KoCoverLyrics.cutLine(6_000L, kept = "plain", owed = owed),
        )
    }

    /** The repository's search uses the helpers above on the cut path (no provider seam to fake a slow source). */
    @Test
    fun theSearchAsksTheOwedVideoSourcesAgain() {
        val root = java.io.File(".").canonicalFile
        val source = listOf("shared/", "").map {
            java.io.File(root, "${it}src/jvmSharedMain/kotlin/com/music/bitchord/data/lyrics/LyricsRepository.kt")
        }.first { it.exists() }.readText()
        val rounds = source.substringAfter("private suspend fun searchRounds(").substringBefore("/** Sources that look a track up by its video id")
        val onCut = rounds.substringAfter("onCut = {").substringBefore("if (first != null) return first")
        kotlin.test.assertTrue("KoCoverLyrics.owedAfterCut(sources intersect KEYED_ON_VIDEO, cutShort)" in onCut)
        kotlin.test.assertTrue("keptKind(synced = kept != null, plain = fallback != null)" in onCut)
        // Review of 0e550c937: inside a name's round the owed source was awaited after the names
        // missed, so one that hung again spent the deadline and the next names were never asked.
        val retry = rounds.substringAfter("suspend fun retry(").substringBefore("suspend fun aheadName(")
        kotlin.test.assertTrue("owed" !in retry.replace("owedReady()", ""), "a name's round must not ask the owed sources")
        kotlin.test.assertTrue("tryName(name.first, name.second, byName) ?: owedReady()" in retry)
        kotlin.test.assertTrue("owedAnswer.start {" in rounds, "the owed sources are asked once, beside the names")
        val search = source.substringAfter("private suspend fun search(").substringBefore("private suspend fun searchRounds(")
        kotlin.test.assertTrue("owedAnswer.cancel()" in search.substringAfter("} finally {"), "the search ends with no owed question left running")
        val coverPath = rounds.substringAfter("if (!artistKnown) {").substringBefore("// A cover's other reading")
        val asked = coverPath.indexOf("askOwed()")
        kotlin.test.assertTrue(asked >= 0 && asked < coverPath.lastIndexOf("return deadlineResult()"), "nothing asks them before giving up")
        kotlin.test.assertTrue("if (byName.isEmpty()) return askOwed() ?: deadlineResult()" in rounds)
        kotlin.test.assertTrue("owedAnswer.last(deadline.remainingMs())" in rounds, "the last wait stays inside the deadline")
    }

    /**
     * The owed question runs beside the names: a hung one never holds up the next name
     * ([KoCoverLyrics.OwedAnswer.ifDone] never waits), the last wait is bounded, an answer
     * that came is taken once, and cancelling leaves nothing running.
     */
    @Test
    fun aHungOwedSourceNeverHoldsUpTheNextName() = runBlocking<Unit> {
        val gate = kotlinx.coroutines.CompletableDeferred<String>()
        kotlinx.coroutines.coroutineScope {
            val hung = KoCoverLyrics.OwedAnswer<String>(this)
            hung.start { kotlinx.coroutines.awaitCancellation() }
            val answered = KoCoverLyrics.OwedAnswer<String>(this)
            answered.start { gate.await() }
            answered.start { "asked twice" } // one question only

            // Names asked one after another, each missing, the third finding the sheet: the hung
            // owed source is looked at between them and never waited for.
            val asked = mutableListOf<String>()
            val started = System.nanoTime()
            var found: String? = null
            for (name in listOf("swapped", "resolved", "title-only")) {
                hung.ifDone()?.let { found = it }
                if (found != null) break
                asked += name
                kotlinx.coroutines.delay(5L)
                if (name == "title-only") found = "sheet"
            }
            assertEquals(listOf("swapped", "resolved", "title-only"), asked)
            assertEquals("sheet", found)
            kotlin.test.assertTrue(System.nanoTime() - started < 5_000_000_000L)

            // The last wait is bounded by what the deadline has left; zero left waits not at all.
            val waitStarted = System.nanoTime()
            assertNull(hung.last(50L))
            assertNull(hung.last(0L))
            kotlin.test.assertTrue(System.nanoTime() - waitStarted < 5_000_000_000L)

            assertNull(answered.ifDone())
            gate.complete("owed sheet")
            assertEquals("owed sheet", answered.last(5_000L))
            assertNull(answered.ifDone(), "taken once")

            assertNull(KoCoverLyrics.OwedAnswer<String>(this).last(5_000L), "nothing owed, nothing waited for")
            hung.cancel()
            answered.cancel()
        } // returns only because the hung question was cancelled
    }

    @Test
    fun aHangulOriginalOrNoArtistAddsNothing() {
        assertEquals(
            listOf("공백" to "원곡가수"),
            KoCoverLyrics.resolvedNames("공백", OriginalNames.Name("공백", "원곡가수")),
        )
        assertEquals(
            listOf("Hero" to ""),
            KoCoverLyrics.resolvedNames("히어로", OriginalNames.Name("Hero", " ")),
        )
        assertEquals(
            listOf("Hero" to "lucy"),
            KoCoverLyrics.resolvedNames("Hero", OriginalNames.Name("Hero", "lucy")),
        )
        assertEquals(listOf("Hero" to "lucy"), KoCoverLyrics.resolvedNames("", OriginalNames.Name("Hero", "lucy")))
        assertEquals(emptyList<Pair<String, String>>(),KoCoverLyrics.resolvedNames("히어로", null))
    }
}
