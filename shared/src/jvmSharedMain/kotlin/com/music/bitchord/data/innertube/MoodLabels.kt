package com.music.bitchord.data.innertube

import com.music.bitchord.data.model.MoodGenre
import com.music.bitchord.data.model.MoodGenreSection
import java.util.Locale

/**
 * Tidies the Explore page's mood and genre buttons for a Korean listener.
 *
 * The account this fork is used with sits in India (for its Premium plan), so
 * YouTube Music hands back India's regional categories — Bhojpuri, Tamil,
 * Bollywood and the like — however the request's country is set, and leaves
 * a good part of the rest in English. Those regional buttons are dropped
 * here, and the English names left over are shown in Korean when the app is
 * in Korean. Only the label changes: each button still opens exactly the
 * browse request YouTube gave it.
 */
object MoodLabels {

    /** Words that mark a button as one of India's regional categories. */
    private val REGIONAL = listOf(
        "bhojpuri", "tamil", "telugu", "punjabi", "hindi", "kannada", "malayalam",
        "marathi", "gujarati", "haryanvi", "bengali", "bangla", "odia", "assamese",
        "rajasthani", "bollywood", "tollywood", "kollywood", "desi", "indian",
        "carnatic", "hindustani", "devotional", "bhakti", "ghazal", "sufi", "qawwali",
        "monsoon", "indie india", "i-pop", "ipop",
    )

    private val KO = mapOf(
        // Section headers
        "moods & moments" to "분위기 및 상황",
        "moods and moments" to "분위기 및 상황",
        "genres" to "장르",
        "for you" to "추천",
        // Moods and moments
        "chill" to "휴식",
        "commute" to "출퇴근",
        "energy boosters" to "에너지 충전",
        "energize" to "에너지 충전",
        "feel good" to "기분 전환",
        "focus" to "집중",
        "gaming" to "게임",
        "party" to "파티",
        "romance" to "로맨스",
        "sad" to "슬플 때",
        "sleep" to "잠잘 때",
        "workout" to "운동",
        "family" to "가족",
        "decades" to "연대별",
        "summer" to "여름",
        "winter" to "겨울",
        "rainy day" to "비 오는 날",
        "holiday" to "휴일",
        "christmas" to "크리스마스",
        "travel" to "여행",
        "cooking" to "요리",
        "wellness" to "웰니스",
        "study" to "공부",
        "breakup" to "이별",
        "heartbreak" to "이별",
        "happy" to "행복",
        "throwback" to "추억",
        "black lives matter" to "Black Lives Matter",
        // Genres
        "pop" to "팝",
        "k-pop" to "K-POP",
        "j-pop" to "J-POP",
        "korean" to "가요",
        "korean hip-hop" to "국내 힙합",
        "korean r&b" to "국내 R&B",
        "ballad" to "발라드",
        "ballads" to "발라드",
        "trot" to "트로트",
        "hip-hop" to "힙합",
        "hip hop" to "힙합",
        "r&b & soul" to "R&B 및 소울",
        "r&b" to "R&B",
        "soul" to "소울",
        "rock" to "록",
        "alternative" to "얼터너티브",
        "indie & alternative" to "인디 및 얼터너티브",
        "indie" to "인디",
        "metal" to "메탈",
        "dance & electronic" to "댄스 및 일렉트로닉",
        "electronic" to "일렉트로닉",
        "edm" to "EDM",
        "jazz" to "재즈",
        "blues" to "블루스",
        "classical" to "클래식",
        "country & americana" to "컨트리",
        "country" to "컨트리",
        "folk & acoustic" to "포크 및 어쿠스틱",
        "acoustic" to "어쿠스틱",
        "folk" to "포크",
        "latin" to "라틴",
        "reggae & caribbean" to "레게 및 캐리비안",
        "reggae" to "레게",
        "african" to "아프리카",
        "arabic" to "아랍 음악",
        "soundtracks & musicals" to "OST 및 뮤지컬",
        "soundtracks" to "OST",
        "musicals" to "뮤지컬",
        "anime" to "애니메이션",
        "children's music" to "어린이",
        "kids" to "어린이",
        "christian & gospel" to "CCM 및 가스펠",
        "gospel" to "가스펠",
        "instrumental" to "연주곡",
        "lo-fi" to "로파이",
        "mandopop & cantopop" to "중화권 팝",
        "c-pop" to "중화권 팝",
        "1980s" to "1980년대",
        "1990s" to "1990년대",
        "2000s" to "2000년대",
        "2010s" to "2010년대",
        "1970s" to "1970년대",
        "1960s" to "1960년대",
    )

    fun isRegional(label: String): Boolean {
        val l = label.lowercase(Locale.ROOT)
        return REGIONAL.any { word -> Regex("(^|[^a-z])${Regex.escape(word)}([^a-z]|$)").containsMatchIn(l) }
    }

    /** [label] in Korean when it is one of the English names above; as given otherwise. */
    fun korean(label: String): String = KO[label.trim().lowercase(Locale.ROOT)] ?: label

    fun tidy(sections: List<MoodGenreSection>, language: String): List<MoodGenreSection> {
        val ko = language.lowercase(Locale.ROOT).startsWith("ko")
        return sections.mapNotNull { section ->
            val items = section.items
                .filterNot { isRegional(it.title) }
                .map { if (ko) it.copy(title = korean(it.title)) else it }
            if (items.isEmpty()) {
                null
            } else {
                MoodGenreSection(
                    title = if (ko) korean(section.title) else section.title,
                    items = items,
                )
            }
        }.filterNot { isRegional(it.title) }
    }

    /** One button, for callers that hold a single [MoodGenre]. */
    fun tidy(item: MoodGenre, language: String): MoodGenre =
        if (language.lowercase(Locale.ROOT).startsWith("ko")) item.copy(title = korean(item.title)) else item
}
