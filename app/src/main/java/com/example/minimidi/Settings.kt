package com.example.minimidi

import android.content.SharedPreferences

data class Settings(
    val sensitivity: Int = 5,
    val fixedVelocity: Boolean = false,
    val fixedValue: Int = 100,
    val haptics: Boolean = true,
    val bigPadsWhenFolded: Boolean = false,
)

fun SharedPreferences.loadSettings(): Settings {
    val d = Settings()
    return Settings(
        sensitivity = getInt("sensitivity", d.sensitivity).coerceIn(SENSITIVITY_MIN, SENSITIVITY_MAX),
        fixedVelocity = getBoolean("fixedVelocity", d.fixedVelocity),
        fixedValue = getInt("fixedValue", d.fixedValue).coerceIn(1, 127),
        haptics = getBoolean("haptics", d.haptics),
        bigPadsWhenFolded = getBoolean("bigPadsWhenFolded", d.bigPadsWhenFolded),
    )
}

fun SharedPreferences.save(settings: Settings) = edit()
    .putInt("sensitivity", settings.sensitivity)
    .putBoolean("fixedVelocity", settings.fixedVelocity)
    .putInt("fixedValue", settings.fixedValue)
    .putBoolean("haptics", settings.haptics)
    .putBoolean("bigPadsWhenFolded", settings.bigPadsWhenFolded)
    .apply()
