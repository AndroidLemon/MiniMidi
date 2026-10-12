package com.example.minimidi

import org.junit.Assert.assertEquals
import org.junit.Test

fun ByteArray.u(): List<Int> = map { it.toInt() and 0xFF }

class MidiTest {
    @Test fun noteOnChannel10() = assertEquals(listOf(0x99, 0x24, 0x64), noteOn(10, 36, 100).u())
    @Test fun noteOnChannel1() = assertEquals(listOf(0x90, 0x3C, 0x01), noteOn(1, 60, 1).u())
    @Test fun noteOffChannel10() = assertEquals(listOf(0x89, 0x24, 0x00), noteOff(10, 36).u())
    @Test fun allNotesOffIsCc123() = assertEquals(listOf(0xB9, 0x7B, 0x00), allNotesOff(10).u())

    @Test(expected = IllegalArgumentException::class) fun channel0Rejected() { noteOn(0, 36, 100) }
    @Test(expected = IllegalArgumentException::class) fun channel17Rejected() { noteOn(17, 36, 100) }
    @Test(expected = IllegalArgumentException::class) fun velocity128Rejected() { noteOn(10, 36, 128) }

    @Test fun defaultPadsAreGmDrumNotes36To51OnChannel10() {
        assertEquals((36..51).toList(), DEFAULT_PADS.map { it.note })
        assertEquals(List(16) { 10 }, DEFAULT_PADS.map { it.channel })
        assertEquals(
            listOf("BD", "RS", "SD", "CP", "SD2", "FT", "CH", "FT2", "PH", "LT", "OH", "MT", "MT2", "CY", "HT", "RD"),
            DEFAULT_PADS.map { padLabel(it.note, it.channel) },
        )
    }
}
