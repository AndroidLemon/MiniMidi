package com.example.minimidi

/** Repeat rates, in 24-per-quarter MIDI clock ticks per repeat. */
enum class Rate(val label: String, val ticks: Int) {
    Quarter("1/4", 24), Eighth("1/8", 12), EighthT("1/8T", 8), Sixteenth("1/16", 6),
    SixteenthT("1/16T", 4), ThirtySecond("1/32", 3), ThirtySecondT("1/32T", 2)
}

fun Rate.next(): Rate = Rate.values()[(ordinal + 1) % Rate.values().size]

/** A repeat lands on the tick grid, skipping a grid line less than half a division after the press. */
fun fires(tick: Long, pressTick: Long, ticksPerRepeat: Int): Boolean =
    tick % ticksPerRepeat == 0L && tick - pressTick >= ticksPerRepeat / 2

/** BPM from the mean of the last up to 4 tap intervals; a gap over 2 s starts a new set. Not thread-safe. */
class TapTempo(private val now: () -> Long) {
    private val taps = ArrayDeque<Long>()

    fun tap(): Int? {
        val t = now()
        if (taps.isNotEmpty() && t - taps.last() > 2000) taps.clear()
        taps.addLast(t)
        if (taps.size > 5) taps.removeFirst()
        if (taps.size < 2) return null
        val mean = (taps.last() - taps.first()).toDouble() / (taps.size - 1)
        return Math.round(60000.0 / mean).toInt().coerceIn(30, 300)
    }
}

/** Tracks external clock ticks (24 per quarter): whether they are arriving and their tempo. Not thread-safe. */
class ClockSource(private val nowNanos: () -> Long) {
    private val times = ArrayDeque<Long>() // last 25 tick times = 24 intervals

    fun tick() {
        times.addLast(nowNanos())
        if (times.size > 25) times.removeFirst()
    }

    fun external(): Boolean = times.isNotEmpty() && nowNanos() - times.last() <= 250_000_000L

    fun externalBpm(): Double? {
        if (times.size < 2 || !external()) return null
        val meanSeconds = (times.last() - times.first()) / 1e9 / (times.size - 1)
        return 60.0 / (24 * meanSeconds)
    }
}
