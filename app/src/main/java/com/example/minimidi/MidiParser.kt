package com.example.minimidi

/**
 * Pulls note-ons out of a raw MIDI 1.0 byte stream; everything else is consumed and dropped. Channel is 1-based.
 * Keeps state between feed() calls, so a message may be split anywhere. Not thread-safe: feed from one thread.
 */
class MidiParser(private val onNoteOn: (channel: Int, note: Int, velocity: Int) -> Unit) {
    private var status = 0 // running status; 0 = none (also after sysex / system common)
    private var inSysex = false
    private var data1 = -1

    fun feed(bytes: ByteArray, offset: Int, count: Int) {
        for (i in offset until offset + count) {
            val b = bytes[i].toInt() and 0xFF
            when {
                b >= 0xF8 -> {} // real-time: may appear anywhere and touches no state
                b >= 0x80 -> {
                    inSysex = b == 0xF0
                    status = if (b >= 0xF0) 0 else b // ponytail: F1..F6 data bytes just fall out as strays
                    data1 = -1
                }
                inSysex || status == 0 -> {}
                status and 0xF0 == 0xC0 || status and 0xF0 == 0xD0 -> data1 = -1 // one data byte, dropped
                data1 < 0 -> data1 = b
                else -> {
                    if (status and 0xF0 == 0x90 && b > 0) onNoteOn((status and 0x0F) + 1, data1, b)
                    data1 = -1
                }
            }
        }
    }
}
