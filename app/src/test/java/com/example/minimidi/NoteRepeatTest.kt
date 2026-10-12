package com.example.minimidi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteRepeatTest {
    @Test fun rateOrderAndTicks() {
        assertEquals(listOf("1/4", "1/8", "1/8T", "1/16", "1/16T", "1/32", "1/32T"), Rate.values().map { it.label })
        assertEquals(listOf(24, 12, 8, 6, 4, 3, 2), Rate.values().map { it.ticks })
    }

    @Test fun rateNextCycles() {
        assertEquals(Rate.Eighth, Rate.Quarter.next())
        assertEquals(Rate.Quarter, Rate.ThirtySecondT.next())
        assertEquals(7, generateSequence(Rate.Quarter) { it.next() }.take(8).toSet().size)
    }

    @Test fun firesOnGridOnly() {
        for (r in Rate.values()) for (t in 100L..160L) {
            assertEquals("${r.label} tick $t", t % r.ticks == 0L, fires(t, 0, r.ticks))
        }
    }

    @Test fun skipsGridLineJustAfterPress() {
        assertFalse(fires(6, 5, 6))
        assertTrue(fires(12, 5, 6))
        assertTrue(fires(6, 3, 6))
        assertFalse(fires(6, 4, 6))
    }

    @Test fun pressOnGridLineDoesNotFire() {
        for (r in Rate.values().filter { it.ticks >= 2 }) assertFalse(r.label, fires(r.ticks * 4L, r.ticks * 4L, r.ticks))
    }

    @Test fun tapTempo120() {
        var t = 0L
        val tt = TapTempo { t }
        assertNull(tt.tap())
        t += 500; assertEquals(120, tt.tap())
        t += 500; assertEquals(120, tt.tap())
    }

    @Test fun tapTempoAveragesLastFourIntervals() {
        var t = 0L
        val tt = TapTempo { t }
        tt.tap()
        t += 1000; tt.tap() // 60 bpm interval, falls out of the window
        repeat(4) { t += 500; tt.tap() }
        t += 500
        assertEquals(120, tt.tap())
    }

    @Test fun tapTempoResetsAfterGap() {
        var t = 0L
        val tt = TapTempo { t }
        tt.tap(); t += 500; tt.tap()
        t += 2001
        assertNull(tt.tap())
        t += 400
        assertEquals(150, tt.tap())
    }

    @Test fun tapTempoGapOfExactly2sContinues() {
        var t = 0L
        val tt = TapTempo { t }
        tt.tap(); t += 2000
        assertEquals(30, tt.tap())
    }

    @Test fun tapTempoClamps() {
        var t = 0L
        val tt = TapTempo { t }
        tt.tap(); t += 100
        assertEquals(300, tt.tap())
    }

    private val tickNs = (60_000_000_000.0 / (24 * 120)).toLong() // 20.833 ms

    @Test fun clockSourceNotExternalInitially() {
        assertFalse(ClockSource { 0L }.external())
    }

    @Test fun clockSourceExternalWindow() {
        var n = 1_000_000_000L
        val cs = ClockSource { n }
        cs.tick()
        n += 249_000_000; assertTrue(cs.external())
        n += 2_000_000; assertFalse(cs.external())
    }

    @Test fun clockSourceBpm120() {
        var n = 1_000_000_000L
        val cs = ClockSource { n }
        for (i in 0 until 30) { cs.tick(); n += tickNs }
        n -= tickNs
        assertEquals(120.0, cs.externalBpm()!!, 0.01)
    }

    @Test fun clockSourceBpmNullCases() {
        var n = 1_000_000_000L
        val cs = ClockSource { n }
        assertNull(cs.externalBpm())
        cs.tick()
        assertNull(cs.externalBpm())
        n += tickNs; cs.tick()
        assertNotNull(cs.externalBpm())
        n += 300_000_000
        assertNull(cs.externalBpm())
    }

    @Test fun clockSourceKeepsLast24Intervals() {
        var n = 1_000_000_000L
        val cs = ClockSource { n }
        repeat(10) { cs.tick(); n += 100_000_000 } // 25 bpm-ish old ticks
        repeat(30) { cs.tick(); n += tickNs }
        n -= tickNs
        assertEquals(120.0, cs.externalBpm()!!, 0.01)
    }

    @Test fun clockSourceForgetsPauseAfterTransportRestart() {
        var n = 1_000_000_000L
        val cs = ClockSource { n }
        repeat(5) { cs.tick(); n += tickNs }
        n += 10_000_000_000L // transport stopped for 10 s
        repeat(5) { cs.tick(); n += tickNs }
        n -= tickNs
        assertEquals(120.0, cs.externalBpm()!!, 0.01)
    }

    // --- NoteRepeat engine (no threads: external clock only) ---
    private val sent = mutableListOf<List<Int>>()
    private var nanos = 1_000_000_000L
    private val engine = PadEngine { sent += it.u() }
    // INT thread is never started here (a real one would race the manual clocks and flake tick-exact assertions)
    private val nr = NoteRepeat(engine, { nanos }, { it.run() }, { r -> Thread(r) })
    private val kick = DEFAULT_PADS[0]

    /** Runs [n] clocks; returns the tick offsets (0-based) at which a retrigger (note-on) was sent. */
    private fun clocks(n: Int): List<Int> = (0 until n).filter { i ->
        val before = sent.size; nr.externalClock(); nanos += tickNs
        sent.size > before
    }

    @Test fun repeatsOnGridAfterPressAtTick5() {
        nr.hold(true)
        clocks(5)
        engine.press(1, kick, 90); sent.clear()
        // clocks at ticks 5..24: grid lines 6 (skipped, <3 after press), 12, 18, 24
        assertEquals(listOf(7, 13, 19), clocks(20))
        assertEquals(listOf(0x89, 0x24, 0), sent[0]); assertEquals(listOf(0x99, 0x24, 90), sent[1])
    }

    @Test fun repeatPressedWhilePadHeldFiresOnNextGridLineOnly() {
        clocks(2)
        engine.press(1, kick, 100); sent.clear()
        clocks(2)
        nr.hold(true)
        assertEquals(emptyList<List<Int>>(), sent) // no immediate note
        // ticks 4..12: grid 6 is only 2 after the hold, so first repeat is 12
        assertEquals(listOf(8), clocks(9))
    }

    @Test fun notHeldNoRetriggers() {
        engine.press(1, kick, 100); sent.clear()
        assertEquals(emptyList<Int>(), clocks(30))
    }

    @Test fun releasingRepeatStops() {
        nr.hold(true); engine.press(1, kick, 100)
        nr.hold(false); sent.clear()
        assertEquals(emptyList<Int>(), clocks(30))
    }

    @Test fun rateChangeApplies() {
        nr.rate = Rate.Quarter
        nr.hold(true); engine.press(1, kick, 100); sent.clear()
        assertEquals(listOf(24), clocks(30)) // tick 24 is the only grid line in 0..29 that is >=12 after the press at 0
    }

    @Test fun startResetsTickAndSongPositionSetsIt() {
        nr.hold(true); clocks(4); nr.externalStart()
        engine.press(1, kick, 100); sent.clear()
        assertEquals(listOf(6), clocks(8)) // tick 6 fires: 6-0 >= 3
        nr.externalSongPosition(3) // tick 18
        assertEquals(listOf(0, 6), clocks(8)) // ticks 18..25: 18 and 24
    }

    @Test fun sourceAndBpmFollowExternalClock() {
        assertEquals(ClockMode.INT, nr.source)
        clocks(30)
        assertEquals(ClockMode.EXT, nr.source)
        assertEquals(120, nr.bpm)
    }

    @Test fun internalTempoShownWhenNoExternalClock() {
        nr.internalBpm = 94; nr.refresh()
        assertEquals(ClockMode.INT, nr.source)
        assertEquals(94, nr.bpm)
    }

    @Test fun refreshStartsIntWhenHeldAndExternalClockLapsed() {
        val spawned = mutableListOf<Thread>()
        val e = PadEngine { }
        val n = NoteRepeat(e, { nanos }, { it.run() }, { r -> Thread(r).also { spawned += it } }) // never started
        n.hold(true) // no external clock: INT starts
        assertEquals(1, spawned.size)
        n.refresh() // INT already running: no second thread
        assertEquals(1, spawned.size)
        n.hold(false)
        nanos += 1_000_000_000L
        n.refresh() // not held: nothing
        assertEquals(1, spawned.size)

        // Held while external clock runs, then the clock lapses.
        val e2 = PadEngine { }
        val n2 = NoteRepeat(e2, { nanos }, { it.run() }, { r -> Thread(r).also { spawned += it } })
        repeat(10) { n2.externalClock(); nanos += tickNs }
        n2.hold(true)
        assertEquals(1, spawned.size) // EXT running: no INT
        n2.refresh()
        assertEquals(1, spawned.size)
        nanos += 1_000_000_000L // Logic stopped
        n2.refresh()
        assertEquals(2, spawned.size)
        n2.refresh()
        assertEquals(2, spawned.size)
        assertEquals(ClockMode.INT, n2.source)
    }

    // --- review fixes ---
    @Test fun repeatsResumeAfterStartResetsTickWhilePadHeld() {
        nr.hold(true); clocks(300); engine.press(1, kick, 100); sent.clear()
        nr.externalStart() // tick -> 0 while the pad's press tick is still 300
        assertEquals(listOf(6, 12), clocks(13))
    }

    @Test fun repeatsResumeAfterSongPositionBackwardsWhilePadHeld() {
        nr.hold(true); clocks(300); engine.press(1, kick, 100); sent.clear()
        nr.externalSongPosition(1) // tick 6
        assertEquals(listOf(6), clocks(7)) // ticks 6..12: only 12 (6 is not >= 3 after the clamped press)
    }

    @Test fun missedTicksPure() {
        val p = 20L
        assertEquals(0L, missedTicks(1000, 1000, p))
        assertEquals(0L, missedTicks(1000 + p, 1000, p)) // exactly one period behind: not a stall
        assertEquals(0L, missedTicks(900, 1000, p)) // early
        assertEquals(2L, missedTicks(1000 + p + 1, 1000, p))
        assertEquals(4L, missedTicks(1000 + 3 * p + 10, 1000, p))
        // the new deadline after skipping is always in the future and on the grid
        for (late in listOf(21L, 40L, 41L, 100L, 1234L)) {
            val m = missedTicks(1000 + late, 1000, p)
            assertTrue(1000 + m * p > 1000 + late); assertTrue(1000 + (m - 1) * p <= 1000 + late)
        }
    }

}
