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
        // The arriving line is not faded in: each line's own dynamic fade (see
        // the strip content) has already taken the old line out, so the new one
        // slides straight up at full strength.
        slideInVertically(animationSpec = tween(duration, easing = FastOutSlowInEasing)) { height -> (height * 0.35f).toInt() }
            .togetherWith(
                fadeOut(animationSpec = tween(duration, easing = FastOutSlowInEasing)) +
                    slideOutVertically(animationSpec = tween(duration, easing = FastOutSlowInEasing)) { height -> -height }
            ).using(
                SizeTransform(clip = true, sizeAnimationSpec = { _, _ -> tween(duration, easing = FastOutSlowInEasing) })
            )
    }
}
