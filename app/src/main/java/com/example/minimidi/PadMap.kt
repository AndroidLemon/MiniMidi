package com.example.minimidi

/** One pad's output. [channel] is 1-based, as musicians and DAWs show it (10 = GM drums). */
data class Pad(val note: Int, val channel: Int, val label: String)

// General MIDI drum map, notes 36..51, so each label is what a GM kit (and Logic's kits) plays.
private val LABELS = listOf("BD", "RS", "SD", "CP", "SD2", "FT", "CH", "FT2", "PH", "LT", "OH", "MT", "MT2", "CY", "HT", "RD")

// ponytail: fixed table for v1. Remapping and banks will edit this list, not the code that reads it.
val DEFAULT_PADS: List<Pad> = LABELS.mapIndexed { i, label -> Pad(note = 36 + i, channel = 10, label = label) }
