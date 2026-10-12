package com.example.minimidi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PadMapTest {
    @Test fun encodeDecodeRoundTrip() {
        val pads = List(16) { Pad(note = 10 + it * 7, channel = 1 + it) }
        assertEquals(pads, decodePads(encodePads(pads)))
        assertEquals(DEFAULT_PADS, decodePads(encodePads(DEFAULT_PADS)))
        assertEquals("36:10,37:10", encodePads(DEFAULT_PADS).substringBefore(",38"))
    }

    @Test fun badStringsFallBackToDefault() {
        val ok = DEFAULT_PADS.map { "${it.note}:${it.channel}" }
        assertEquals(DEFAULT_PADS, decodePads(null))
        assertEquals(DEFAULT_PADS, decodePads(""))
        assertEquals(DEFAULT_PADS, decodePads(ok.take(15).joinToString(",")))
        assertEquals(DEFAULT_PADS, decodePads((ok + "36:10").joinToString(",")))
        assertEquals(DEFAULT_PADS, decodePads((listOf("128:10") + ok.drop(1)).joinToString(",")))
        assertEquals(DEFAULT_PADS, decodePads((listOf("36:0") + ok.drop(1)).joinToString(",")))
        assertEquals(DEFAULT_PADS, decodePads((listOf("36:17") + ok.drop(1)).joinToString(",")))
        assertEquals(DEFAULT_PADS, decodePads((listOf("-1:10") + ok.drop(1)).joinToString(",")))
        assertEquals(DEFAULT_PADS, decodePads("junk"))
        assertEquals(DEFAULT_PADS, decodePads((listOf("a:b") + ok.drop(1)).joinToString(",")))
        assertEquals(DEFAULT_PADS, decodePads((listOf("36") + ok.drop(1)).joinToString(",")))
    }

    @Test fun extraFieldsAreIgnored() {
        val s = DEFAULT_PADS.joinToString(",") { "${it.note}:${it.channel}:x" }
        assertEquals(DEFAULT_PADS, decodePads(s))
    }

    @Test fun playedAppliesBankOffset() {
        val pad = Pad(note = 40, channel = 3)
        assertEquals(pad, played(pad, 0))
        assertEquals(Pad(note = 56, channel = 3), played(pad, 1))
        assertEquals(Pad(note = 120, channel = 3), played(pad, 5))
        assertEquals(Pad(note = 127, channel = 3), played(Pad(note = 47, channel = 3), 5))
        assertNull(played(Pad(note = 48, channel = 3), 5))
        assertNull(played(Pad(note = 127, channel = 3), 1))
    }

    @Test fun learnedNoteIsRelativeToBank() {
        assertEquals(60, learnedNote(60, 0))
        assertEquals(44, learnedNote(60, 1))
        assertEquals(0, learnedNote(80, 5))
        assertNull(learnedNote(79, 5))
        assertNull(learnedNote(10, 1))
    }

    @Test fun bankNames() {
        assertEquals(6, BANK_COUNT)
        assertEquals(listOf("A", "B", "C", "D", "E", "F"), (0 until BANK_COUNT).map { bankName(it) })
    }

    @Test fun labels() {
        assertEquals("BD", padLabel(36, 10))
        assertEquals("RD", padLabel(51, 10))
        assertEquals("C3", padLabel(60, 1))
        assertEquals("D#1", padLabel(39, 1))
        assertEquals("C1", padLabel(36, 1))
        assertEquals("C0", padLabel(24, 1))
        assertEquals("C0", padLabel(24, 10)) // outside GM drum range 35..81
        assertEquals("A5", padLabel(93, 10))
        assertEquals("G8", padLabel(127, 1))
    }
}
