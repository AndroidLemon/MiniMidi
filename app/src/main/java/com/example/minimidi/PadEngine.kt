package com.example.minimidi

/**
 * Tracks which pad each finger pressed and emits MIDI bytes through [send].
 * Note-off always uses the Pad captured at press time, so remaps, banks or fold changes mid-hit can't leave notes stuck.
 */
class PadEngine(private val send: (ByteArray) -> Unit) {
    private class Held(val pad: Pad, val velocity: Int)
    private val held = HashMap<Int, Held>()

    /** Called with the pointer id just before each press is applied, outside this engine's lock (NoteRepeat takes its own lock first). */
    @Volatile var onPress: ((pointerId: Int) -> Unit)? = null

    fun press(pointerId: Int, pad: Pad, velocity: Int) {
        onPress?.invoke(pointerId)
        pressLocked(pointerId, pad, velocity)
    }

    @Synchronized private fun pressLocked(pointerId: Int, pad: Pad, velocity: Int) {
        release(pointerId) // a missed UP must not leave the old note hanging
        val v = velocity.coerceIn(1, 127)
        held[pointerId] = Held(pad, v)
        send(noteOn(pad.channel, pad.note, v))
    }

    @Synchronized fun release(pointerId: Int) {
        held.remove(pointerId)?.let { send(noteOff(it.pad.channel, it.pad.note)) }
    }

    /** Note-off then note-on of the pad this pointer pressed, at its original velocity. Unknown pointer: nothing. */
    @Synchronized fun retrigger(pointerId: Int) {
        val h = held[pointerId] ?: return
        send(noteOff(h.pad.channel, h.pad.note))
        send(noteOn(h.pad.channel, h.pad.note, h.velocity))
    }

    @Synchronized fun heldPointers(): List<Int> = held.keys.toList()

    @Synchronized fun releaseAll() {
        held.values.forEach { send(noteOff(it.pad.channel, it.pad.note)) }
        held.clear()
    }

    /** App leaving the foreground: note-offs for held pads, then All Notes Off on every channel in use. */
    @Synchronized fun panic(channels: Collection<Int>) {
        val all = (channels + held.values.map { it.pad.channel }).distinct()
        releaseAll()
        all.forEach { send(allNotesOff(it)) }
    }
}
