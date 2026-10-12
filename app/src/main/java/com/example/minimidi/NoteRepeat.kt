package com.example.minimidi

import android.os.Handler
import android.os.Looper
import android.os.Process
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.concurrent.locks.LockSupport

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
        val t = nowNanos()
        if (times.isNotEmpty() && t - times.last() > 250_000_000L) times.clear() // transport restarted: don't average in the pause
        times.addLast(t)
        if (times.size > 25) times.removeFirst()
    }

    fun external(): Boolean = times.isNotEmpty() && nowNanos() - times.last() <= 250_000_000L

    fun externalBpm(): Double? {
        if (times.size < 2 || !external()) return null
        val meanSeconds = (times.last() - times.first()) / 1e9 / (times.size - 1)
        return 60.0 / (24 * meanSeconds)
    }
}

/**
 * INT-loop catch-up: when [now] is more than one [period] past the next [deadline] (the thread stalled), how many grid
 * ticks to skip so the deadline lands on the first grid line after [now]; 0 when not that far behind.
 */
fun missedTicks(now: Long, deadline: Long, period: Long): Long =
    if (now - deadline > period) (now - deadline) / period + 1 else 0

enum class ClockMode { EXT, INT }

/**
 * Retriggers held pads on the tick grid (24 ticks per quarter) while REPEAT is held.
 * Threads: the main thread calls hold/stop/refresh, the MIDI thread calls external*(), the INT thread calls advance().
 * One lock ([lock]) guards tick, pressTicks, held, clock and the INT thread handle; it is always taken before the PadEngine's own lock
 * (advance -> engine.retrigger), and PadEngine.onPress runs before the engine lock, so the order is never reversed.
 * [source] and [bpm] are Compose state for the UI: written on the main thread via [post], at most every 250 ms from the tick path
 * (plus on hold/stop/external stop and [refresh], which a UI may poll while idle).
 */
class NoteRepeat(
    private val engine: PadEngine,
    private val nowNanos: () -> Long = System::nanoTime,
    private val post: (Runnable) -> Unit = { Handler(Looper.getMainLooper()).post(it) },
    private val spawn: (Runnable) -> Thread = { r -> Thread(r, "note-repeat-int").apply { isDaemon = true; start() } },
) {
    @Volatile var rate = Rate.Sixteenth
    @Volatile var internalBpm = 120

    var source by mutableStateOf(ClockMode.INT)
        private set
    var bpm by mutableIntStateOf(120)
        private set

    private val lock = Any()
    private val clock = ClockSource(nowNanos)
    private val pressTicks = HashMap<Int, Long>()
    private var held = false
    private var tick = 0L
    @Volatile private var intGen = 0 // bumped to stop the running INT thread; 0..: thread g runs only while intGen == g
    private var intThread: Thread? = null
    private var lastUiNanos = 0L

    /** Current clock position in ticks (for tests). */
    internal val position: Long get() = synchronized(lock) { tick }

    init { engine.onPress = ::onPress }

    fun onPress(pointerId: Int) { synchronized(lock) { pressTicks[pointerId] = tick } }

    fun hold(down: Boolean) = synchronized(lock) {
        held = down
        if (down) {
            engine.heldPointers().forEach { pressTicks[it] = tick }
            // INT counts from 0 at the REPEAT press; an already-running EXT clock keeps its own count.
            if (!clock.external()) startIntFromZero()
        } else stopInt()
        refreshLocked()
    }

    /** App leaving the foreground: stop INT, clear held. */
    fun stop() = synchronized(lock) { held = false; stopInt(); refreshLocked() }

    // External clock: all on the MIDI thread.
    fun externalClock() = synchronized(lock) {
        stopInt() // EXT wins over INT as soon as it arrives
        clock.tick()
        advance()
        throttledRefresh()
    }
    fun externalStart() = synchronized(lock) { setTick(0) }
    fun externalContinue() {} // ticks keep counting from where Start/SPP/Stop left them
    fun externalStop() = refresh()
    fun externalSongPosition(sixteenths: Int) = synchronized(lock) { setTick(sixteenths * 6L) }

    // Moving the clock position backwards must not leave held pads with press ticks in the future (fires() would stay false).
    private fun setTick(t: Long) {
        tick = t
        pressTicks.replaceAll { _, v -> minOf(v, t) }
    }

    /** Recomputes [source] and [bpm] now. Cheap; a UI may poll it so a vanished external clock shows as INT. */
    fun refresh() = synchronized(lock) {
        // REPEAT held, external clock gone (Logic stopped) and INT idle: carry on at the phone's tempo.
        if (held && intThread == null && !clock.external()) startIntFromZero()
        refreshLocked()
    }

    private fun startIntFromZero() {
        tick = 0
        engine.heldPointers().forEach { pressTicks[it] = 0 }
        startInt()
    }

    // Callers hold [lock]. Processes the clock position `tick`, then moves to the next one.
    private fun advance() {
        if (held) for (id in engine.heldPointers()) {
            if (fires(tick, pressTicks[id] ?: tick, rate.ticks)) engine.retrigger(id)
        }
        tick++
    }

    private fun throttledRefresh() {
        val n = nowNanos()
        if (n - lastUiNanos >= 250_000_000L) refreshLocked()
    }

    private fun refreshLocked() {
        lastUiNanos = nowNanos()
        val ext = clock.external()
        val s = if (ext) ClockMode.EXT else ClockMode.INT
        val b = (if (ext) clock.externalBpm()?.let { Math.round(it).toInt() } else null) ?: internalBpm
        post(Runnable { source = s; bpm = b })
    }

    private fun stopInt() {
        intGen++
        intThread?.let { LockSupport.unpark(it) }
        intThread = null
    }

    private fun startInt() {
        stopInt()
        val g = intGen
        intThread = spawn(Runnable {
            runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO) }
            var deadline = System.nanoTime()
            while (true) {
                while (true) { // park until the deadline, bailing out as soon as we're stopped
                    val rem = deadline - System.nanoTime()
                    if (rem <= 0 || intGen != g) break
                    LockSupport.parkNanos(rem)
                }
                synchronized(lock) {
                    if (intGen != g) return@Runnable
                    advance()
                    throttledRefresh()
                }
                val period = (60_000_000_000.0 / (internalBpm.coerceIn(30, 300) * 24)).toLong()
                deadline += period
                // After a stall, don't replay every missed tick back-to-back: advance the count silently, keep the grid phase.
                val skip = missedTicks(System.nanoTime(), deadline, period)
                if (skip > 0) {
                    synchronized(lock) {
                        if (intGen != g) return@Runnable
                        tick += skip
                    }
                    deadline += skip * period
                }
            }
        })
    }
}
