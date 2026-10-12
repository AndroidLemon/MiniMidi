package com.example.minimidi

import org.junit.Assert.assertEquals
import org.junit.Test

class MidiParserClockTest {
    private val notes = mutableListOf<Triple<Int, Int, Int>>()
    private val events = mutableListOf<String>()
    private val parser = MidiParser(
        onNoteOn = { c, n, v -> notes += Triple(c, n, v) },
        onClock = { events += "clock" },
        onStart = { events += "start" },
        onContinue = { events += "continue" },
        onStop = { events += "stop" },
        onSongPosition = { events += "spp$it" },
    )

    private fun feed(vararg b: Int) {
        val bytes = ByteArray(b.size) { b[it].toByte() }
        parser.feed(bytes, 0, bytes.size)
    }

    @Test fun realTimeMessages() {
        feed(0xFA, 0xF8, 0xF8, 0xFC, 0xFB)
        assertEquals(listOf("start", "clock", "clock", "stop", "continue"), events)
    }

    @Test fun clockInsideNoteOnKeepsNoteAndFiresClock() {
        feed(0x90, 60, 0xF8, 100)
        assertEquals(listOf(Triple(1, 60, 100)), notes)
        assertEquals(listOf("clock"), events)
    }

    @Test fun clockKeepsRunningStatus() {
        feed(0x90, 60, 100, 0xF8, 62, 90)
        assertEquals(listOf(Triple(1, 60, 100), Triple(1, 62, 90)), notes)
    }

    @Test fun songPosition() {
        feed(0xF2, 0x01, 0x02)
        assertEquals(listOf("spp${1 or (2 shl 7)}"), events)
    }

    @Test fun songPositionSplitAcrossFeeds() {
        feed(0xF2)
        feed(0x10)
        assertEquals(emptyList<String>(), events)
        feed(0x00)
        assertEquals(listOf("spp16"), events)
    }

    @Test fun songPositionWithClockInside() {
        feed(0xF2, 0x05, 0xF8, 0x01)
        assertEquals(listOf("clock", "spp${5 or (1 shl 7)}"), events)
    }

    @Test fun songPositionIsNotRunningStatus() {
        feed(0x90, 60, 100, 0xF2, 1, 2, 3, 4, 62, 90)
        assertEquals(listOf(Triple(1, 60, 100)), notes)
        assertEquals(listOf("spp${1 or (2 shl 7)}"), events)
    }
}
