# Gemini review of MiniMidi spec + plan — 2026-09-30

Turn 1 only (user paused the debate after one ~10 min turn). Prompt: adversarial reviewer, spec + plan inlined.

## Gemini turn 1

Here are the top 10 findings on the MiniMidi spec and implementation plan, ranked by severity.

---

### 1. [P0 — Fatal MIDI Bug] `ACTION_UP` leaves hanging / stuck notes on multi-touch releases
* **Location:** Plan Task 7, `PadScreen.kt`:
  ```kotlin
  MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
      val id = e.getPointerId(e.actionIndex)
      engine.release(id)
      lit.remove(id)
  }
  ```
* **What breaks:** 
  1. In Android's `MotionEvent`, `actionIndex` is **only** valid for `ACTION_POINTER_DOWN` and `ACTION_POINTER_UP`. For `ACTION_UP`, `actionIndex` is always `0`.
  2. `ACTION_UP` signals that the **final** pointer has left the glass (0 fingers touching). If a drummer plays a flam, roll, or two-handed chord and lifts both fingers in close succession, Android frequently batches the release or transitions directly to `ACTION_UP`. Handling `ACTION_UP` by releasing only `e.getPointerId(e.actionIndex)` releases at most one pointer. Any other pointer IDs remaining in `engine.held` will **never receive a Note-Off**, droning indefinitely in Logic until the app is paused.
* **Fix:** Differentiate `ACTION_POINTER_UP` from `ACTION_UP`. On `ACTION_UP`, release the active pointer and flush all remaining held notes:
  ```kotlin
  MotionEvent.ACTION_POINTER_UP -> {
      val id = e.getPointerId(e.actionIndex)
      engine.release(id)
      lit.remove(id)
  }
  MotionEvent.ACTION_UP -> {
      val id = e.getPointerId(e.actionIndex)
      engine.release(id)
      engine.releaseAll()
      lit.clear()
  }
  ```

---

### 2. [P0 — Fatal Connection Bug] `MidiOut` leaks devices and permanently locks out reconnects
* **Location:** Plan Task 6, `MidiOut.kt`:
  ```kotlin
  private fun openIfUsb(info: MidiDeviceInfo) {
      if (device != null || opening || info.type != MidiDeviceInfo.TYPE_USB || info.inputPortCount == 0) return
      opening = true
      manager.openDevice(info, { d ->
          opening = false
          if (d == null) return@openDevice
          device = d
          port = d.openInputPort(0) // null if another app holds the port
          connected = port != null
      }, handler)
  }
  ```
* **What breaks:**
  1. **Permanent Lockout:** If `openInputPort(0)` returns `null` (e.g. system service port race or permission contention), `device` is assigned `d`, `port` is `null`, and `connected = false`. Because `device != null`, the guard `if (device != null ...)` permanently aborts all future attempts to open the port. Furthermore, `d` is never closed, leaking the IPC binder token.
  2. **Lifecycle Zombie Port:** `manager.openDevice` is asynchronous. If `stop()` is invoked while `openDevice` is in flight, `stop()` cleans up the existing state, but the callback executes moments later, opens `port`, sets `device = d`, and marks `connected = true` while the Activity is stopped.
* **Fix:** Clean up on null port and guard with an `isStarted` state flag:
  ```kotlin
  private var isStarted = false

  fun start() {
      isStarted = true
      manager.registerDeviceCallback(callback, handler)
      manager.devices.forEach(::openIfUsb)
  }

  fun stop() {
      isStarted = false
      manager.unregisterDeviceCallback(callback)
      close()
  }

  private fun openIfUsb(info: MidiDeviceInfo) {
      if (!isStarted || device != null || opening || info.type != MidiDeviceInfo.TYPE_USB || info.inputPortCount == 0) return
      opening = true
      manager.openDevice(info, { d ->
          opening = false
          if (!isStarted || d == null) {
              d?.close()
              return@openDevice
          }
          val p = d.openInputPort(0)
          if (p == null) {
              d.close()
              return@openDevice
          }
          device = d
          port = p
          connected = true
      }, handler)
  }
  ```

---

### 3. [P0 — Feel / Audio Flaw] `Velocity.kt` has a 100-to-1 cliff, an inaudible floor, and omits `getSize()`
* **Location:** Plan Task 3, `Velocity.kt`:
  ```kotlin
  fun velocity(contactDp: Float, sensitivity: Int, fixed: Boolean, fixedValue: Int): Int {
      val fallback = fixedValue.coerceIn(1, 127)
      if (fixed || contactDp.isNaN() || contactDp <= 0f) return fallback
      val s = sensitivity.coerceIn(SENSITIVITY_MIN, SENSITIVITY_MAX)
      val fullScaleDp = 100f - 6f * s // s=1 → 94 dp, s=10 → 40 dp
      val t = ((contactDp - FLOOR_DP) / (fullScaleDp - FLOOR_DP)).coerceIn(0f, 1f)
      return (1 + t * 126).roundToInt()
  }
  ```
* **What breaks:**
  1. **100-to-1 Inversion Cliff:** If a digitizer driver outputs `0f`, velocity is `100` (`fallback`). But if it outputs a minimal positive value (e.g. `0.1f` or `1.0f` pixel = ~0.3 dp), `contactDp <= 0f` is false, `t` clamps to `0f`, and it outputs `velocity = 1`. A zero-size touch hits at full velocity; a tiny touch hits at absolute silence.
  2. **Inaudible Strike Floor:** In Logic Pro / drum samplers, velocity 1 has -40 dB to -60 dB attenuation. Hardware MPCs enforce a minimum strike floor (typically 25–35). Mapping the floor to `1` means all light taps below 25 dp (~4 mm) produce ghost notes that are completely inaudible.
  3. **Ignoring `MotionEvent.getSize()`:** The spec mandates using `getSize()`/`getTouchMajor()`. The implementation only calls `e.getTouchMajor(i) / density`. Many Android digitizer drivers leave `getTouchMajor` at 0 or 1 while correctly populating normalized `getSize()`.
* **Fix:** Define a musical velocity floor (e.g. 28..127), fall back to `getSize()` when `getTouchMajor()` is non-positive or dummy, and apply fallback to any non-viable contact reading:
  ```kotlin
  private const val MIN_VELOCITY = 28
  private const val FLOOR_DP = 20f

  fun velocity(contactDp: Float, sensitivity: Int, fixed: Boolean, fixedValue: Int): Int {
      val fallback = fixedValue.coerceIn(1, 127)
      if (fixed || contactDp.isNaN() || contactDp <= 1f) return fallback
      val s = sensitivity.coerceIn(SENSITIVITY_MIN, SENSITIVITY_MAX)
      val fullScaleDp = 100f - 6f * s
      val t = ((contactDp - FLOOR_DP) / (fullScaleDp - FLOOR_DP)).coerceIn(0f, 1f)
      return (MIN_VELOCITY + t * (127 - MIN_VELOCITY)).roundToInt()
  }
  ```

---

### 4. [P1 — Compile Error] Overload resolution ambiguity on `clickable(null, null)`
* **Location:** Plan Task 8 Step 2, `PadScreen.kt`:
  ```kotlin
  Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)).clickable(null, null) { panelOpen = false })
  ```
* **What breaks:** In Compose BOM 2026 / Compose 1.7+, `Modifier.clickable` has multiple overloaded variants accepting nullable arguments (interaction source, indications, labels, and roles). Passing positional `(null, null)` causes Kotlin compile error: `Overload resolution ambiguity: All candidates produce an error or match null`. Notice that Step 3 right below it had to use named parameters.
* **Fix:** Use named arguments:
  ```kotlin
  Box(
      Modifier
          .fillMaxSize()
          .background(Color.Black.copy(alpha = 0.5f))
          .clickable(interactionSource = null, indication = null) { panelOpen = false }
  )
  ```

---

### 5. [P1 — Latency / Jank] Compose recomposition storm inside `PadGrid` on every touch & glow frame
* **Location:** Plan Task 7, `PadScreen.kt`:
  ```kotlin
  val lit = remember { mutableStateMapOf<Int, Pair<Int, Float>>() }
  ...
  for (row in 0 until layout.rows) {
      Row(Modifier.weight(1f)) {
          for (column in 0 until layout.columns) {
              val index = padIndex(row, column, layout)
              val velocity = lit.values.filter { it.first == index }.maxOfOrNull { it.second }
              PadCell(index, velocity, ...)
          }
      }
  }
  ```
  and inside `PadCell`:
  ```kotlin
  val glow by animateFloatAsState(...)
  Box(
      modifier
          .background(lerp(Rhythm.Groups[colourGroup(index)], Color.White, glow))
          ...
  )
  ```
* **What breaks:** 
  1. Reading `lit.values` inside the grid loop causes the entire `PadGrid` composable to recompose on every down/up event, executing 16 filter operations and allocating closures across all cells.
  2. In `PadCell`, reading `glow` in `Modifier.background()` causes `PadCell` to recompose every display frame (8.3 ms on 120 Hz screens) for 150 ms after every hit. Concurrent hits multiply this churn, causing UI thread starvation and input jitter.
* **Fix:** Defer state reads to the Draw phase and isolate pad state:
  1. Store pad states in an array of 16 independent states (`remember { List(16) { mutableStateOf<Float?>(null) } }`) so only the touched pad is updated.
  2. Draw the glow background in `Modifier.drawBehind { ... }` where reading `glow` skips Recomposition and Layout entirely, running directly on RenderThread.

---

### 6. [P1 — Spec Violation] `systemGestureExclusion` on full grid exceeds Android's 200 dp limit
* **Location:** Plan Task 7, `PadScreen.kt`:
  ```kotlin
  Column(
      modifier
          .fillMaxWidth()
          .onSizeChanged { size = it }
          .systemGestureExclusion()
  ```
* **What breaks:** The `Column` spans ~700–900 dp. In Android 10+ (`View.setSystemGestureExclusionRects`), the OS strictly limits gesture exclusion to **200 dp per edge**. Any height beyond 200 dp is truncated by the system (AOSP takes only the bottom 200 dp). Pads in rows 3 and 4 (including corner pads 13 and 16) are left completely unprotected; striking them near the side bezel triggers the system Back gesture and exits the app. The plan explicitly admits this in Task 10 and defers it to a "follow-up fix", violating the spec's requirement that the implementation cover edge pads within the quota.
* **Fix:** Remove `.systemGestureExclusion()` from the parent `Column`. Instead, calculate explicit rects targeting the 4 outer edge zones, allocating 50 dp per row to the left and right bezels (4 × 50 dp = 200 dp exactly).

---

### 7. [P1 — Broken Test Harness] Destructive queue consumption in `e2e_midi.py`
* **Location:** Plan Task 9 Step 2, `tools/e2e_midi.py`:
  ```python
  def wait_for(port, kind, note, timeout=1.0):
      end = time.monotonic() + timeout
      while time.monotonic() < end:
          for msg in port.iter_pending():
              if msg.type == kind and msg.note == note:
                  return msg
          time.sleep(0.002)
      return None
  ...
  on = wait_for(port, "note_on", note)
  off = wait_for(port, "note_off", note)
  ```
* **What breaks:** `port.iter_pending()` pops and discards messages from `mido`'s queue. When `adb shell input tap` executes, the tap completes before `subprocess.run` returns, meaning both `note_on` and `note_off` are already in the port buffer. If `wait_for(..., "note_on", ...)` encounters `note_off` during its loop or if messages arrive out of strict sequential iteration, `note_off` is consumed and discarded. The subsequent `wait_for(..., "note_off", ...)` then times out, causing false test failures.
* **Fix:** Drain incoming messages into an append-only backlog and inspect the backlog:
  ```python
  def drain(port, backlog):
      for msg in port.iter_pending():
          backlog.append(msg)

  def wait_for(port, backlog, kind, note, timeout=1.0):
      end = time.monotonic() + timeout
      while time.monotonic() < end:
          drain(port, backlog)
          for i, msg in enumerate(backlog):
              if msg.type == kind and msg.note == note:
                  return backlog.pop(i)
          time.sleep(0.002)
      return None
  ```

---

### 8. [P2 — Edge Hit Drop] Boundary coordinate drop in `Grid.kt`
* **Location:** Plan Task 5, `Grid.kt`:
  ```kotlin
  fun hitTest(x: Float, y: Float, width: Float, height: Float, layout: GridLayout): Int? {
      if (x < 0f || y < 0f || x >= width || y >= height) return null
      val column = (x / width * layout.columns).toInt()
      val visualRow = (y / height * layout.rows).toInt()
      return padIndex(visualRow, column, layout)
  }
  ```
* **What breaks:** Android digitizers can report touches on the right or bottom bezel boundary where `x == width` or `y == height` (or `width - epsilon`). Strict `>= width` drops legitimate hits on edge pads (Pads 4, 8, 12, 16) as `null`.
* **Fix:** Clamp `x` and `y` within the layout boundaries:
  ```kotlin
  fun hitTest(x: Float, y: Float, width: Float, height: Float, layout: GridLayout): Int? {
      if (width <= 0f || height <= 0f || x < 0f || y < 0f || x > width || y > height) return null
      val column = (x / width * layout.columns).toInt().coerceIn(0, layout.columns - 1)
      val visualRow = (y / height * layout.rows).toInt().coerceIn(0, layout.rows - 1)
      return padIndex(visualRow, column, layout)
  }
  ```

---

### 9. [P2 — UI Hitching] `SharedPreferences` disk thrashing during slider drag
* **Location:** Plan Task 8 Step 4, `PadScreen.kt`:
  ```kotlin
  Slider(
      value = settings.sensitivity.toFloat(),
      onValueChange = { onChange(settings.copy(sensitivity = it.roundToInt())) },
      ...
  )
  ```
  wired in `MainActivity.kt`:
  ```kotlin
  onSettingsChange = { settings = it; prefs.save(it) }
  ```
* **What breaks:** Dragging the sensitivity slider invokes `onValueChange` at 60–120 Hz. Every event triggers `prefs.save(it)` -> `SharedPreferences.apply()`, queuing dozens of asynchronous disk serialization runnables into Android's `QueuedWork`, causing main-thread stutter while interacting with settings.
* **Fix:** Update transient UI state in `onValueChange`, and only persist to `SharedPreferences` in `Slider`'s `onValueChangeFinished` callback or when tapping the "DONE" button.

---

### 10. [P2 — Spec Violation] `safeDrawingPadding()` and outer horizontal padding break full-bleed layout
* **Location:** Plan Task 7, `PadScreen.kt`:
  ```kotlin
  Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 8.dp)) {
      StatusBar(connected)
      PadGrid(layout, settings, engine, haptics, enabled = !panelOpen, modifier = Modifier.weight(1f))
      SettingsStrip(settings, onClick = { panelOpen = true })
  }
  ```
* **What breaks:** The spec demands a "Full-bleed play surface: immersive mode ... tight 4–6 dp gutters". Applying `safeDrawingPadding()` on top of `padding(horizontal = 8.dp)` creates excessive letterboxing, especially on foldable inner screens with camera cutouts. Furthermore, in immersive mode, if transient system bars are swiped in, insets shift dynamically, resizing `PadGrid` and altering pad positions mid-hit.
* **Fix:** Remove `safeDrawingPadding()` and fixed outer horizontal padding from the root `PadScreen` container. Let the pad grid bleed to the edges, handling only camera cutout insets if necessary.
