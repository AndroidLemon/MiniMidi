package com.example.minimidi

import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

private const val ECHO_WINDOW_MS = 100L

class MainActivity : ComponentActivity() {
    private lateinit var midi: MidiOut
    private lateinit var engine: PadEngine
    private lateinit var noteRepeat: NoteRepeat
    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }
    // Saved in onPause, not on every change: a slider drag fires this ~100 times a second.
    private var settings by mutableStateOf(Settings())
    // Pads and bank are saved on every change (taps, not drags).
    private var pads by mutableStateOf(DEFAULT_PADS)
    private var bank by mutableIntStateOf(0)
    private var heardNote by mutableStateOf<HeardNote?>(null)
    // Plain fields, not Compose state: notes from the computer only touch the UI while LEARN is armed.
    private var learnArmed = false
    private var lastSentAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = prefs.loadSettings()
        pads = prefs.loadPads()
        bank = prefs.loadBank()
        midi = MidiOut(this)
        midi.onNote = { channel, note ->
            // A note right after our own pad press is probably the DAW echoing it back: not something to learn.
            if (learnArmed && SystemClock.uptimeMillis() - lastSentAt > ECHO_WINDOW_MS) heardNote = HeardNote(channel, note)
        }
        engine = PadEngine { bytes -> lastSentAt = SystemClock.uptimeMillis(); midi.send(bytes) }
        noteRepeat = NoteRepeat(engine) // hooks engine.onPress itself
        midi.clock = object : ClockListener {
            override fun onClock() = noteRepeat.externalClock()
            override fun onStart() = noteRepeat.externalStart()
            override fun onContinue() = noteRepeat.externalContinue()
            override fun onStop() = noteRepeat.externalStop()
            override fun onSongPosition(sixteenths: Int) = noteRepeat.externalSongPosition(sixteenths)
        }
        val haptics = Haptics(this)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                PadScreen(
                    settings = settings,
                    onSettingsChange = { settings = it },
                    connected = midi.connected,
                    inputConnected = midi.inputConnected,
                    pads = pads,
                    onPadsChange = { pads = it; prefs.savePads(it) },
                    bank = bank,
                    onBankChange = { bank = it; prefs.saveBank(it) },
                    heardNote = heardNote,
                    onArmedChange = { learnArmed = it },
                    engine = engine,
                    noteRepeat = noteRepeat,
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
        noteRepeat.stop()
        engine.panic(pads.map { it.channel })
        prefs.save(settings)
        super.onPause()
    }

    override fun onStop() { midi.stop(); super.onStop() }
}
