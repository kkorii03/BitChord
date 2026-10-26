package com.music.bitchord.ui.theme

import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/**
 * Apple gives Korean no extra tracking: the syllable blocks already sit on a
 * fixed rhythm, and widening them only breaks the word shape. Latin uppercase
 * labels keep their tracking, which is why the callers pass the tracking they
 * want and this drops it only for Hangul.
 */
fun containsHangul(text: String): Boolean = text.any { char ->
    char in '\uAC00'..'\uD7A3' ||
        char in '\u1100'..'\u11FF' ||
        char in '\u3130'..'\u318F'
}

/**
 * A date, a year or a count: digits and their separators, with no word to
 * track. Numbers are set at their natural rhythm too — the embossed member
 * date on the Replay card was the one line still spreading its digits apart.
 */
fun isNumericText(text: String): Boolean =
    text.any { it.isDigit() } && text.none { it.isLetter() }

/** [latin] tracking for Latin text, and none for Hangul or numbers. */
fun trackingFor(text: String, latin: TextUnit): TextUnit =
    if (containsHangul(text) || isNumericText(text)) 0.sp else latin
