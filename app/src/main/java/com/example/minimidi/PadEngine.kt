package com.example.minimidi

/**
 * Tracks which pad each finger pressed and emits MIDI bytes through [send].
 * Note-off always uses the Pad captured at press time, so remaps, banks or fold changes mid-hit can't leave notes stuck.
 */
class PadEngine(private val send: (ByteArray) -> Unit) {
    private val held = HashMap<Int, Pad>()

    fun press(pointerId: Int, pad: Pad, velocity: Int) {
        release(pointerId) // a missed UP must not leave the old note hanging
        held[pointerId] = pad
        send(noteOn(pad.channel, pad.note, velocity.coerceIn(1, 127)))
    }

    fun release(pointerId: Int) {
        held.remove(pointerId)?.let { send(noteOff(it.channel, it.note)) }
    }

    fun releaseAll() {
        held.values.forEach { send(noteOff(it.channel, it.note)) }
        held.clear()
    }

    /** App leaving the foreground: note-offs for held pads, then All Notes Off on every channel in use. */
    fun panic(channels: Collection<Int>) {
        val all = (channels + held.values.map { it.channel }).distinct()
        releaseAll()
        all.forEach { send(allNotesOff(it)) }
    }
}
