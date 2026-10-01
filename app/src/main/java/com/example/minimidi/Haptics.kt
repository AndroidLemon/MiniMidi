package com.example.minimidi

import android.content.Context
import android.os.VibrationEffect
import android.os.VibrationEffect.Composition.PRIMITIVE_CLICK
import android.os.VibratorManager

/** A short, crisp tick per hit, scaled by velocity. Silent on vibrators without haptic primitives. */
class Haptics(context: Context) {
    private val vibrator = context.getSystemService(VibratorManager::class.java).defaultVibrator
    private val supported = vibrator.areAllPrimitivesSupported(PRIMITIVE_CLICK)

    fun tick(velocity: Int) {
        if (!supported) return
        vibrator.vibrate(VibrationEffect.startComposition().addPrimitive(PRIMITIVE_CLICK, velocity.coerceIn(1, 127) / 127f).compose())
    }
}
