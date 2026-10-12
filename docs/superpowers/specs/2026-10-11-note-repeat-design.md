# Note repeat, synced to MIDI clock

Date: 2026-10-11. Builds on the MIDI input from `2026-10-11-remap-banks-learn-design.md`. The task list at the end is the plan.

## Goal

The user holds a REPEAT button with one thumb, and every pad held with the other hand retriggers at a chosen rate. Repeats stay in time with Logic when it sends MIDI clock, and with the phone's own tempo when it doesn't. The first hit plays the moment the pad is touched; later repeats land on the tempo grid.

## Timing model

- **One tick counter at 24 ticks per quarter note (MIDI clock rate) drives both clock sources.** Rates and their ticks per repeat (`Rate` enum, in this order):

  | rate | ticks |
  |---|---|
  | 1/4 | 24 |
  | 1/8 | 12 |
  | 1/8T | 8 |
  | 1/16 | 6 (default) |
  | 1/16T | 4 |
  | 1/32 | 3 |
  | 1/32T | 2 |

- **External clock (EXT):**
  - `0xF8` increments the counter. This runs on the MIDI receive thread.
  - Start `0xFA` sets the counter to 0.
  - Song Position Pointer `0xF2 lsb msb` sets it to `sixteenths * 6`. A following Continue `0xFB` resumes from that position.
  - Stop `0xFC` stops the ticks; nothing resets.
- **Internal clock (INT):** a dedicated thread produces ticks from the phone's tempo using `System.nanoTime()` deadlines and `LockSupport.parkNanos`, so timing doesn't drift. It runs only while REPEAT is held, and its counter starts at 0 when REPEAT is pressed.
- **Picking the source:** EXT if an external tick arrived in the last 250 ms, otherwise INT. INT starts only while REPEAT is held and EXT is absent. When EXT arrives while INT is running, INT stops and ticks come from EXT from then on.
  - EXT tempo for display = `60 / (24 * mean tick interval)` over the last 24 ticks.
- **When a repeat fires (pure function):** `fires(tick, pressTick, ticksPerRepeat)` is true when `tick % ticksPerRepeat == 0` and `tick - pressTick >= ticksPerRepeat / 2`, using integer division for ticks.
  - The first rule puts repeats on the grid.
  - The second rule skips a grid line that comes less than half a division after the touch, which avoids a double hit.
  - When REPEAT is pressed while pads are already held, those pads get `pressTick` = the tick at the REPEAT press. They fire on the next grid line, with no immediate extra note.
- **Repeat = retrigger:** the new `PadEngine.retrigger(pointerId)` sends note-off then note-on for the pad stored for that pointer, at the velocity it was first pressed with (`press` stores it now). Lifting a finger, `releaseAll` and `panic` keep their current stuck-note guarantees.
  - All `PadEngine` methods become `@Synchronized`, because they're now called from the main thread, the MIDI thread and the INT thread.
- **Releasing REPEAT** stops repeating. Held pads stay held, with their last note still on, until the finger lifts, as in play without repeat.
- **No haptics on repeats.** Haptics stay on real touches only.

## Components

- **`MidiParser`:** add optional callbacks `onClock()`, `onStart()`, `onContinue()`, `onStop()`, `onSongPosition(sixteenths)`, all defaulting to no-op.
  - Real-time bytes are still allowed in the middle of a message, and they now produce their callback without disturbing the partial message.
  - `0xF2` is parsed with its 2 data bytes. Other system common messages behave as before.
- **`NoteRepeat.kt` (new):**
  - `Rate`;
  - the pure `fires`;
  - `TapTempo` (mean of the last 4 tap intervals; a gap over 2 s starts a new set; result clamped to 30..300 BPM);
  - the clock-source state (last tick time, tick interval history, EXT tempo);
  - the INT thread;
  - the per-pointer press ticks.

  It takes an injectable time source so the logic can be unit-tested. It exposes Compose-observable `source` (EXT/INT) and `bpm` for the UI. It updates them at most about 4 times a second, so they don't cause a recomposition storm.
- **`MidiOut`:** feeds the clock callbacks to `NoteRepeat` on the MIDI thread. Clock handling is not posted to main. The note-to-main path for learn stays as it is.
- **`PadEngine`:** `@Synchronized`, `retrigger`, a stored velocity per pointer, and a hook so `NoteRepeat` learns press ticks and held pointers.

## UI

- **A new row above the existing bottom strip:**
  - `[ REPEAT ]`: a hold button built on press/release pointer input, not click. Pressing it calls `NoteRepeat.hold(true)` and lifting or cancelling calls `hold(false)`. It's 48 dp tall and visibly lit while held.
  - Rate label (`1/16`): a tap cycles to the next rate. 48 dp target, contentDescription "Repeat rate 1/16".
  - Tempo readout `EXT 120` / `INT 94`: a tap is a tap-tempo tap, applied to the internal tempo, and ignored while EXT. contentDescription "Tempo".
- **Settings panel:** an "Internal tempo" slider from 30 to 300.
- **Saved:** rate and internal tempo, using the existing Settings save on pause.
- **The pad grid's touch handling doesn't change,** apart from telling `NoteRepeat` about presses through the `PadEngine` hook. The REPEAT row is outside the grid, and multi-touch across the two works because Compose sends each pointer to the element under it.
- Edit mode hides the REPEAT row: no repeating while editing.

## Out of scope

Swing, accents, latch (could later be a double-tap on REPEAT), sending clock out, and repeats over a wireless link.

## Testing

- **Unit tests:**
  - parser: clock, start, stop, continue, SPP, and a clock byte in the middle of a note-on;
  - `fires` for every rate, including the half-division skip right before a grid line and the REPEAT-pressed-while-held case;
  - `TapTempo` averaging, the 2 s reset and the clamp;
  - choosing EXT/INT with a fake clock, and the EXT tempo from tick intervals;
  - `PadEngine.retrigger` sends off-then-on at the stored velocity and does nothing for an unknown pointer.
- **End to end (`tools/e2e_midi.py repeat`, run by the user):** `adb input` can't hold two fingers, so this pass is partly manual.
  1. The script sends Start and then clock at 120 BPM to the phone.
  2. It prints "Hold REPEAT and pad 1 for about 3 s now".
  3. It records note-ons for 5 s and sends Stop.
  4. It asserts at least 8 note-ons for note 36. With the default rate of 1/16, each note-on after the first must land on a 6-tick boundary of the Mac's own count, within 1 tick.
  5. It prints a timing summary: mean and max deviation in ms.

## Tasks

1. **Parser clock + pure logic:**
   - `MidiParser` callbacks;
   - in `NoteRepeat.kt`, `Rate`, `fires`, `TapTempo`, and the source selection with an injectable clock;
   - unit tests first; each red run must be seen before implementing.

   — sonnet
2. **Engine wiring:**
   - `PadEngine` synchronized, `retrigger`, stored velocity and the press hook, with tests;
   - in `NoteRepeat`, the INT thread, the tick counter and EXT handling;
   - `MidiOut` sends clock to `NoteRepeat`.

   — sonnet
3. **UI:** REPEAT row, rate cycling, tap tempo, settings slider, persistence, hiding the row in edit mode. Then a device check over Wi-Fi adb. — sonnet
4. **E2E:** the `repeat` pass in `tools/e2e_midi.py`. — sonnet
5. **Docs:** update `TODO.md`.

After task 4, Opus reviews the whole diff, with special attention to threading and timing.
