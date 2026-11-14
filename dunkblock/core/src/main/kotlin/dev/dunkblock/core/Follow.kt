package dev.dunkblock.core

import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Which way to push the joystick so that my player closes in on my matchup (HANDOFF 0-4v). A pure decision on
 * [MatchupPicker.Pick]; whether and how the push reaches the game is the app's business.
 *
 * Rules agreed with the user (11-12): only the matchup the game itself marks, nothing when it is not found, never
 * outside defence. So: no target or no own position -> the picker's last place for it stands in for [coastS] (tags and
 * the ring drop out for a scan or a few), then let go ([holdS] is the older, shorter form of the same, kept for coastS 0).
 *
 * NOT measured on the phone: [NEAR] / [FAR] are guesses in px of the 1170 x 540 picture. The only numbers behind
 * them are eye readings of 11-10 (HANDOFF section 6, "밤 재검토"): "블록 위치 부족" at 0.12-0.21 of the screen width
 * (140-245 px), timing verdicts (= within reach) at 0.07-0.11 (80-130 px), practice blocks at 0.03-0.05 (35-60 px).
 * The 1.6 for depth is the floor foreshortening used everywhere in the matchup code, and "joystick up = up on the
 * screen" is assumed. The push goes straight at the matchup's feet, not between the matchup and the hoop.
 */
class FollowRule(private val near: Float = NEAR, private val far: Float = FAR, private val holdS: Double = HOLD_S,
                 private val coastS: Double = COAST_S) {
    private var moving = false
    private var lastDeg = Float.NaN
    private var lastSeen = -9.0
    // Last place the picker gave for the target and for me, each with its own time (coasting, see [COAST_S]).
    private var tgtT = -9.0; private var tgtX = 0f; private var tgtY = 0f
    private var meT = -9.0; private var meX = 0f; private var meY = 0f

    /** Why the last [step] answered what it did: push / coast / close / hold / no target / no me / not defence. */
    var why = ""; private set
    /** Distance of the last scan that had both positions (same unit as [NEAR]); -1 before that. */
    var dist = -1f; private set

    /** Joystick direction in degrees (0 = right on the screen, 90 = up) or NaN = let the joystick go. */
    fun step(t: Double, p: MatchupPicker.Pick): Float {
        if (!p.defence) { moving = false; tgtT = -9.0; meT = -9.0; why = "not defence"; return Float.NaN }
        if (p.src > 0) { tgtX = p.x; tgtY = p.y; tgtT = t }
        if (p.myX >= 0) { meX = p.myX; meY = p.myY; meT = t }
        if (t - tgtT > coastS || t - meT > coastS) {
            if (moving && t - lastSeen <= holdS) { why = "hold"; return lastDeg }
            moving = false; why = if (t - tgtT > coastS) "no target" else "no me"; return Float.NaN
        }
        val dx = (tgtX - meX).toDouble(); val up = (meY - tgtY) * Y_SCALE      // picture y grows downwards
        dist = hypot(dx, up).toFloat()
        val fresh = tgtT == t && meT == t
        if (fresh) lastSeen = t          // the hold counts from the last scan with both found, so it never adds to the coast
        if (moving) { if (dist < near) moving = false } else if (dist > far) moving = true
        if (!moving) { why = "close"; return Float.NaN }
        lastDeg = Math.toDegrees(atan2(up, dx)).toFloat(); why = if (fresh) "push" else "coast"
        return lastDeg
    }

    companion object {
        const val NEAR = 55f
        const val FAR = 85f
        const val HOLD_S = 0.3
        /**
         * How long a target or own place the picker lost may stand in for it (s). Measured 11-13 on 13 phone matches
         * (scratchpad follow2/rep.py): the tag or ring drops out for a scan or a few (bodies, ring on another player) far
         * more often than the player really vanishes. 0.6 s (= MatchupPicker AGE) raised the scans 2-0.1 s before an
         * opponent's shot that have a direction or are already close from 44 % to 56 % on 3 held-out matches (directions
         * 41 -> 47 %); coasted directions were within 45 degrees of the next fresh scan's in 86 % (fresh pushes 96 %, the
         * old [HOLD_S] answer 85 %). The picker is not touched: no new body, only its last answer kept. 0 = the old rule.
         */
        const val COAST_S = 0.6
        const val Y_SCALE = 1.6
    }
}
