package com.example.minimidi

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

class MainActivity : ComponentActivity() {
    private lateinit var midi: MidiOut
    private lateinit var engine: PadEngine
    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }
    // Saved in onPause, not on every change: a slider drag fires this ~100 times a second.
    private var settings by mutableStateOf(Settings())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = prefs.loadSettings()
        midi = MidiOut(this)
        engine = PadEngine(midi::send)
        val haptics = Haptics(this)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                PadScreen(
                    settings = settings,
                    onSettingsChange = { settings = it },
                    connected = midi.connected,
                    engine = engine,
                    haptics = haptics,
                )
            }
        }
    }

    override fun onStart() { super.onStart(); midi.start() }

    override fun onResume() {
        super.onResume()
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    // Leaving the foreground must never leave a note droning in the DAW.
    override fun onPause() {
        engine.panic(DEFAULT_PADS.map { it.channel })
        prefs.save(settings)
        super.onPause()
    }

    override fun onStop() { midi.stop(); super.onStop() }
}
