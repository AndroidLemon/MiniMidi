package com.example.minimidi

import android.content.SharedPreferences

/** One pad's output. [note] is relative to bank A; [channel] is 1-based, as musicians and DAWs show it (10 = GM drums). */
data class Pad(val note: Int, val channel: Int)

const val BANK_COUNT = 6

// General MIDI percussion map, notes 35..81, in terse pad-label style (what a GM kit, and Logic's kits, plays).
private val GM_LABELS = listOf(
    "BD2", "BD", "RS", "SD", "CP", "SD2", "FT", "CH", "FT2", "PH", "LT", "OH", "MT", "MT2", "CY", "HT", "RD", // 35..51
    "CC", "RB", "TB", "SP", "CB", "CY2", "VS", "RD2", "HB", "LB", "MC", "OC", "LC", "HTB", "LTB", // 52..66
    "HA", "LA", "CAB", "MAR", "WS", "WL", "GS", "GL", "CLV", "WB", "WB2", "CU", "CU2", "TR", "TR2", // 67..81
)
private val NOTE_NAMES = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

val DEFAULT_PADS: List<Pad> = List(16) { Pad(note = 36 + it, channel = 10) }

/** GM drum name on channel 10 for notes 35..81, otherwise the note name with C1 = 36 (e.g. "D#2"). */
fun padLabel(note: Int, channel: Int): String {
    if (channel == 10 && note in 35..81) return GM_LABELS[note - 35]
    return NOTE_NAMES[Math.floorMod(note, 12)] + (Math.floorDiv(note, 12) - 2)
}

fun bankName(bank: Int): String = ('A' + bank).toString()

/** The pad as sent in [bank], or null if its note would pass 127. */
fun played(pad: Pad, bank: Int): Pad? = (pad.note + bank * 16).takeIf { it <= 127 }?.let { pad.copy(note = it) }

/** The stored (bank A) note for an [incoming] note heard while in [bank], or null if that's below 0. */
fun learnedNote(incoming: Int, bank: Int): Int? = (incoming - bank * 16).takeIf { it >= 0 }

fun encodePads(pads: List<Pad>): String = pads.joinToString(",") { "${it.note}:${it.channel}" }

/** [DEFAULT_PADS] unless [s] is exactly 16 `note:channel` entries in range. Extra `:x` fields are ignored (forward compatibility). */
fun decodePads(s: String?): List<Pad> {
    val pads = s?.split(',')?.map { entry ->
        val f = entry.split(':')
        val note = f.getOrNull(0)?.toIntOrNull()?.takeIf { it in 0..127 }
        val channel = f.getOrNull(1)?.toIntOrNull()?.takeIf { it in 1..16 }
        if (note == null || channel == null) return DEFAULT_PADS
        Pad(note, channel)
    }
    return if (pads?.size == 16) pads else DEFAULT_PADS
}

fun SharedPreferences.loadPads(): List<Pad> = decodePads(getString("padmap.v1", null))

fun SharedPreferences.savePads(pads: List<Pad>) = edit().putString("padmap.v1", encodePads(pads)).apply()

fun SharedPreferences.loadBank(): Int = getInt("bank", 0).coerceIn(0, BANK_COUNT - 1)

fun SharedPreferences.saveBank(bank: Int) = edit().putInt("bank", bank.coerceIn(0, BANK_COUNT - 1)).apply()
