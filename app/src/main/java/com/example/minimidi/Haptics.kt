package com.example.minimidi

import android.content.Context
import android.os.VibrationEffect
import android.os.VibrationEffect.Composition.PRIMITIVE_CLICK
import android.os.VibratorManager

/** A short, crisp tick per hit: a velocity-scaled click primitive where supported, else the system's predefined click. */
class Haptics(context: Context) {
    private val vibrator = context.getSystemService(VibratorManager::class.java).defaultVibrator
    private val supported = vibrator.areAllPrimitivesSupported(PRIMITIVE_CLICK)

    fun tick(velocity: Int) {
        vibrator.vibrate(
            if (supported) VibrationEffect.startComposition().addPrimitive(PRIMITIVE_CLICK, velocity.coerceIn(1, 127) / 127f).compose()
            else VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK),
        )
    }
}
