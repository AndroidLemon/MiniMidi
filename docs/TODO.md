# MiniMidi to-do

v1 merged to `main` on 2026-10-01. It is verified on a Galaxy Z Fold5, and the end-to-end check passed 16/16 pads to Logic.

## Next features (committed follow-ups from the spec and plan)

- [ ] **Per-pad remap** (note and channel) through an explicit edit mode; persist `PadMap`.
- [ ] **Banks**: shift all pads ±16 notes.
- [ ] **Bluetooth MIDI output**: a BLE MIDI GATT server with the phone as peripheral, added beside `MidiOut`.

## Known limitations

- [ ] **Area velocity doesn't work on the Fold5.** Samsung reports touch contact of only about 1.6–3.3 dp, against `velocity()`'s 25 dp floor, so every hit plays at velocity 20. For now, play in Fixed mode. The full diagnosis is in `docs/notes/2026-09-30-feel-and-artemis.md`. Fix options:
  - retune the floor and full-scale range from 10 soft and 10 hard sample taps; or
  - self-calibrate per device.
- [ ] **Haptics feel faint.** The primitive click plays, but it's barely felt. You're fine with it; revisit only if it matters.

## Experiments

- [ ] **ARTEMIS local-model UI test** (Task 10, steps 2–4):
  - Clone `google/artemis`.
  - Serve `qwen2.5vl:7b` through Ollama (about 6 GB).
  - Run four flows: tap pad 1; set Fixed velocity near 64; set sensitivity to 8; turn on big pads.
  - Compare against a larger model if the 7B one fails.
  - Record the results in the notes file.

## Unverified on device

- [ ] Hold a pad and **fold mid-beat**: confirm no stuck note.
- [ ] Hold a pad and **press Home**: confirm no stuck note.

## Polish and cleanup (minor review findings)

- [ ] Replace the template **launcher icon**. It's the green Android robot with a gradient, which breaks the "no gradients" rule, and it also shows on the splash screen.
- [ ] Switch rows in settings report `checked=false` to TalkBack regardless of state. Use `Modifier.toggleable`.
- [ ] Give the settings scrim a click label ("Close settings") for TalkBack.
- [ ] Have `tools/e2e_midi.py` assert:
  - velocity equals the fixed value (100);
  - the note_off channel;
  - and print adb's stderr on failure.
- [ ] Remove unused version-catalog entries (navigation3, kotlin-serialization, lifecycle, androidx-test, coroutines-test).
- [ ] Optional test gaps:
  - MIDI out-of-range note and velocity cases;
  - the `contactDp == 1f` boundary;
  - NaN in `hitTest`.

## Dev environment notes

- Gradle: run a bare `./gradlew --offline -g .gradle-home …`. The sandbox excludes `./gradlew *`.
- adb: set `ANDROID_SERIAL=RFCW709RGRH`. A stale offline `emulator-5562` is still registered.
- Run `tools/e2e_midi.py` outside the sandbox, because CoreMIDI is blocked inside it.

## Separate future app (not MiniMidi)

- [ ] **Phone numpad**: reuse the pad design as a Bluetooth HID numpad (`BluetoothHidDevice`, Android 9+). Stock Android can't act as a USB HID device without root.
