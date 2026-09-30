# MiniMidi — Design Spec

**Date:** 2026-09-30
**Status:** Draft for review

## Purpose

A pocket MIDI drum pad for an Android foldable. Plug the phone into a Mac, and any DAW (Logic Pro first, but Ableton/FL/Reaper/Bitwig equally) sees it as a class-compliant MIDI controller. It must feel like a real instrument, not an app.

**Success:** Phone plugged in via USB → appears in the DAW's MIDI inputs with no drivers → tapping a pad triggers a drum sound with no perceptible delay, velocity follows how hard you hit, and folding/unfolding mid-session never interrupts playing.

## Scope

### v1 (this spec)
- Native Android app, Kotlin + Jetpack Compose, single screen.
- USB MIDI output only (phone in Android's USB "MIDI" mode).
- 16 pads, fixed MPC-style mapping: pad 1 = note 36 … pad 16 = note 51, MIDI channel 10.
- Velocity from touch contact area, with a sensitivity slider and a fixed-velocity toggle.
- Fold-adaptive layout: 4×4 on both screens; optional "big pads when folded" (8 pads, 2×4) on the cover screen.
- Visual direction **C · Rhythm** (TR-808 lineage).

### Committed follow-ups (not v1, but v1 must not block them)
- **Per-pad remapping** (note + channel), via an explicit edit mode.
- **Banks** (shift all pads by 16 notes).
- **Bluetooth MIDI** output (phone as BLE MIDI *peripheral*; needs a custom GATT server, since Android's `MidiManager` only acts as BLE central).

### Out of scope
- iOS, other DAW integrations beyond plain MIDI, audio output on the phone, sample playback.
- A numpad/HID mode — noted as a possible *separate* future app.

## Architecture

One app module, four units. Call path on every touch is synchronous: no queues, threads, or coroutines between touch and MIDI send.

| Unit | Responsibility | Depends on |
|---|---|---|
| `PadMap` | Table of 16 `(note, channel)` entries. Default: notes 36–51, channel 10. Plain data, so remapping and banks later only edit the table. | nothing |
| `Velocity` | Pure function: `(contactSize, sensitivity, fixedMode, fixedValue) → 1..127`. | nothing (no Android types) |
| `MidiOut` | Finds the USB peripheral MIDI port via `MidiManager`, opens its input port, sends note-on/off and CC bytes; reports connected/disconnected. The single seam the future Bluetooth output plugs into. | Android `MidiManager` |
| `PadScreen` | Compose UI: grid, multi-touch handling, pad lighting, haptics, settings strip, status LED. | the three above |

Android note: in USB MIDI mode the host connection appears to the app as a MIDI device whose **input** port is what the app writes to.

## Data flow

**Finger down** (`ACTION_DOWN` / `ACTION_POINTER_DOWN`, per pointer ID):
1. Hit-test the pad under the pointer.
2. `Velocity` from `MotionEvent.getSize()`/`getTouchMajor()` and current settings.
3. Look up `(note, channel)` in `PadMap`; send note-on.
4. Light the pad in the same frame; fire haptic.
5. Store `pointerId → (note, channel)`.

**Finger up / cancel:** send note-off for the `(note, channel)` **stored at finger-down**, not the pad's current mapping. This keeps notes from sticking when banks/remaps/fold state change mid-hit.

**Rules:**
- A pad fires only where a finger lands. Sliding onto another pad does not trigger it.
- Each pointer is independent; two fingers on one pad retrigger it.
- Velocity is clamped to **1–127** (a note-on with velocity 0 is a note-off in MIDI).

## Feel & UX

Principles drawn from Apple's HIG (direct manipulation, immediate feedback, deference, clarity, meaningful haptics) and from MPC/Maschine, Ableton Push, Teenage Engineering and Koala Sampler.

1. **Trigger on finger-down**, never on release.
2. **No Material ripple.** The pad lights instantly (brightness scaled by velocity); only the release animates, with a ~150 ms fade.
3. **Haptics:** `VibrationEffect` `PRIMITIVE_CLICK` scaled by velocity, with an off switch (the motor is audible on quiet takes).
4. **Full-bleed play surface:** immersive mode, keep-screen-on, and system-gesture exclusion over edge pads so hitting a corner pad doesn't trigger Back. Android caps exclusion at 200 dp per edge; implementation must verify pads near the edges are covered and choose which ones to exclude if not.
5. **Nothing on the play surface does anything except play:** no long-press, no scrolling. Future remapping uses a separate edit mode.
6. **One status LED** for the MIDI connection, like a power light — no toasts or dialogs.

### Anti-"generated" rules
Avoid: gradients, glassmorphism, soft cards with drop shadows, default system/Inter type everywhere, emoji icons, Material dynamic colour, generous padding, taglines. Every visual choice must be justifiable as "hardware does this".

### Visual direction — C · Rhythm
- Near-black body (`#111`), cream text (`#e9e4d8`).
- Pads colour-grouped by row, Push-style: row 1 (bottom) red `#c8321f`, row 2 orange `#e2701f`, row 3 yellow `#e9c22c`, row 4 cream `#e9e4d8`.
- Pad number top-left, short instrument label bottom-right (BD, BD2, SD, CP / RS, LT, CH, MT / OH, HT, CY, RD / P1, P2, P3, FX), in a condensed sans (Archivo Narrow); wordmark in heavy italic (Archivo).
- Lit pad: brighter + saturated, with a thin inset highlight. Tight ~4–6 dp gutters, small corner radius (~3 dp).
- Pad order runs bottom-left (pad 1) to top-right (pad 16), MPC style.
- Reference mockups: `.superpowers/brainstorm/*/content/visual-direction.html`, `cover-layout.html`.

## Layout & fold behaviour

- **Unfolded:** 4×4 grid filling the screen, thin status bar on top (wordmark, LED, "USB · 10"), thin settings strip at the bottom.
- **Folded (cover screen), default:** the same 4×4, pads taller and narrower (~12 × 32 mm on a Z Fold). Positions are identical to unfolded so muscle memory carries over.
- **Folded, "big pads when folded" setting on:** 2×4 grid showing pads 1–8 only. Implemented as parameters (`columns`, `padCount`) on the same grid composable, not a second layout. The setting has no effect unfolded.
- Fold changes must **not** recreate the Activity. The manifest declares `configChanges` for `screenSize|smallestScreenSize|screenLayout|orientation`; Compose re-lays out, and held notes stay held.
- Layout selection follows the Android `adaptive` skill's guidance (window size classes).

## Settings

The bottom strip shows only the current velocity mode and sensitivity (e.g. "Velocity: Area · Sens 4"). Tapping it opens a panel over the bottom of the screen containing all settings. Pads are inactive while the panel is open, so adjusting settings never triggers a note.

Persisted in `SharedPreferences`: sensitivity, fixed-velocity toggle, fixed-velocity value, haptics on/off, big-pads-when-folded. `PadMap` is a code constant in v1; it gets persistence when remapping lands.

## Error handling & lifecycle

- **No MIDI port** (USB not in MIDI mode, or no cable): LED off, one line of text "USB → MIDI". Pads still light and play haptics (practice mode); nothing is sent.
- **Cable unplugged mid-play:** `MidiManager.DeviceCallback.onDeviceRemoved` → LED off. Plugging back in reconnects automatically via `onDeviceAdded`.
- **App paused** (home, call, screen off): send **All Notes Off (CC 123)** on every channel in use, then clear the pointer map.
- A failed send never crashes the app. It marks the connection as down.

## Testing

1. **JVM unit tests** (no device):
   - `Velocity`: clamps to 1–127, tiny contact never yields 0, sensitivity changes the mapping monotonically, fixed mode ignores contact.
   - MIDI encoding: pad 1 on channel 10 → `99 24 vv`; note-off → `89 24 00`; CC 123 bytes.
   - Pointer tracking: note-off uses the stored note even after `PadMap` changes.
2. **End-to-end MIDI** (real phone, USB MIDI mode, Mac): a Python script using `mido` listens on the phone's CoreMIDI port while `adb shell input tap` hits all 16 pad centres. It asserts notes 36–51 arrive in order with velocity 1–127. `adb` taps have a fixed contact size, so this checks *validity*, not expressiveness. If ADB drops in MIDI mode on the device, use wireless debugging.
3. **UI flows with ARTEMIS** (via its MCP server): launch, fold/unfold keeps 4×4, big-pads toggle, sensitivity slider. **Run against a local vision model** (Ollama / LM Studio / llama.cpp, OpenAI-compatible) as a deliberate experiment in local-model capability. Candidates: Qwen2.5-VL / Qwen3-VL, UI-TARS. Endpoint configuration must be confirmed from ARTEMIS's code at install time (its docs show a `local-ollama` preset but don't document the base URL). A cloud key is the fallback. All pads and controls carry content descriptions (e.g. "Pad 5, RS"), which helps both accessibility and ARTEMIS's hierarchy-first locating.
4. **Manual feel check in Logic:** multi-finger playing, latency gut check, tuning the velocity range per device.

The emulator cannot act as a USB MIDI peripheral: layers 2 and 4 need the real phone. The emulator's foldable profile is fine for layers 1 and 3.

## Tooling

- Official Android skills in this environment: `android-cli` (project create/build/run), `adaptive` (fold layout), `testing-setup` (test scaffolding).
- `.superpowers/` (brainstorm mockups) is git-ignored.
