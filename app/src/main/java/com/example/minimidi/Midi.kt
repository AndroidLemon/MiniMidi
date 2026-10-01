package com.example.minimidi

// Raw MIDI 1.0 channel messages. Channel is 1-based (1..16).
fun noteOn(channel: Int, note: Int, velocity: Int) = message(0x90, channel, note, velocity)
fun noteOff(channel: Int, note: Int) = message(0x80, channel, note, 0)
fun allNotesOff(channel: Int) = message(0xB0, channel, 123, 0)

private fun message(status: Int, channel: Int, data1: Int, data2: Int): ByteArray {
    require(channel in 1..16 && data1 in 0..127 && data2 in 0..127) { "bad MIDI message: ch=$channel $data1 $data2" }
    return byteArrayOf((status or (channel - 1)).toByte(), data1.toByte(), data2.toByte())
}
