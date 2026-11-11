package dev.dunkblock.core

import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Which way to push the joystick so that my player closes in on my matchup (HANDOFF 0-4v). A pure decision on
 * [MatchupPicker.Pick]; whether and how the push reaches the game is the app's business.
 *
 * Rules agreed with the user (11-12): only the matchup the game itself marks, nothing when it is not found, never
 * outside defence. So: no target or no own position -> let go (after [holdS], tags flicker for a scan or two).
 *
 * NOT measured on the phone: [NEAR] / [FAR] are guesses in px of the 1170 x 540 picture. The only numbers behind
 * them are eye readings of 11-10 (HANDOFF section 6, "밤 재검토"): "블록 위치 부족" at 0.12-0.21 of the screen width
 * (140-245 px), timing verdicts (= within reach) at 0.07-0.11 (80-130 px), practice blocks at 0.03-0.05 (35-60 px).
 * The 1.6 for depth is the floor foreshortening used everywhere in the matchup code, and "joystick up = up on the
 * screen" is assumed. The push goes straight at the matchup's feet, not between the matchup and the hoop.
 */
class FollowRule(private val near: Float = NEAR, private val far: Float = FAR, private val holdS: Double = HOLD_S) {
    private var moving = false
    private var lastDeg = Float.NaN
    private var lastSeen = -9.0

    /** Why the last [step] answered what it did: push / close / hold / no target / no me / not defence. */
    var why = ""; private set
    /** Distance of the last scan that had both positions (same unit as [NEAR]); -1 before that. */
    var dist = -1f; private set

    /** Joystick direction in degrees (0 = right on the screen, 90 = up) or NaN = let the joystick go. */
    fun step(t: Double, p: MatchupPicker.Pick): Float {
        if (!p.defence) { moving = false; why = "not defence"; return Float.NaN }
        if (p.src == 0 || p.myX < 0) {
            if (moving && t - lastSeen <= holdS) { why = "hold"; return lastDeg }
            moving = false; why = if (p.src == 0) "no target" else "no me"; return Float.NaN
        }
        val dx = (p.x - p.myX).toDouble(); val up = (p.myY - p.y) * Y_SCALE      // picture y grows downwards
        dist = hypot(dx, up).toFloat()
        lastSeen = t
        if (moving) { if (dist < near) moving = false } else if (dist > far) moving = true
        if (!moving) { why = "close"; return Float.NaN }
        lastDeg = Math.toDegrees(atan2(up, dx)).toFloat(); why = "push"
        return lastDeg
    }

    companion object {
        const val NEAR = 55f
        const val FAR = 85f
        const val HOLD_S = 0.3
        const val Y_SCALE = 1.6
    }
}
