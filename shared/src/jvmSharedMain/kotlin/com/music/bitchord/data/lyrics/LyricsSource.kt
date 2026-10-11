package com.music.bitchord.data.lyrics

/**
 * The databases [LyricsRepository] can ask, in the order it asks them.
 *
 * Exposed in Settings because the trade-offs are real and personal: one of
 * these is geoblocked in some countries, another runs on volunteer mirrors
 * that come and go, and all of them are third-party services being reached on
 * the user's connection. Anyone who would rather not talk to a given one
 * should be able to say so.
 */
enum class LyricsSource(
    val label: String,
    val detail: String,
    /** Whether it can return per-word timings, or only whole lines. */
    val wordSynced: Boolean,
    /**
     * Kept in code but off the list: not shown in Settings or the provider
     * picker, and never asked — even by someone whose saved choices still name
     * it. For a host that has stopped answering, where deleting the provider
     * outright would only mean writing it again if it comes back.
     */
    val hidden: Boolean = false,
) {
    // Declaration order is the default priority — [AppSettings.lyricsSourceOrder]
    // and [AppSettings.lyricsSources] both fall back to [LyricsSource.entries]
    // verbatim, so this list *is* the out-of-the-box experience. Stored by
    // name rather than position, so this can be rearranged without disturbing
    // an order somebody has already chosen for themselves.
    //
    // The three Apple hosts lead, because they carry the same catalogue and
    // that catalogue is the one with the voices in it. [BINI_LYRICS] goes
    // first of the three: it is the only one that will answer to a recording
    // rather than to a name, and it is where the ISRC the others use comes
    // from. [LYRICS_PLUS] has the finest timing of the lot and the least
    // reliable hosting — behind them rather than in front, so a track does not
    // wait on a mirror that is down to be told what three other hosts already
    // had.
    //
    // [LRC_RED] is ahead of all of them: it is where [BINI_LYRICS] gets its
    // documents now, so asking it directly is the same Apple timing without
    // the middleman, and it is the one a dead mirror can't take down with it.
    LRC_RED(
        label = "lrc.red",
        detail = "녹음본 기준 Apple 타이밍, 음절 단위",
        wordSynced = true,
    ),
    BINI_LYRICS(
        label = "BiniLyrics",
        detail = "같은 Apple 타이밍을 녹음 자체에 맞춰 매칭",
        wordSynced = true,
    ),
    BETTER_LYRICS(
        label = "BetterLyrics",
        detail = "Apple Music 타이밍, 단어 단위",
        wordSynced = true,
    ),
    BETTER_LYRICS_PORTATO(
        label = "BetterLyrics Portato",
        detail = "BetterLyrics를 통한 QQ Music 노래방 타이밍",
        wordSynced = true,
    ),
    PAXSENIX(
        label = "PaxSenix",
        detail = "기존 키 없는 제공처를 통한 Apple Music 타이밍",
        wordSynced = true,
    ),
    PAXSENIX_SPOTIFY(
        label = "PaxSenix: Spotify",
        detail = "PaxSenix 대체가 있는 Spotify 가사, API 키 필요",
        wordSynced = false,
    ),
    PAXSENIX_MUSIXMATCH(
        label = "PaxSenix: Musixmatch",
        detail = "PaxSenix 대체가 있는 Musixmatch 타이밍, API 키 필요",
        wordSynced = true,
    ),
    LYRICS_PLUS(
        label = "LyricsPlus",
        detail = "커뮤니티 미러에서 음절 단위",
        wordSynced = true,
    ),
    // The Korean services: whole lines only, but the Korean catalogue the
    // Apple hosts above miss — indie, OST, the originals covers are made of.
    // Ahead of every line-only fallback below, so a Korean song gets its own
    // service's lines before KuGou or Genius guesses at a name.
    VIBE(
        label = "VIBE",
        detail = "네이버 VIBE, 한국 노래 줄 단위 싱크",
        wordSynced = false,
    ),
    BUGS(
        label = "벅스",
        detail = "한국 노래 줄 단위 싱크",
        wordSynced = false,
    ),
    SIMP_MUSIC(
        label = "SimpMusic",
        detail = "비디오 기준으로 매칭되어 다른 버전과 헷갈리지 않음",
        wordSynced = true,
        hidden = true,
    ),
    UNISON(
        label = "Unison",
        detail = "리스너가 기여해서 라이선스 없는 노래도 있음",
        wordSynced = true,
    ),
    YOUTUBE_TRANSCRIPT(
        label = "YouTube 자막",
        detail = "재생 중인 비디오에 정확히 맞춘 타임 자막",
        wordSynced = false,
    ),
    YOUTUBE_MUSIC(
        label = "YouTube Music",
        detail = "재생 중인 비디오의 가사 탭에서 가져온 일반 가사",
        wordSynced = false,
    ),
    MEGALOBIZ(
        label = "Megalobiz",
        detail = "커뮤니티 제작, 줄 단위 LRC",
        wordSynced = false,
        hidden = true,
    ),
    KUGOU(
        label = "KuGou",
        detail = "줄 단위, 영어권 외 음악에 강함",
        wordSynced = false,
    ),
    LRCLIB(
        label = "LRCLIB",
        detail = "줄 단위만, 항상 접속 가능",
        wordSynced = false,
    ),
    MUSIXMATCH(
        label = "Musixmatch",
        detail = "줄 단위, 가장 큰 가사 데이터베이스",
        wordSynced = false,
    ),
    GENIUS(
        label = "Genius",
        detail = "일반 텍스트 대체, 방대한 웹 카탈로그",
        wordSynced = false,
    ),
    /**
     * Not a source anyone picks: what Gemini heard sung in a video no source
     * had lyrics for — see [LyricsRepository.transcriber]. Hidden, so it is
     * never raced or listed.
     */
    GEMINI_TRANSCRIPT(
        label = "Gemini 받아쓰기",
        detail = "가사를 찾지 못한 비디오를 Gemini가 듣고 받아씀",
        wordSynced = false,
        hidden = true,
    ),
    ;

    companion object {
        /** Every source a listener can see and choose, in default priority. */
        val offered: List<LyricsSource> = entries.filterNot { it.hidden }

        /**
         * A saved order brought up to date with this build: hidden sources
         * dropped, and any source added since it was saved slotted in right
         * after the one it is declared behind — at the very top if nothing is
         * declared ahead of it. So a new source lands where a fresh install
         * puts it relative to its neighbours, rather than under every
         * line-only fallback where it would never get to answer.
         */
        fun ordered(saved: List<LyricsSource>): List<LyricsSource> {
            val result = saved.filter { it in offered }.distinct().toMutableList()
            offered.forEachIndexed { index, source ->
                if (source in result) return@forEachIndexed
                val before = offered.subList(0, index).lastOrNull { it in result }
                result.add(before?.let { result.indexOf(it) + 1 } ?: 0, source)
            }
            return result
        }
    }
}
