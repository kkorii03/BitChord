package com.music.bitchord.data

object HangulSearch {
    private const val SYLLABLE_BASE = 0xAC00
    private const val SYLLABLE_LAST = 0xD7A3
    private const val CACHE_LIMIT = 4096

    private val CHO = charArrayOf(
        'ㄱ', 'ㄲ', 'ㄴ', 'ㄷ', 'ㄸ', 'ㄹ', 'ㅁ', 'ㅂ', 'ㅃ', 'ㅅ',
        'ㅆ', 'ㅇ', 'ㅈ', 'ㅉ', 'ㅊ', 'ㅋ', 'ㅌ', 'ㅍ', 'ㅎ',
    )

    private val JUNG = charArrayOf(
        'ㅏ', 'ㅐ', 'ㅑ', 'ㅒ', 'ㅓ', 'ㅔ', 'ㅕ', 'ㅖ', 'ㅗ', 'ㅘ', 'ㅙ',
        'ㅚ', 'ㅛ', 'ㅜ', 'ㅝ', 'ㅞ', 'ㅟ', 'ㅠ', 'ㅡ', 'ㅢ', 'ㅣ',
    )

    private val JONG = charArrayOf(
        '\u0000', 'ㄱ', 'ㄲ', 'ㄳ', 'ㄴ', 'ㄵ', 'ㄶ', 'ㄷ', 'ㄹ', 'ㄺ', 'ㄻ',
        'ㄼ', 'ㄽ', 'ㄾ', 'ㄿ', 'ㅀ', 'ㅁ', 'ㅂ', 'ㅄ', 'ㅅ', 'ㅆ', 'ㅇ',
        'ㅈ', 'ㅊ', 'ㅋ', 'ㅌ', 'ㅍ', 'ㅎ',
    )

    private val KEYBOARD: Map<Char, Char> = mapOf(
        'q' to 'ㅂ', 'w' to 'ㅈ', 'e' to 'ㄷ', 'r' to 'ㄱ', 't' to 'ㅅ',
        'y' to 'ㅛ', 'u' to 'ㅕ', 'i' to 'ㅑ', 'o' to 'ㅐ', 'p' to 'ㅔ',
        'a' to 'ㅁ', 's' to 'ㄴ', 'd' to 'ㅇ', 'f' to 'ㄹ', 'g' to 'ㅎ',
        'h' to 'ㅗ', 'j' to 'ㅓ', 'k' to 'ㅏ', 'l' to 'ㅣ',
        'z' to 'ㅋ', 'x' to 'ㅌ', 'c' to 'ㅊ', 'v' to 'ㅍ', 'b' to 'ㅠ',
        'n' to 'ㅜ', 'm' to 'ㅡ',
        'Q' to 'ㅃ', 'W' to 'ㅉ', 'E' to 'ㄸ', 'R' to 'ㄲ', 'T' to 'ㅆ',
        'O' to 'ㅒ', 'P' to 'ㅖ',
    )

    data class Prepared(
        val text: String,
        val compact: String,
        val initials: String,
        val keyboardCompact: String,
        val keyboardInitials: String,
    )

    private val cache = LinkedHashMap<String, Prepared>(128, 0.75f, true)

    @Synchronized
    fun prepared(text: String): Prepared {
        cache[text]?.let { return it }
        val value = prepare(text)
        if (cache.size >= CACHE_LIMIT) {
            cache.keys.firstOrNull()?.let(cache::remove)
        }
        cache[text] = value
        return value
    }

    fun prepare(text: String): Prepared {
        val normalized = text.lowercase()
        val typed = fromKeyboard(text)
        return Prepared(
            text = normalized,
            compact = compact(normalized),
            initials = initialConsonants(normalized),
            keyboardCompact = compact(typed.lowercase()),
            keyboardInitials = initialConsonants(typed),
        )
    }

    fun initialConsonants(text: String): String {
        val out = StringBuilder(text.length)
        for (ch in text) {
            val code = ch.code
            when {
                code in SYLLABLE_BASE..SYLLABLE_LAST -> out.append(CHO[(code - SYLLABLE_BASE) / 588])
                ch in 'ㄱ'..'ㅎ' -> out.append(ch)
            }
        }
        return out.toString()
    }

    fun fromKeyboard(text: String): String {
        val out = StringBuilder(text.length)
        val jamo = StringBuilder()
        fun flushJamo() {
            if (jamo.isNotEmpty()) {
                out.append(compose(jamo.toString()))
                jamo.clear()
            }
        }
        for (ch in text) {
            val mapped = KEYBOARD[ch] ?: KEYBOARD[ch.lowercaseChar()]
            if (mapped != null) {
                jamo.append(mapped)
            } else {
                flushJamo()
                out.append(ch)
            }
        }
        flushJamo()
        return out.toString()
    }

    fun matches(query: String, text: String): Boolean = score(query, text) > 0

    fun matches(query: String, prepared: Prepared): Boolean = score(query, prepared) > 0

    fun score(query: String, text: String): Int = score(query, prepared(text))

    fun score(query: String, target: Prepared): Int {
        val q = prepared(query)
        if (q.compact.isEmpty() && q.initials.isEmpty()) return 0

        var best = 0
        if (q.compact.isNotEmpty() && target.compact.isNotEmpty()) {
            val at = target.compact.indexOf(q.compact)
            if (at >= 0) {
                var value = 1000
                if (target.compact == q.compact) value += 1000
                if (at == 0) value += 300
                if (target.text.startsWith(q.text)) value += 120
                best = maxOf(best, value)
            }
        }

        if (isInitialQuery(q.text) && q.initials.isNotEmpty() && target.initials.isNotEmpty()) {
            val at = target.initials.indexOf(q.initials)
            best = maxOf(
                best,
                if (at >= 0) 800 + if (at == 0) 120 else 0 else subsequenceScore(target.initials, q.initials),
            )
        }

        if (q.keyboardCompact.isNotEmpty() && q.keyboardCompact != q.compact && target.compact.isNotEmpty()) {
            val trimmed = trimTrailingVowelSyllable(q.keyboardCompact)
            for (typed in if (trimmed != q.keyboardCompact) arrayOf(q.keyboardCompact, trimmed) else arrayOf(q.keyboardCompact)) {
                val at = target.compact.indexOf(typed)
                if (at >= 0) best = maxOf(best, 700 + if (at == 0) 120 else 0)
            }
        }

        if (q.keyboardCompact != q.compact && isInitialQuery(q.keyboardCompact) &&
            q.keyboardInitials.isNotEmpty() && target.initials.isNotEmpty()
        ) {
            val at = target.initials.indexOf(q.keyboardInitials)
            best = maxOf(
                best,
                if (at >= 0) 650 + if (at == 0) 80 else 0
                else subsequenceScore(target.initials, q.keyboardInitials) - 50,
            )
        }

        return best
    }

    private fun compact(text: String): String = text.filterNot { it.isWhitespace() }

    private fun trimTrailingVowelSyllable(text: String): String {
        if (text.length < 2) return text
        val code = text.last().code
        if (code !in SYLLABLE_BASE..SYLLABLE_LAST) return text
        val offset = code - SYLLABLE_BASE
        if (offset / 588 != CHO.indexOf('ㅇ') || offset % 28 != 0) return text
        return text.dropLast(1)
    }

    private fun isInitialQuery(text: String): Boolean {
        var any = false
        for (ch in text) {
            if (ch.isWhitespace()) continue
            if (ch !in 'ㄱ'..'ㅎ') return false
            any = true
        }
        return any
    }

    private fun subsequenceScore(haystack: String, needle: String): Int {
        if (needle.isEmpty()) return 0
        var best = -1
        var start = 0
        while (true) {
            val first = haystack.indexOf(needle[0], start)
            if (first < 0) break
            var i = first + 1
            var j = 1
            while (i < haystack.length && j < needle.length) {
                if (haystack[i] == needle[j]) j++
                i++
            }
            if (j == needle.length) {
                val span = i - first
                if (best < 0 || span < best) best = span
                if (span == needle.length) break
            }
            start = first + 1
        }
        if (best < 0) return 0
        return (620 - (best - needle.length) * 30).coerceAtLeast(180)
    }

    private fun compose(text: String): String {
        val out = StringBuilder(text.length)
        var initial = -1
        var medial = -1
        var final = 0

        fun flush() {
            if (initial >= 0 && medial >= 0) {
                out.append((SYLLABLE_BASE + (initial * 21 + medial) * 28 + final).toChar())
            } else {
                if (initial >= 0) out.append(CHO[initial])
                if (medial >= 0) out.append(JUNG[medial])
                if (final > 0) out.append(JONG[final])
            }
            initial = -1
            medial = -1
            final = 0
        }

        for (ch in text) {
            val consonant = CHO.indexOf(ch)
            val vowel = JUNG.indexOf(ch)
            when {
                consonant >= 0 -> {
                    val tail = JONG.indexOf(ch)
                    when {
                        initial < 0 -> initial = consonant
                        medial < 0 -> {
                            flush()
                            initial = consonant
                        }
                        final == 0 -> {
                            if (tail > 0) {
                                final = tail
                            } else {
                                flush()
                                initial = consonant
                            }
                        }
                        else -> {
                            val combined = if (tail > 0) combineFinal(final, tail) else -1
                            if (combined > 0) {
                                final = combined
                            } else {
                                flush()
                                initial = consonant
                            }
                        }
                    }
                }
                vowel >= 0 -> {
                    when {
                        initial < 0 -> {
                            if (medial < 0) {
                                initial = CHO.indexOf('ㅇ')
                                medial = vowel
                            } else {
                                val combined = combineMedial(medial, vowel)
                                if (combined >= 0) {
                                    medial = combined
                                } else {
                                    flush()
                                    initial = CHO.indexOf('ㅇ')
                                    medial = vowel
                                }
                            }
                        }
                        medial < 0 -> medial = vowel
                        final == 0 -> {
                            val combined = combineMedial(medial, vowel)
                            if (combined >= 0) {
                                medial = combined
                            } else {
                                flush()
                                initial = CHO.indexOf('ㅇ')
                                medial = vowel
                            }
                        }
                        else -> {
                            val split = splitFinal(final)
                            val moved = if (split != null) JONG[split.second] else JONG[final]
                            if (split != null) final = split.first else final = 0
                            flush()
                            val nextInitial = CHO.indexOf(moved)
                            if (nextInitial >= 0) {
                                initial = nextInitial
                                medial = vowel
                            } else {
                                out.append(moved)
                                initial = CHO.indexOf('ㅇ')
                                medial = vowel
                            }
                        }
                    }
                }
                else -> {
                    flush()
                    out.append(ch)
                }
            }
        }
        flush()
        return out.toString()
    }

    private fun combineMedial(first: Int, second: Int): Int {
        val a = JUNG[first]
        val b = JUNG[second]
        return when {
            a == 'ㅗ' && b == 'ㅏ' -> 9
            a == 'ㅗ' && b == 'ㅐ' -> 10
            a == 'ㅗ' && b == 'ㅣ' -> 11
            a == 'ㅜ' && b == 'ㅓ' -> 14
            a == 'ㅜ' && b == 'ㅔ' -> 15
            a == 'ㅜ' && b == 'ㅣ' -> 16
            a == 'ㅡ' && b == 'ㅣ' -> 19
            else -> -1
        }
    }

    private fun combineFinal(first: Int, second: Int): Int = when {
        first == 1 && second == 19 -> 3
        first == 4 && second == 22 -> 5
        first == 4 && second == 27 -> 6
        first == 8 && second == 1 -> 9
        first == 8 && second == 16 -> 10
        first == 8 && second == 17 -> 11
        first == 8 && second == 19 -> 12
        first == 8 && second == 25 -> 13
        first == 8 && second == 26 -> 14
        first == 8 && second == 27 -> 15
        first == 17 && second == 19 -> 18
        else -> -1
    }

    private fun splitFinal(final: Int): Pair<Int, Int>? = when (final) {
        3 -> 1 to 19
        5 -> 4 to 22
        6 -> 4 to 27
        9 -> 8 to 1
        10 -> 8 to 16
        11 -> 8 to 17
        12 -> 8 to 19
        13 -> 8 to 25
        14 -> 8 to 26
        15 -> 8 to 27
        18 -> 17 to 19
        else -> null
    }
}
