package com.example.minimidi

/**
 * Pulls note-ons out of a raw MIDI 1.0 byte stream; everything else is consumed and dropped. Channel is 1-based.
 * Keeps state between feed() calls, so a message may be split anywhere. Not thread-safe: feed from one thread.
 */
class MidiParser(
    private val onNoteOn: (channel: Int, note: Int, velocity: Int) -> Unit,
    private val onClock: () -> Unit = {},
    private val onStart: () -> Unit = {},
    private val onContinue: () -> Unit = {},
    private val onStop: () -> Unit = {},
    private val onSongPosition: (sixteenths: Int) -> Unit = {},
) {
    // keeps `MidiParser { ch, note, vel -> }` compiling: a trailing lambda would otherwise bind to the last parameter
    constructor(onNoteOn: (channel: Int, note: Int, velocity: Int) -> Unit) : this(onNoteOn, {})

    private var status = 0 // running status; 0 = none (also after sysex / system common; F2 only while collecting its data)
    private var inSysex = false
    private var data1 = -1

    fun feed(bytes: ByteArray, offset: Int, count: Int) {
        for (i in offset until offset + count) {
            val b = bytes[i].toInt() and 0xFF
            when {
                b >= 0xF8 -> when (b) { // real-time: may appear anywhere and touches no parser state
                    0xF8 -> onClock()
                    0xFA -> onStart()
                    0xFB -> onContinue()
                    0xFC -> onStop()
                }
                b >= 0x80 -> {
                    inSysex = b == 0xF0
                    status = if (b >= 0xF0 && b != 0xF2) 0 else b // F2 (song position) keeps status for its data bytes; ponytail: F1..F6 data bytes just fall out as strays
                    data1 = -1
                }
                inSysex || status == 0 -> {}
                status and 0xF0 == 0xC0 || status and 0xF0 == 0xD0 -> data1 = -1 // one data byte, dropped
                data1 < 0 -> data1 = b
                status == 0xF2 -> {
                    onSongPosition(data1 or (b shl 7))
                    status = 0
                    data1 = -1
                }
                else -> {
                    if (status and 0xF0 == 0x90 && b > 0) onNoteOn((status and 0x0F) + 1, data1, b)
                    data1 = -1
                }
            }
        }
    }
}
