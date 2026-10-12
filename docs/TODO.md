# MiniMidi to-do

v1 merged to `main` on 2026-10-01. It is verified on a Galaxy Z Fold5, and the end-to-end check passed 16/16 pads to Logic.

## Next features (committed follow-ups from the spec and plan)

- [x] **Per-pad remap** (note and channel) through an explicit edit mode, with MIDI learn; `PadMap` persisted (2026-10-11).
- [x] **Banks** A–F: shift all pads by 16 notes (2026-10-11).
- [ ] **MPC-style A–D banks**: four independent 16-pad maps instead of one offset map. Saved format `padmap.v1` would become per-bank.
- [ ] **Wireless MIDI**: undecided between Network MIDI (RTP-MIDI over Wi-Fi; likely lower latency, needs the same network) and BLE MIDI (GATT server with the phone as peripheral; works anywhere, ~10–20 ms and jitter set by the Mac's connection interval). Both sit beside `MidiOut`. Before building, feel the latency with an existing app that does both, and decide by why wireless is wanted.

## Candidate features (not yet committed)

Several of these need **MIDI input** (receiving from the DAW; the port and `MidiParser` exist since 2026-10-11, parser needs clock support): clock for note repeat, notes for pad lighting. If note repeat is wanted, plan MIDI input before Bluetooth MIDI, since both build on the same MIDI port work.

Worth adding:

- [x] **Note repeat / roll** (2026-10-11): hold REPEAT + pads; 1/4..1/32T; synced to MIDI clock (e2e: 59 repeats within 2.7 ms of the Mac's grid), internal tempo + tap tempo otherwise.
- [ ] **Note repeat latch**: double-tap REPEAT to latch.
- [ ] **Full Level**: a quick toggle on the main screen that forces velocity 127.
- [ ] **Velocity curves** (soft, linear, hard): could replace or sit beside sensitivity. Most useful once area velocity works.
- [ ] **Choke groups**: one pad cuts another (closed hi-hat cuts open), with the controller sending the note-off.
- [ ] **Aftertouch / pressure** from contact size while held. Experimental, since Fold5 contact data is coarse.

Nice to have:

- [ ] **16 Levels**: one sound across all 16 pads at stepped velocity. Do it after banks.
- [ ] **Pad-to-CC / program-change mode**, so pads can act as control-surface buttons.
- [ ] **Kit presets**: save and recall pad maps, for example GM drums or a Drum Rack starting at C1 (note 36).
- [ ] **Pad lighting from the DAW**: light pads from incoming notes. Needs MIDI input.
- [ ] **Swing / quantize**: only if note repeat is added; otherwise leave it to the DAW.

Skipped: on-device sounds or a sampler, a step sequencer, and an XY pad or touch strip. These are out of scope for a pad controller.

## Known limitations

- [ ] **Note repeat sends one extra note-off** per hold (e2e saw 61 offs for 60 ons). Harmless, but find where it comes from.
- [ ] The note-repeat DAW-echo guard (100 ms after own presses, for learn) and the INT takeover after Logic stops are unit-tested only, not on hardware.

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
- [x] Switch rows in settings report `checked=false` to TalkBack regardless of state. Use `Modifier.toggleable`.
- [x] Give the settings scrim a click label ("Close settings") for TalkBack.
- [x] Have `tools/e2e_midi.py` assert:
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
- adb over Wi-Fi (paired 2026-10-11): `ANDROID_SERIAL=adb-RFCW709RGRH-XxXrqF._adb-tls-connect._tcp` (mDNS name, survives port changes). Never start the adb server inside the sandbox: it can't reach the LAN. If pairing/connect fails with "protocol fault", `! adb kill-server`.
- USB MIDI mode can be set remotely: `adb shell svc usb setFunctions midi`.
- The offline `emulator-5562` is Native Instruments' NTKDaemon on port 5563, not an emulator. Hide it with `export ADB_LOCAL_TRANSPORT_MAX_PORT=5561`.
- Run `tools/e2e_midi.py` outside the sandbox, because CoreMIDI is blocked inside it.

## Separate future app (not MiniMidi)

- [ ] **Phone numpad**: reuse the pad design as a Bluetooth HID numpad (`BluetoothHidDevice`, Android 9+). Stock Android can't act as a USB HID device without root.
