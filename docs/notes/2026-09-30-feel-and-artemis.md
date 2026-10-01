# MiniMidi v1: feel check and ARTEMIS experiment

Device: Galaxy Z Fold5 (SM-F946U1), Android SDK 36. Mac: Logic Pro, input port `SAMSUNG_Android`.
Build: `feat/minimidi-v1` @ cec2715. Feel check run 2026-10-01.

## Feel check (Task 10, step 1)

| # | Check | Result |
|---|---|---|
| 1 | Finger-drum a beat with 2–3 fingers: lag, doubled or missed hits | **Pass.** Works well. |
| 2 | Soft vs hard taps in area mode; tune Sensitivity | **Fail: known limitation.** Every area-mode hit plays at the same velocity; only Fixed velocity changes the level. User chose to play in Fixed mode for now. Diagnosis below. |
| 3 | Corner pads 1, 4, 13, 16 hit hard at the edge: does Back trigger? | **Pass.** No Back triggers seen. |
| 4 | Hold a pad and fold mid-beat | **Not tested** (hard to do physically). Code path reviewed, and the Activity is not recreated on fold (verified via adb). |
| 5 | Hold a pad and press Home | **Not tested** (not reachable one-handed with this phone's setup). Code path reviewed: `onPause` sends note-offs plus CC 123. |
| 6 | Cream top row (13–16) visibly lights when hit | **Pass.** |
| 7 | Cover screen in landscape (4×4 even with big pads on) | **Pass.** Looks fine, keep as is. |
| 8 | System bars stay hidden after fold/unfold | **Pass.** |
| 9 | Haptic tick per hit | **Faint.** Not much felt in the way of haptics; user is happy with it. The Fold5 supports `PRIMITIVE_CLICK`, so the primitive path is the one that plays. |

### Area velocity diagnosis (check 2)

- The panel reports `ABS_MT_TOUCH_MAJOR` 0–255 with `touch.size.calibration: GEOMETRIC`, and no pressure axis.
- Geometric calibration scales raw units by the X position scale (1812 px / 4096 ≈ 0.44 px per unit).
- Raw contact at touch-down measured 8–17 across 5 taps. That is about 1.6–3.3 dp after density 2.25.
- Contact keeps growing after down (one tap went 9 → 21). We only sample at down, to avoid adding latency.
- `velocity()` ramps from `FLOOR_DP = 25` to a full scale of 40–94 dp. Every real tap lands below the floor and plays at `MIN_VELOCITY` (20).

Options for a later plan:
1. Retune `FLOOR_DP` and the full-scale range to the measured ~1.5–4 dp. Capture 10 soft and 10 hard taps first.
2. Self-calibrate per device from observed min and max contact sizes.

The raw signal does vary about 2×, so some dynamics are recoverable.

## ARTEMIS local-model experiment (Task 10, steps 2–4)

Deferred to a later session by the user (2026-10-01).
