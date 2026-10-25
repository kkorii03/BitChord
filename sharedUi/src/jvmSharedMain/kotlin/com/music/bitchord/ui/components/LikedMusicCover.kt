package com.music.bitchord.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * The cover Apple Music gives Liked Music: a star on a pink-to-red gradient,
 * rather than YouTube's thumb-up thumbnail.
 *
 * Drawn wherever the liked collection has no sleeve of its own — the Library
 * card and its page header — so the same collection looks the same in both.
 */
@Composable
fun LikedMusicCover(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.background(
            Brush.linearGradient(
                listOf(
                    Color(0xFFFF6B9D),
                    Color(0xFFF21D4F),
                    Color(0xFFD4002A),
                ),
            ),
        ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Rounded.Star,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.94f),
            modifier = Modifier.fillMaxSize(0.38f),
        )
    }
}
