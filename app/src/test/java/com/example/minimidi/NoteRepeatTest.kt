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
}
