package com.music.bitchord.ko.ui.player

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith

internal fun <S> AnimatedContentTransitionScope<S>.koCurrentLyricTransition(calm: Boolean): ContentTransform {
    val duration = if (calm) 0 else 340
    return if (calm) {
        (fadeIn(snap()) togetherWith fadeOut(snap())).using(
            SizeTransform(clip = false, sizeAnimationSpec = { _, _ -> snap() })
        )
    } else {
        // A full-height slide rather than a crossfade: the outgoing line
        // leaves through the top while the incoming one comes up from the
        // bottom, so the two never sit on the same baseline and read as one
        // garbled line mid-transition.
        (fadeIn(animationSpec = tween(duration, easing = FastOutSlowInEasing)) +
            slideInVertically(animationSpec = tween(duration, easing = FastOutSlowInEasing)) { height -> height })
            .togetherWith(
                fadeOut(animationSpec = tween(duration, easing = FastOutSlowInEasing)) +
                    slideOutVertically(animationSpec = tween(duration, easing = FastOutSlowInEasing)) { height -> -height }
            ).using(
                SizeTransform(clip = true, sizeAnimationSpec = { _, _ -> tween(duration, easing = FastOutSlowInEasing) })
            )
    }
}
