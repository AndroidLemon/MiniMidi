package com.example.minimidi

import org.junit.Assert.assertEquals
import org.junit.Test

class MidiParserTest {
    private val got = mutableListOf<Triple<Int, Int, Int>>()
    private val parser = MidiParser { c, n, v -> got += Triple(c, n, v) }

    private fun feed(vararg b: Int) {
        val bytes = ByteArray(b.size) { b[it].toByte() }
        parser.feed(bytes, 0, bytes.size)
    }

    @Test fun plainNoteOn() {
        feed(0x90, 60, 100)
        assertEquals(listOf(Triple(1, 60, 100)), got)
    }

    @Test fun runningStatus() {
        feed(0x92, 60, 100, 62, 90)
        assertEquals(listOf(Triple(3, 60, 100), Triple(3, 62, 90)), got)
    }

    @Test fun realTimeInsideMessageIsIgnored() {
        feed(0x90, 60, 0xF8, 100, 0xF8, 62, 90)
        assertEquals(listOf(Triple(1, 60, 100), Triple(1, 62, 90)), got)
    }

    @Test fun sysexIsSkipped() {
        feed(0xF0, 0x7D, 0x10, 0x20, 0xF7, 0x90, 64, 80)
        assertEquals(listOf(Triple(1, 64, 80)), got)
    }

    @Test fun sysexDataIsNotRunningStatus() {
        feed(0x90, 60, 100, 0xF0, 1, 2, 0xF7, 5, 6)
        assertEquals(listOf(Triple(1, 60, 100)), got)
    }

    @Test fun velocityZeroIsIgnored() {
        feed(0x90, 60, 0)
        assertEquals(emptyList<Triple<Int, Int, Int>>(), got)
    }

    @Test fun programChangeThenNoteOn() {
        feed(0xC0, 5, 0x90, 60, 100)
        assertEquals(listOf(Triple(1, 60, 100)), got)
    }

    @Test fun programChangeRunningStatusTakesOneByte() {
        feed(0xC0, 5, 6, 0x90, 60, 100)
        assertEquals(listOf(Triple(1, 60, 100)), got)
    }

    @Test fun splitAcrossFeeds() {
        feed(0x90, 60)
        assertEquals(emptyList<Triple<Int, Int, Int>>(), got)
        feed(100)
        assertEquals(listOf(Triple(1, 60, 100)), got)
    }

    @Test fun noteOffDoesNotEmit() {
        feed(0x80, 60, 64)
        assertEquals(emptyList<Triple<Int, Int, Int>>(), got)
    }

    @Test fun channelIsOneBased() {
        feed(0x9F, 60, 100)
        assertEquals(listOf(Triple(16, 60, 100)), got)
    }

    @Test fun strayDataBytesIgnored() {
        feed(60, 100, 0x90, 61, 100)
        assertEquals(listOf(Triple(1, 61, 100)), got)
    }

    @Test fun systemCommonClearsRunningStatus() {
        feed(0x90, 60, 100, 0xF2, 1, 2, 62, 90)
        assertEquals(listOf(Triple(1, 60, 100)), got)
    }
}
