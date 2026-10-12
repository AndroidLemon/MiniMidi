# Per-pad remap, banks and MIDI learn

Date: 2026-10-11. Follows the v1 spec (`2026-09-30-minimidi-design.md`). No separate plan doc: the task list at the end is the plan.

## Goal

The user can set each pad's note and channel, shift the whole kit up and down in banks of 16, and assign a pad by playing a note on the Mac (MIDI learn). Banks are one offset applied over one map. Independent MPC-style A–D maps are deferred to the to-do list.

Precondition, verified 2026-10-11 on the Fold5 in USB MIDI mode: `dumpsys midi` shows `Android USB Peripheral Port` with `mInputPortCount=1, mOutputPortCount=1`, so the phone can receive MIDI from the Mac.

## Data model (`PadMap.kt`)

- `Pad(note, channel)`. `label` leaves the data class; labels are derived. `channel` stays 1-based.
- `DEFAULT_PADS`: notes 36..51, channel 10 (unchanged behaviour).
- `padLabel(note, channel)`: the GM drum name on channel 10 for notes 35..81 (BD, SD, CH…); otherwise the note name with C1 = 36, e.g. `D#2`.
- Stored notes are relative to bank A. `played(pad, bank): Pad?` returns the pad with `note + bank*16`, or null if that note is above 127. A null pad is drawn dimmed and doesn't play.
- Banks: `0..5`, shown as A–F.
- `learnedNote(incoming, bank): Int?` = `incoming - bank*16`, or null if that's below 0. Learning stays armed when it's null.
- Persistence: key `padmap.v1`, value `"36:10,37:10,…"` (16 entries of `note:channel`). Future per-pad fields (choke group, CC mode) append `:x` per entry. If the string doesn't decode (wrong count, out of range, junk), the app falls back to `DEFAULT_PADS`. Bank is saved under `bank`, clamped to 0..5.
- The map and bank are saved on every change (taps, not drags, so this is cheap). Settings keep their onPause save.

## MIDI input (`MidiParser.kt`, `MidiOut.kt`)

- `MidiParser` is plain Kotlin: `feed(bytes, offset, count)` calls `onNoteOn(channel1Based, note, velocity)` for each note-on with velocity > 0.
  - It handles running status.
  - Real-time bytes (`0xF8..0xFF`) are ignored even in the middle of a message, and they don't reset running status.
  - Sysex (`0xF0..0xF7`) is skipped.
  - Other channel messages are consumed with the right data length (2, or 1 for `0xC0`/`0xD0`) and dropped.
  - System common messages clear running status.
- `MidiOut` also opens the same USB device's output port 0 and connects a `MidiReceiver` that feeds a parser. Each note-on is posted to the main thread as an `onNote` callback. `inputConnected` is Compose state.
  - If the output port is null (another app holds it), output still works and `inputConnected` stays false.
  - `close()` disconnects and closes the input side too.
  - The class keeps its name; its doc comment now says it works in both directions.

## UI (`PadScreen.kt`, `MainActivity.kt`)

- **Status bar:** `◀ A ▶` bank control next to the wordmark, with 48 dp targets and the content descriptions "Previous bank" and "Next bank". Changing bank calls `engine.releaseAll()` first.
- **Bottom strip:** an `EDIT` label at the left. Velocity and SENS stay, and tapping elsewhere still opens settings.
- **Edit mode:** a panel docks below the grid, and the grid takes the remaining height.
  - Pads still play. Tapping one also selects it and gives it a white outline.
  - Panel: `PAD n · LABEL`, `NOTE − <name·number> +`, `OCT − +`, `CH − n +`, `LEARN` (shown only when `inputConnected`), `RESET PAD`, `DONE`. Before a pad is selected, the panel says "Tap a pad".
  - NOTE shows the played note, with the bank applied. Steppers edit the stored note, clamped to 0..127; CH is clamped to 1..16. Steppers are silent.
  - LEARN arms the selected pad and the pad outline blinks. The next `onNote` assigns `learnedNote` and the channel, then disarms. LEARN again, selecting another pad, or DONE cancels.
  - The settings panel can't be opened while in edit mode.
- **Pad cells** show `padLabel` of the played pad. A pad that's null on this bank is drawn at 30% and its touches are ignored.
- **Panic in onPause** uses the channels from the current map, not `DEFAULT_PADS`.
- **Accessibility polish** (from the to-do list):
  - settings switch rows use `Modifier.toggleable(role = Switch)`;
  - the scrim gets `onClickLabel = "Close settings"`;
  - the new steppers have content descriptions such as "Note up", "Channel down".

## End to end (`tools/e2e_midi.py`)

- Existing pass, plus the to-do items: assert velocity == 100 in Fixed mode, assert the note-off channel, print adb's stderr when an adb call fails.
- Learn pass:
  1. Tap EDIT, pad 1, LEARN.
  2. Send note 60 on channel 2 from the Mac to the phone's port.
  3. Tap DONE, then tap pad 1, and expect note 60 on channel 2.
  4. Restore pad 1: EDIT, pad 1, RESET PAD, DONE.
- Bank pass: tap ▶, tap pad 1, expect 52 on ch 10; tap ◀.
- The phone's USB mode is set with `adb shell svc usb setFunctions midi`, which works over Wi-Fi adb.

## Testing (unit)

- `MidiParserTest`:
  - a plain note-on;
  - running status;
  - `0xF8` inside a message;
  - sysex followed by a note;
  - velocity 0 ignored;
  - program change followed by a note;
  - a message split across two `feed` calls.
- `PadMapTest`:
  - encode/decode round trip;
  - bad strings fall back to the default;
  - `played` returns null above 127;
  - `learnedNote` is relative to the bank and null below 0;
  - labels (GM name, note name, non-10 channel).
- Existing `PadEngineTest` constructors are updated for `Pad` without `label`. This changes no assertions.

## Out of scope

Pad lighting from the DAW, kit presets, choke groups, pad-to-CC, 16 Levels, MPC A–D banks (added to the to-do list), note repeat/clock.

## Tasks

1. **Model:** `PadMap.kt` (Pad without label, `padLabel`, `played`, `learnedNote`, encode/decode) and `PadMapTest`; update the `PadEngineTest` constructors. — sonnet
2. **Parser:** `MidiParser.kt` and `MidiParserTest`. — sonnet
3. **Input:** the input side of `MidiOut` (output port, receiver, `onNote`, `inputConnected`). — sonnet
4. **UI:** bank control, edit mode, learn, persistence wiring in `MainActivity`, panic channels, accessibility polish. Then a device check over Wi-Fi adb. — sonnet
5. **E2E:** extend `tools/e2e_midi.py` (learn, bank, asserts, stderr). The user runs it with `!`. — sonnet
6. **Docs:** in `TODO.md`, check off the done items, add "MPC-style A–D banks", update the dev notes (Wi-Fi adb, mDNS serial, NTKDaemon/`ADB_LOCAL_TRANSPORT_MAX_PORT`).

After task 5, Opus reviews the whole diff.
