package com.example.minimidi

import kotlin.math.roundToInt

const val SENSITIVITY_MIN = 1
const val SENSITIVITY_MAX = 10

// Softest real hit. Velocity 1 is near-silent in Logic's kits; hardware pads keep a floor too. Tuned in the Task 10 feel check.
const val MIN_VELOCITY = 20

// Contact diameters below this are treated as the softest hit.
private const val FLOOR_DP = 25f

/**
 * Touch contact diameter (dp, from MotionEvent.getTouchMajor) → MIDI velocity MIN_VELOCITY..127.
 * Higher sensitivity means a smaller contact reaches full velocity.
 * Devices that report no usable contact size (≤ 1 dp, or NaN) get [fixedValue], so pads never go silent.
 */
fun velocity(contactDp: Float, sensitivity: Int, fixed: Boolean, fixedValue: Int): Int {
    val fallback = fixedValue.coerceIn(1, 127)
    if (fixed || contactDp.isNaN() || contactDp <= 1f) return fallback
    val s = sensitivity.coerceIn(SENSITIVITY_MIN, SENSITIVITY_MAX)
    // ponytail: linear curve; the sensitivity slider is the per-device calibration knob. Add a curve setting if feel demands it.
    val fullScaleDp = 100f - 6f * s // s=1 → 94 dp, s=10 → 40 dp
    val t = ((contactDp - FLOOR_DP) / (fullScaleDp - FLOOR_DP)).coerceIn(0f, 1f)
    return (MIN_VELOCITY + t * (127 - MIN_VELOCITY)).roundToInt()
}
