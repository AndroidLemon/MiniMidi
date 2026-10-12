package com.example.minimidi

import org.junit.Assert.assertEquals
import org.junit.Test

class PadEngineTest {
    private val sent = mutableListOf<List<Int>>()
    private val engine = PadEngine { sent += it.u() }
    private val kick = DEFAULT_PADS[0]   // note 36 = 0x24
    private val snare = DEFAULT_PADS[2]  // note 38 = 0x26

    @Test fun pressSendsNoteOn() {
        engine.press(1, kick, 100)
        assertEquals(listOf(listOf(0x99, 0x24, 100)), sent)
    }

    @Test fun pressClampsVelocity() {
        engine.press(1, kick, 0)
        assertEquals(listOf(listOf(0x99, 0x24, 1)), sent)
    }

    @Test fun releaseSendsNoteOff() {
        engine.press(1, kick, 100); sent.clear()
        engine.release(1)
        assertEquals(listOf(listOf(0x89, 0x24, 0)), sent)
    }

    @Test fun releaseUnknownPointerSendsNothing() {
        engine.release(42)
        assertEquals(emptyList<List<Int>>(), sent)
    }

    // Review Focus 2: the note turned off is the one captured at press, whatever the pad map says now.
    @Test fun releaseUsesPadCapturedAtPress() {
        var current = kick
        engine.press(1, current, 100)
        current = Pad(note = 60, channel = 1) // remap/bank/fold changed the pad under the finger
        sent.clear()
        engine.release(1)
        assertEquals(listOf(listOf(0x89, 0x24, 0)), sent)
    }

    // Review Focus 3: a missed UP must not leave the first note hanging.
    @Test fun pressingSamePointerTwiceTurnsOffFirstNote() {
        engine.press(1, kick, 100)
        engine.press(1, snare, 90)
        assertEquals(listOf(listOf(0x99, 0x24, 100), listOf(0x89, 0x24, 0), listOf(0x99, 0x26, 90)), sent)
    }

    @Test fun twoFingersOnOnePadAreIndependent() {
        engine.press(1, kick, 100)
        engine.press(2, kick, 80)
        sent.clear()
        engine.release(1)
        assertEquals(listOf(listOf(0x89, 0x24, 0)), sent)
    }

    // Review Focus 4: ACTION_CANCEL / panel opening turns everything off exactly once.
    @Test fun releaseAllTurnsOffEveryHeldNote() {
        engine.press(1, kick, 100)
        engine.press(2, snare, 100)
        sent.clear()
        engine.releaseAll()
        assertEquals(setOf(listOf(0x89, 0x24, 0), listOf(0x89, 0x26, 0)), sent.toSet())
        assertEquals(2, sent.size)
        sent.clear()
        engine.release(1); engine.release(2)
        assertEquals(emptyList<List<Int>>(), sent)
    }

    @Test fun panicSendsNoteOffsThenOneAllNotesOffPerChannel() {
        engine.press(1, kick, 100)
        sent.clear()
        engine.panic(listOf(10, 10))
        assertEquals(listOf(listOf(0x89, 0x24, 0), listOf(0xB9, 123, 0)), sent)
    }

    @Test fun retriggerSendsOffThenOnAtStoredVelocity() {
        engine.press(1, kick, 77); sent.clear()
        engine.retrigger(1)
        assertEquals(listOf(listOf(0x89, 0x24, 0), listOf(0x99, 0x24, 77)), sent)
        engine.release(1)
        assertEquals(listOf(0x89, 0x24, 0), sent.last()) // still held after retrigger
    }

    @Test fun retriggerUnknownPointerSendsNothing() {
        engine.retrigger(9)
        assertEquals(emptyList<List<Int>>(), sent)
    }

    @Test fun heldPointersAndPressHook() {
        val pressed = mutableListOf<Int>()
        engine.onPress = { pressed += it }
        engine.press(3, kick, 100); engine.press(5, snare, 100); engine.release(3)
        assertEquals(listOf(5), engine.heldPointers())
        assertEquals(listOf(3, 5), pressed)
    }
}
