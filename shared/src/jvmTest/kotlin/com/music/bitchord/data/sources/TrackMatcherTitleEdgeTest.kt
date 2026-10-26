package com.music.bitchord.data.sources

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Title parsing is where a listing's packaging is separated from the song's
 * identity. These cases pin the boundaries: track numbers, brackets, dashes,
 * translation pairs, trailing labels, CJK handling and artist bylines.
 */
class TrackMatcherTitleEdgeTest {

    private fun core(raw: String, artist: String = "") = TrackMatcher.parseTitle(raw, artist).core

    @Test
    fun `an empty or punctuation-only title has no identity`() {
        listOf("", "   ", "!!!", "...", " - ", "( )", "[]").forEach { raw ->
            val parts = TrackMatcher.parseTitle(raw)
            assertEquals("raw='$raw'", "", parts.core)
            assertTrue("raw='$raw'", parts.words.isEmpty())
            assertTrue("raw='$raw'", parts.versions.isEmpty())
            assertTrue("raw='$raw'", parts.cores().isEmpty())
        }
        assertTrue(TrackMatcher.queries(TrackMatcher.Target("", "")).isEmpty())
        assertTrue(TrackMatcher.queries(TrackMatcher.Target("!!!", "")).isEmpty())
        assertTrue(TrackMatcher.titleVariants("").isEmpty())
        assertTrue(TrackMatcher.titleCores("").isEmpty())
    }

    @Test
    fun `listing track numbers are removed in every printed form`() {
        listOf(
            "3. Ditto",
            "03 Ditto",
            "3) Ditto",
            "3] Ditto",
            "3- Ditto",
            "3 – Ditto",
            "3 — Ditto",
            "Track 3 - Ditto",
            "track 03. Ditto",
            "  3. Ditto",
        ).forEach { raw ->
            assertEquals(raw, "ditto", core(raw, "aespa"))
            assertTrue(raw, TrackMatcher.hasLeadingTrackNumber(raw))
        }
    }

    @Test
    fun `a number that is part of the title is not a track number`() {
        assertEquals("7rings", core("7 Rings", "Ariana Grande"))
        assertEquals("3nights", core("3 Nights", "Dominic Fike"))
        assertEquals("1000days", core("1000 Days"))
        assertFalse(TrackMatcher.hasLeadingTrackNumber("7 Rings"))
        assertFalse(TrackMatcher.hasLeadingTrackNumber("3 Nights"))
        assertFalse(TrackMatcher.hasLeadingTrackNumber("1000 Days"))
        assertFalse(TrackMatcher.hasLeadingTrackNumber("Track 1234 - Song"))
        assertFalse(TrackMatcher.hasLeadingTrackNumber("Track 3 Song"))
    }

    @Test
    fun `a zero-padded number is always a listing number`() {
        assertEquals("ditto", core("0. Ditto"))
        assertEquals("ditto", core("00 Ditto"))
        assertTrue(TrackMatcher.hasLeadingTrackNumber("0. Ditto"))
        assertTrue(TrackMatcher.hasLeadingTrackNumber("00 Ditto"))
    }

    @Test
    fun `bracketed packaging leaves the title but keeps take markers`() {
        assertEquals("song", core("Song (From \"Film\")"))
        assertEquals("song", core("Song [Official Audio]"))
        assertEquals("song", core("Song (Official Video)"))
        assertEquals("song", core("Song (Remastered 2011)"))
        assertEquals("song", core("Song (Deluxe Edition)"))
        assertEquals("song", core("Song (Live)"))
        assertEquals("song", core("Song (Live at Wembley)"))
        assertEquals("song", core("Song (Remix)"))
        assertEquals("song", core("Song (Acoustic)"))
        assertEquals("song", core("Song (Instrumental)"))
        assertEquals("song", core("Song (Karaoke)"))
        assertEquals("song", core("Song (Album Version)"))
        assertEquals("song", core("Song (Radio Edit)"))
        assertEquals("song", core("Song (Original Mix)"))
        assertEquals("song", core("Song (Single Version)"))
        assertEquals("song", core("Song (Original Version)"))
        assertEquals("song", core("Song (Explicit Version)"))
        assertEquals("song", core("Song (Deluxe Version)"))
    }

    @Test
    fun `the take marker survives the bracket`() {
        assertEquals(setOf("live"), TrackMatcher.parseTitle("Song (Live)").versions)
        assertEquals(setOf("live"), TrackMatcher.parseTitle("Song (Live at Wembley)").versions)
        assertEquals(setOf("remix"), TrackMatcher.parseTitle("Song (Remix)").versions)
        assertEquals(setOf("acoustic"), TrackMatcher.parseTitle("Song (Acoustic)").versions)
        assertEquals(setOf("instrumental"), TrackMatcher.parseTitle("Song (Instrumental)").versions)
        assertEquals(setOf("karaoke"), TrackMatcher.parseTitle("Song (Karaoke)").versions)
        assertEquals(emptySet<String>(), TrackMatcher.parseTitle("Song (Album Version)").versions)
        assertEquals(emptySet<String>(), TrackMatcher.parseTitle("Song (Radio Edit)").versions)
        assertEquals(emptySet<String>(), TrackMatcher.parseTitle("Song (Original Mix)").versions)
        assertEquals(emptySet<String>(), TrackMatcher.parseTitle("Song (Single Version)").versions)
    }

    @Test
    fun `nested brackets are unpacked innermost first`() {
        val parts = TrackMatcher.parseTitle("Song (From \"Film (Extended)\")")
        assertEquals("song", parts.core)
        assertEquals(setOf("extended"), parts.versions)
        assertTrue(parts.context.contains("film"))
    }

    @Test
    fun `an unbalanced bracket takes the rest of the line with it`() {
        assertEquals("song", core("Song (feat. Someone"))
        assertEquals(setOf("live"), TrackMatcher.parseTitle("Song [Live").versions)
        assertEquals("song", core("Song [Live"))
        assertEquals("song", core("Song (From Film"))
    }

    @Test
    fun `dash and pipe packaging is removed`() {
        assertEquals("song", core("Song | Official Video"))
        assertEquals("song", core("Song - Official Video"))
        assertEquals("song", core("Song – Official Audio"))
        assertEquals("song", core("Song — Official Audio"))
        assertEquals("paniyonsa", core("Paniyon Sa - Satyamev Jayate"))
    }

    @Test
    fun `the artist - title upload shape is inverted`() {
        assertEquals("title", core("Artist - Title", "Artist"))
        assertEquals("title", core("The Artist - Title", "The Artist"))
        // A tail that is the artist is packaging, not a second title.
        assertEquals("title", core("Title - Artist", "Artist"))
        assertEquals(emptyList<List<String>>(), TrackMatcher.parseTitle("Title - Artist", "Artist").altWords)
    }

    @Test
    fun `a Japanese title and its Latin tail are read in the right direction`() {
        // "紅蓮華 - LiSA" is title and artist, not a transliteration pair.
        val lisA = TrackMatcher.parseTitle("紅蓮華 - LiSA", "LiSA")
        assertEquals("紅蓮華", lisA.core)
        assertTrue(lisA.altWords.isEmpty())

        // A non-Latin head with a filing Latin tail is the transliteration
        // shape: the tail becomes the identity, the head the context.
        val zero = TrackMatcher.parseTitle("第ゼロ感 - Dai Zero Kan")
        assertEquals("daizerokan", zero.core)
        assertEquals(listOf("dai zero kan"), TrackMatcher.titleVariants("第ゼロ感 - Dai Zero Kan"))
    }

    @Test
    fun `a Hangul and Latin dash pair keeps both identities`() {
        val parts = TrackMatcher.parseTitle("3.여수밤바다 - Yeosu Night Sea", "장범준")
        assertEquals("여수밤바다", parts.core)
        assertTrue(parts.cores().contains("yeosunightsea"))
        assertEquals(
            listOf("여수밤바다", "yeosu night sea"),
            TrackMatcher.titleVariants("3.여수밤바다 - Yeosu Night Sea", "장범준"),
        )
        assertEquals(
            listOf("여수밤바다", "yeosunightsea"),
            TrackMatcher.titleCores("3.여수밤바다 - Yeosu Night Sea", "장범준"),
        )
        assertEquals(
            "여수밤바다",
            TrackMatcher.searchableTitle("3.여수밤바다 - Yeosu Night Sea", "장범준"),
        )
    }

    @Test
    fun `the Hangul side stays primary when the pair is reversed`() {
        val parts = TrackMatcher.parseTitle("Yeosu Night Sea - 여수밤바다", "장범준")
        assertEquals("여수밤바다", parts.core)
        assertTrue(parts.cores().contains("yeosunightsea"))
    }

    @Test
    fun `an alternate core is cleaned like the primary one`() {
        // The Latin half carries the same unbracketed packaging; its core must
        // lose the trailing label exactly as the primary core does.
        val parts = TrackMatcher.parseTitle("여수밤바다 - Yeosu Night Sea 東京 Official Video", "장범준")
        assertEquals(listOf("여수밤바다", "yeosunightsea東京"), parts.cores())
        assertFalse(parts.cores().any { it.endsWith("officialvideo") })
    }

    @Test
    fun `a featuring credit is removed wherever it is printed`() {
        assertEquals("song", core("Song feat. Artist"))
        assertEquals("song", core("Song ft. Artist"))
        assertEquals("song", core("Song ft Artist"))
        assertEquals("song", core("Song featuring Artist"))
        assertEquals("song", core("Song with Artist"))
        assertEquals("song", core("Song With Artist"))
        assertEquals("song", core("SONG WITH ARTIST"))
        assertEquals("song", core("Song (feat. Artist)"))
        assertEquals("goodday", core("Good Day (feat. 팔로알토 (Paloalto))"))
    }

    /**
     * "with" is part of the name when a pronoun, determiner or conjunction
     * follows it ("with Me", "with the Wolves", "with or Without You"), and a
     * credit when a name follows. The printed case does not decide it: real
     * titles are written lowercase and all-caps too, and credits title-case.
     */
    @Test
    fun `with in a real title is not a featuring credit`() {
        assertEquals("staywithme", core("Stay With Me"))
        assertEquals("staywithme", core("Stay with Me"))
        assertEquals("staywithme", core("STAY WITH ME"))
        assertEquals("dancewithme", core("Dance With Me"))
        assertEquals("dancewithme", core("Dance with Me"))
        assertEquals("withorwithoutyou", core("With or Without You"))
        assertEquals("withorwithoutyou", core("WITH OR WITHOUT YOU"))
        assertEquals("withyou", core("With You"))
        assertEquals("dancewiththewolves", core("Dance With the Wolves"))
    }

    @Test
    fun `trailing upload labels are removed from a Latin title`() {
        assertEquals("kizunanokiseki", core("Kizuna No Kiseki Instrumental"))
        assertEquals(setOf("instrumental"), TrackMatcher.parseTitle("Kizuna No Kiseki Instrumental").versions)
        assertEquals(setOf("karaoke"), TrackMatcher.parseTitle("Song Karaoke").versions)
        assertEquals(setOf("remix"), TrackMatcher.parseTitle("Song Remix").versions)
        assertEquals(setOf("nightcore"), TrackMatcher.parseTitle("Song Nightcore").versions)
        assertEquals(setOf("acapella"), TrackMatcher.parseTitle("Song Acapella").versions)
        assertEquals("song", core("Song Official Video"))
        assertEquals("song", core("Song Full Audio"))
    }

    @Test
    fun `a title that is only a take word keeps its name`() {
        assertEquals("instrumental", core("Instrumental"))
        assertEquals(emptySet<String>(), TrackMatcher.parseTitle("Instrumental").versions)
        assertEquals("karaoke", core("Karaoke"))
        assertEquals("live", core("Live"))
        assertEquals(emptySet<String>(), TrackMatcher.parseTitle("Live").versions)
    }

    @Test
    fun `ambiguous trailing take words stay in the title`() {
        // Reading these as takes would let the live or cover take stand in for
        // the studio record.
        assertEquals("letmelive", core("Let Me Live"))
        assertEquals("takecover", core("Take Cover"))
        assertEquals("songlive", core("Song Live"))
        assertEquals("songacoustic", core("Song Acoustic"))
        assertEquals("songcover", core("Song Cover"))
        assertEquals("thefinalchapter", core("The Final Chapter"))
        assertEquals(emptySet<String>(), TrackMatcher.parseTitle("Let Me Live").versions)
        assertEquals(emptySet<String>(), TrackMatcher.parseTitle("Take Cover").versions)
    }

    @Test
    fun `CJK titles keep their raw sequence without spaces`() {
        assertEquals("여수밤바다", core("여수 밤바다"))
        assertEquals("紅蓮華", core("紅蓮華"))
        assertEquals("コイコガレ", core("コイコガレ"))
        assertEquals("真夜中のドア", core("真夜中のドア"))
        // Bracketed packaging is still removed before the raw sequence is kept.
        assertEquals("여수밤바다", core("여수 밤바다 (Official)"))
        assertEquals("紅蓮華", core("紅蓮華 [Official Video]"))
    }

    /**
     * The core is the cleaned word list for every script, so an unbracketed
     * trailing label or take word leaves the identity and only a take marker
     * remains in versions.
     */
    @Test
    fun `CJK trailing packaging does not leak into the identity`() {
        assertEquals("紅蓮華", core("紅蓮華 Official Video"))
        assertEquals("여수밤바다", core("여수 밤바다 Official"))
        val instrumental = TrackMatcher.parseTitle("紅蓮華 Instrumental")
        assertEquals("紅蓮華", instrumental.core)
        assertEquals(setOf("instrumental"), instrumental.versions)
    }

    @Test
    fun `normalisation folds width case ampersands and CJK brackets`() {
        assertEquals("dai zero", TrackMatcher.normalize("Ｄａｉ　Ｚｅｒｏ"))
        assertEquals("jack  and  jill", TrackMatcher.normalize("Jack & Jill"))
        assertEquals("(song)", TrackMatcher.normalize("「Song」"))
        assertEquals("(song)", TrackMatcher.normalize("【Song】"))
        assertEquals("abc", TrackMatcher.normalize("ABC"))
    }

    @Test
    fun `script detection is not fooled by accented Latin`() {
        assertTrue(TrackMatcher.hasLatinLetters("abc"))
        assertTrue(TrackMatcher.hasLatinLetters("abc한글"))
        assertFalse(TrackMatcher.hasLatinLetters("한글"))
        assertFalse(TrackMatcher.hasLatinLetters(""))

        assertFalse(TrackMatcher.hasOtherScript("abc"))
        assertFalse(TrackMatcher.hasOtherScript("café"))
        assertTrue(TrackMatcher.hasOtherScript("한글"))
        assertTrue(TrackMatcher.hasOtherScript("コイ"))
        assertTrue(TrackMatcher.hasOtherScript("abc漢"))
    }

    @Test
    fun `filing Latin excludes packaging and take words`() {
        assertTrue(TrackMatcher.hasFilingLatin("Dai Zero Kan"))
        assertTrue(TrackMatcher.hasFilingLatin("第ゼロ感 Dai"))
        assertFalse(TrackMatcher.hasFilingLatin("第ゼロ感"))
        assertFalse(TrackMatcher.hasFilingLatin("instrumental"))
        assertFalse(TrackMatcher.hasFilingLatin("-instrumental-"))
        assertFalse(TrackMatcher.hasFilingLatin("official"))
        assertFalse(TrackMatcher.hasFilingLatin("the"))
        assertFalse(TrackMatcher.hasFilingLatin("and"))
        assertFalse(TrackMatcher.hasFilingLatin(""))
    }

    @Test
    fun `script mates bridge kana and romaji in either order`() {
        assertEquals(
            listOf("コイコガレ", "koikogare"),
            TrackMatcher.scriptMates("コイコガレ - koikogare"),
        )
        assertEquals(
            listOf("koikogare", "コイコガレ"),
            TrackMatcher.scriptMates("koikogare - コイコガレ"),
        )
        assertEquals(
            listOf("アブナイキオク", "abunaikioku"),
            TrackMatcher.scriptMates("アブナイキオク - abunaikioku"),
        )
        assertTrue(TrackMatcher.scriptMates("Paniyon Sa - Satyamev Jayate").isEmpty())
        assertTrue(TrackMatcher.scriptMates("Song").isEmpty())
        assertTrue(TrackMatcher.scriptMates("Song - Song").isEmpty())
    }

    @Test
    fun `romaji follows Hepburn for kana and leaves other scripts alone`() {
        assertEquals("koikogare", TrackMatcher.romajiOf("コイコガレ"))
        assertEquals("abunaikioku", TrackMatcher.romajiOf("アブナイキオク"))
        assertEquals("renai", TrackMatcher.romajiOf("レンアイ"))
        assertEquals("matchi", TrackMatcher.romajiOf("マッチ"))
        assertEquals("masshu", TrackMatcher.romajiOf("マッシュ"))
        assertEquals("shashusho", TrackMatcher.romajiOf("しゃしゅしょ"))
        assertEquals("jajujo", TrackMatcher.romajiOf("じゃじゅじょ"))
        assertEquals("chachucho", TrackMatcher.romajiOf("ちゃちゅちょ"))
        assertEquals("fafifefo", TrackMatcher.romajiOf("ふぁふぃふぇふぉ"))
        assertEquals("koohii", TrackMatcher.romajiOf("コーヒー"))
        assertEquals("kompa", TrackMatcher.romajiOf("コンパ"))
        assertEquals("tta", TrackMatcher.romajiOf("ッタ"))
        assertEquals("", TrackMatcher.romajiOf("ー"))
        assertEquals("doa", TrackMatcher.romajiOf("ドア"))
        assertEquals("紅蓮華", TrackMatcher.romajiOf("紅蓮華"))
        assertEquals("ABC123", TrackMatcher.romajiOf("ABC123"))
    }

    @Test
    fun `artist bylines split on every separator the catalogues print`() {
        listOf(
            "A, B",
            "A & B",
            "A / B",
            "A ; B",
            "A · B",
            "A | B",
            "A、B",
            "A，B",
            "A × B",
            "A and B",
            "A x B",
            "A vs B",
            "A feat B",
            "A ft B",
            "A featuring B",
            "A with B",
            "A 및 B",
        ).forEach { raw ->
            val variants = TrackMatcher.artistVariants(raw)
            assertTrue("raw='$raw' -> $variants", variants.contains("a"))
            assertTrue("raw='$raw' -> $variants", variants.contains("b"))
        }
    }

    /**
     * The dotted abbreviations consume their period, so the next name starts
     * with a letter rather than ". b".
     */
    @Test
    fun `a dotted separator leaves the next name intact`() {
        listOf("A vs. B", "A feat. B", "A ft. B").forEach { raw ->
            assertEquals(raw, listOf("a", "b"), TrackMatcher.artistVariants(raw))
        }
    }

    @Test
    fun `artist names keep bracketed aliases only when both sides are names`() {
        assertEquals(
            listOf("강현준", "lil tachi", "김호진", "hotchkiss"),
            TrackMatcher.artistVariants("강현준(Lil tachi) 및 김호진(HOTCHKISS)"),
        )
        assertEquals(listOf("f(x)"), TrackMatcher.artistVariants("f(x)"))
        assertEquals(listOf("f(x)", "f(y)"), TrackMatcher.artistVariants("f(x) & f(y)"))
        // One-letter base or alias is not split into nonsense.
        assertEquals(listOf("ab(c)"), TrackMatcher.artistVariants("AB(C)"))
        assertEquals(listOf("ab()"), TrackMatcher.artistVariants("AB()"))
        // A separator inside brackets belongs to the name.
        assertEquals(listOf("a (b, c)"), TrackMatcher.artistVariants("A (B, C)"))
        assertEquals(listOf("simon", "garfunkel"), TrackMatcher.artistVariants("Simon & Garfunkel"))
        assertEquals(listOf("ac", "dc"), TrackMatcher.artistVariants("AC/DC"))
    }

    @Test
    fun `blank artist lines produce no names`() {
        assertTrue(TrackMatcher.artistVariants("").isEmpty())
        assertTrue(TrackMatcher.artistVariants("   ").isEmpty())
        assertTrue(TrackMatcher.artistVariants(" , ; | ").isEmpty())
        assertTrue(TrackMatcher.artistNames("").isEmpty())
        assertEquals(setOf(listOf("fx")), TrackMatcher.artistNames("f(x)"))
        assertEquals("", TrackMatcher.primaryArtist(""))
        assertEquals("ph-1", TrackMatcher.primaryArtist("pH-1, 키드밀리 및 루피 (Loopy)"))
    }

    @Test
    fun `artist names drop single letters but keep whole names`() {
        assertEquals(setOf(listOf("rahman")), TrackMatcher.artistNames("A. R. Rahman"))
        assertEquals(setOf(listOf("ar", "rahman")), TrackMatcher.artistNames("AR Rahman"))
        assertEquals(
            setOf(listOf("atif", "aslam"), listOf("tulsi", "kumar")),
            TrackMatcher.artistNames("Atif Aslam, Tulsi Kumar"),
        )
    }

    @Test
    fun `sharesArtist accepts partial credits and whole-name containment`() {
        assertTrue(TrackMatcher.sharesArtist("Atif Aslam", "Atif Aslam, Tulsi Kumar"))
        assertTrue(TrackMatcher.sharesArtist("Atif Aslam, Tulsi Kumar", "Atif Aslam"))
        assertTrue(TrackMatcher.sharesArtist("Simon", "Simon & Garfunkel"))
        assertTrue(TrackMatcher.sharesArtist("Simon & Garfunkel", "Garfunkel"))
        assertTrue(TrackMatcher.sharesArtist("A. R. Rahman", "AR Rahman"))
        // A name inside another name as text is not the same artist.
        assertFalse(TrackMatcher.sharesArtist("Queen", "Queensrÿche"))
        assertFalse(TrackMatcher.sharesArtist("", "Anyone"))
        assertFalse(TrackMatcher.sharesArtist("Anyone", ""))
        // Punctuation is removed, so "f(x)" is the comparable word "fx".
        assertTrue(TrackMatcher.sharesArtist("f(x)", "f(x)"))
        assertFalse(TrackMatcher.sharesArtist("f(x)", "Justin Bieber"))
    }

    @Test
    fun `namesTheSameArtist needs a whole exact name, not a shared word`() {
        assertTrue(TrackMatcher.namesTheSameArtist("Atif Aslam", "Atif Aslam, Tulsi Kumar"))
        assertFalse(TrackMatcher.namesTheSameArtist("Queen", "Queensrÿche"))
        assertTrue(TrackMatcher.namesTheSameArtist("f(x)", "f(x)"))
        assertFalse(TrackMatcher.namesTheSameArtist("f(x)", "Justin Bieber"))
        assertFalse(TrackMatcher.namesTheSameArtist("", ""))
        // An initialled and a plain spelling are the same person for
        // sharesArtist, but this stricter check does not guess without evidence.
        assertFalse(TrackMatcher.namesTheSameArtist("A. R. Rahman", "AR Rahman"))
    }

    @Test
    fun `queries put the primary artist first and the bare title second`() {
        assertEquals(
            listOf("next level aespa", "next level"),
            TrackMatcher.queries(TrackMatcher.Target("Next Level", "aespa")),
        )
        assertEquals(
            listOf("next level"),
            TrackMatcher.queries(TrackMatcher.Target("Next Level", "")),
        )
        assertEquals(
            listOf("song ph-1", "song"),
            TrackMatcher.queries(TrackMatcher.Target("Song", "pH-1, 키드밀리")),
        )
        assertEquals(
            listOf("shape of you acoustic"),
            TrackMatcher.queries(TrackMatcher.Target("Shape of You (Acoustic)", "")),
        )
    }

    @Test
    fun `queries add the other script once`() {
        assertEquals(
            listOf("コイコガレ", "koikogare"),
            TrackMatcher.queries(TrackMatcher.Target("コイコガレ", "")),
        )
        assertEquals(
            listOf("コイコガレ aimer", "コイコガレ", "koikogare"),
            TrackMatcher.queries(TrackMatcher.Target("コイコガレ", "Aimer")),
        )
        assertEquals(
            listOf("koikogare", "コイコガレ"),
            TrackMatcher.queries(TrackMatcher.Target("コイコガレ - koikogare", "")),
        )
        assertEquals(
            listOf("紅蓮華"),
            TrackMatcher.queries(TrackMatcher.Target("紅蓮華", "")),
        )
    }

    @Test
    fun `queries keep a translated title in both languages`() {
        assertEquals(
            listOf("여수밤바다 장범준", "여수밤바다", "yeosu night sea"),
            TrackMatcher.queries(TrackMatcher.Target("3.여수밤바다 - Yeosu Night Sea", "장범준")),
        )
    }
}
