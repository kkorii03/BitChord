package dev.dunkblock.core

import kotlin.math.abs
import kotlin.math.atan2

/**
 * Character-dependent block arc (information from the user, not verified in-game by this project):
 *  - most defenders block only in front (180 deg),
 *  - some defenders also block to the side (front + sides),
 *  - LeBron blocks in any direction (360 deg).
 *
 * The exact angle of "front + side" is NOT known; 270 deg (front + both sides, no back) is a placeholder
 * that must be calibrated. The final system therefore needs, besides "when", also "is the attacker inside
 * my defender's block arc right now" — which requires the defender's position AND facing direction from
 * the screen. Neither is measured by the benchmark yet (see docs/FEASIBILITY.md).
 */
enum class BlockArc(val totalDeg: Float, val label: String) {
    FRONT_180(180f, "정면 180°(대부분)"),
    FRONT_SIDE(270f, "정면+측면(각도 미확인, 270° 가정)"),
    OMNI_360(360f, "360°(르브론)");

    val halfAngleDeg get() = totalDeg / 2f
}

object BlockGeometry {
    /**
     * Signed angle (deg, -180..180) between the defender's facing direction and the direction to the attacker,
     * in the SAME 2-D frame for all inputs (screen coords: x right, y down; facing 0 = +x, 90 = +y).
     * Caveat: with a tilted camera, screen angles differ from court angles; calibrate before trusting.
     */
    fun relativeAngleDeg(defX: Float, defY: Float, facingDeg: Float, attX: Float, attY: Float): Float {
        val toAtt = Math.toDegrees(atan2((attY - defY).toDouble(), (attX - defX).toDouble())).toFloat()
        var d = toAtt - facingDeg
        while (d > 180f) d -= 360f
        while (d < -180f) d += 360f
        return d
    }

    /** True if the attacker lies inside the block arc. A 360 arc always passes; the boundary counts as inside. */
    fun inArc(arc: BlockArc, defX: Float, defY: Float, facingDeg: Float, attX: Float, attY: Float, customHalfDeg: Float? = null): Boolean {
        if (arc == BlockArc.OMNI_360 && customHalfDeg == null) return true
        val half = customHalfDeg ?: arc.halfAngleDeg
        return abs(relativeAngleDeg(defX, defY, facingDeg, attX, attY)) <= half
    }
}
