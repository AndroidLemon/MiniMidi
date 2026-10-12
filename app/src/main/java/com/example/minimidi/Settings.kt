package com.example.minimidi

import android.content.SharedPreferences

const val BPM_MIN = 30
const val BPM_MAX = 300

data class Settings(
    val sensitivity: Int = 5,
    val fixedVelocity: Boolean = false,
    val fixedValue: Int = 100,
    val haptics: Boolean = true,
    val bigPadsWhenFolded: Boolean = false,
    val rate: Rate = Rate.Sixteenth,
    val internalBpm: Int = 120,
)

fun SharedPreferences.loadSettings(): Settings {
    val d = Settings()
    return Settings(
        sensitivity = getInt("sensitivity", d.sensitivity).coerceIn(SENSITIVITY_MIN, SENSITIVITY_MAX),
        fixedVelocity = getBoolean("fixedVelocity", d.fixedVelocity),
        fixedValue = getInt("fixedValue", d.fixedValue).coerceIn(1, 127),
        haptics = getBoolean("haptics", d.haptics),
        bigPadsWhenFolded = getBoolean("bigPadsWhenFolded", d.bigPadsWhenFolded),
        rate = Rate.values().firstOrNull { it.name == getString("rate", null) } ?: d.rate,
        internalBpm = getInt("internalBpm", d.internalBpm).coerceIn(BPM_MIN, BPM_MAX),
    )
}

fun SharedPreferences.save(settings: Settings) = edit()
    .putInt("sensitivity", settings.sensitivity)
    .putBoolean("fixedVelocity", settings.fixedVelocity)
    .putInt("fixedValue", settings.fixedValue)
    .putBoolean("haptics", settings.haptics)
    .putBoolean("bigPadsWhenFolded", settings.bigPadsWhenFolded)
    .putString("rate", settings.rate.name)
    .putInt("internalBpm", settings.internalBpm)
    .apply()
