package com.music.bitchord.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The one type scale every surface draws from.
 *
 * Apple Music keeps four steps in everyday use: a 34sp display title, a 22sp
 * section title, 17sp body copy and 15/13sp secondary copy. Screens used to
 * repeat those numbers as literals, which drifted (16/17, 14/15, 12/13) and
 * left Korean and Latin text on slightly different rhythms. Naming the steps
 * here makes the drift visible and keeps the shared player and the phone UI
 * on the same scale.
 */
object AppType {
    val DisplayLarge = 34.sp
    val Display = 30.sp
    val Section = 22.sp
    val Title = 20.sp
    val Body = 17.sp
    val Secondary = 15.sp
    val Caption = 13.sp
    val Micro = 11.sp
}

/**
 * The spacing rhythm the library, detail and settings screens share.
 *
 * Twenty dp is the screen gutter on iPhone; rows breathe by [ListRowVertical]
 * and keep at least [ListRowMinHeight] so a thumb target never shrinks below
 * the iOS 44pt guideline. Section gaps are a little larger than a list gap.
 */
object AppSpacing {
    val ScreenHorizontal = 20.dp
    val Section = 24.dp
    val Gutter = 16.dp
    val Tight = 8.dp
    val ListRowVertical = 12.dp
    val ListRowMinHeight = 52.dp
}

/** iOS sheets and alerts are softer than Material's 28dp default corners. */
val IosSheetShape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
val IosDialogShape = RoundedCornerShape(24.dp)
val IosPillShape = RoundedCornerShape(percent = 50)
