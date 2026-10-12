package com.example.minimidi

import android.view.MotionEvent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.pointerInput
import android.os.SystemClock
import kotlinx.coroutines.delay
import androidx.compose.runtime.DisposableEffect
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

private val PadShape = RoundedCornerShape(3.dp)

/** A note-on heard from the computer. Identity-compared, so the same note twice still counts as two events. */
class HeardNote(val channel: Int, val note: Int)

@Composable
fun PadScreen(
    settings: Settings,
    onSettingsChange: (Settings) -> Unit,
    connected: Boolean,
    inputConnected: Boolean,
    pads: List<Pad>,
    onPadsChange: (List<Pad>) -> Unit,
    bank: Int,
    onBankChange: (Int) -> Unit,
    heardNote: HeardNote?,
    onArmedChange: (Boolean) -> Unit,
    engine: PadEngine,
    noteRepeat: NoteRepeat,
    haptics: Haptics,
) {
    var panelOpen by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Int?>(null) }
    var armed by remember { mutableStateOf(false) } // MIDI learn: the next incoming note goes to the selected pad
    BoxWithConstraints(Modifier.fillMaxSize().background(Rhythm.Body)) {
        val folded = maxWidth < 600.dp
        val layout = layoutFor(folded, settings.bigPadsWhenFolded)
        val sel = selected?.takeIf { editing && it < layout.padCount }
        fun endEdit() { editing = false; selected = null; armed = false }
        fun edit(f: (Pad) -> Pad) {
            val i = sel ?: return
            onPadsChange(pads.toMutableList().also { it[i] = f(it[i]) })
        }

        LaunchedEffect(heardNote) {
            val h = heardNote ?: return@LaunchedEffect
            if (!armed || sel == null) return@LaunchedEffect
            // Below bank A's range the note can't be stored: stay armed.
            val note = learnedNote(h.note, bank) ?: return@LaunchedEffect
            edit { Pad(note, h.channel) }
            armed = false
        }
        LaunchedEffect(settings.rate, settings.internalBpm) {
            noteRepeat.rate = settings.rate
            noteRepeat.internalBpm = settings.internalBpm
            noteRepeat.refresh()
        }
        // Drives the EXT -> INT display, and INT taking over when the external clock stops under a held REPEAT.
        LaunchedEffect(Unit) { while (true) { noteRepeat.refresh(); delay(250) } }
        LaunchedEffect(panelOpen) { if (panelOpen) noteRepeat.hold(false) }
        LaunchedEffect(armed) { onArmedChange(armed) }
        LaunchedEffect(inputConnected) { if (!inputConnected) armed = false }

        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 8.dp)) {
            StatusBar(connected, bank) { engine.releaseAll(); onBankChange(it) }
            PadGrid(
                layout, pads, bank, settings, engine, haptics, enabled = !panelOpen,
                selected = sel, armed = armed,
                onSelect = { i -> if (editing) { if (i != selected) armed = false; selected = i } },
                modifier = Modifier.weight(1f),
            )
            if (editing) {
                val shown = sel?.let { played(pads[it], bank) }
                EditPanel(
                    selected = sel,
                    label = sel?.let { padLabel(shown?.note ?: pads[it].note, pads[it].channel) } ?: "",
                    noteText = shown?.let { "${padLabel(it.note, it.channel)}·${it.note}" } ?: "—",
                    channel = sel?.let { pads[it].channel } ?: 0,
                    armed = armed,
                    inputConnected = inputConnected,
                    onNote = { d -> edit { it.copy(note = (it.note + d).coerceIn(0, 127)) } },
                    onOctave = { d -> edit { it.copy(note = (it.note + d * 12).coerceIn(0, 127)) } },
                    onChannel = { d -> edit { it.copy(channel = (it.channel + d).coerceIn(1, 16)) } },
                    onLearn = { armed = !armed },
                    onReset = { sel?.let { i -> edit { DEFAULT_PADS[i] } } },
                    onDone = ::endEdit,
                )
            }
            if (!editing) {
                RepeatRow(
                    noteRepeat, panelOpen, settings.rate,
                    onRate = { onSettingsChange(settings.copy(rate = settings.rate.next())) },
                    onTempo = { onSettingsChange(settings.copy(internalBpm = it)) },
                )
            }
            SettingsStrip(settings, editing, onEdit = { if (editing) endEdit() else editing = true }, onClick = { panelOpen = true })
        }
        if (panelOpen) {
            // Scrim: tapping outside closes the panel and swallows the touch so nothing underneath fires.
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)).clickable(interactionSource = null, indication = null, onClickLabel = "Close settings") { panelOpen = false })
            SettingsPanel(settings, onSettingsChange, onDone = { panelOpen = false }, Modifier.align(Alignment.BottomCenter))
        }
    }
}

@Composable
private fun StatusBar(connected: Boolean, bank: Int, onBank: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("MiniMidi", style = TextStyle(fontFamily = Rhythm.Wordmark, fontSize = 16.sp, color = Rhythm.Ink))
        Spacer(Modifier.width(8.dp))
        BankButton("◀", "Previous bank", enabled = bank > 0) { onBank(bank - 1) }
        Text(bankName(bank), Modifier.semantics { contentDescription = "Bank ${bankName(bank)}" }, style = Rhythm.label(Rhythm.Ink))
        BankButton("▶", "Next bank", enabled = bank < BANK_COUNT - 1) { onBank(bank + 1) }
        Spacer(Modifier.weight(1f))
        Row(
            Modifier.semantics(mergeDescendants = true) { contentDescription = if (connected) "MIDI connected" else "MIDI not connected" },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(if (connected) Rhythm.Led else Rhythm.Led.copy(alpha = 0.2f)))
            Spacer(Modifier.width(6.dp))
            Text(if (connected) "USB" else "USB → MIDI", style = Rhythm.label(Rhythm.Ink))
        }
    }
}

@Composable
private fun BankButton(glyph: String, description: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(48.dp)
            .alpha(if (enabled) 1f else 0.3f)
            .clickable(enabled = enabled, interactionSource = null, indication = null, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = description; if (!enabled) disabled() },
        contentAlignment = Alignment.Center,
    ) {
        Text(glyph, style = Rhythm.label(Rhythm.Ink))
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun PadGrid(
    layout: GridLayout,
    pads: List<Pad>,
    bank: Int,
    settings: Settings,
    engine: PadEngine,
    haptics: Haptics,
    enabled: Boolean,
    selected: Int?,
    armed: Boolean,
    onSelect: (Int) -> Unit,
    modifier: Modifier,
) {
    // pointerId → (padIndex, velocity 0..1). Plain map, not Compose state: only the per-pad levels below recompose.
    val fingers = remember { HashMap<Int, Pair<Int, Float>>() }
    // One state per pad, so a hit recomposes only the pad that was hit. A pad is lit while any finger holds it.
    val padLevels = remember { List(DEFAULT_PADS.size) { mutableStateOf<Float?>(null) } }
    fun refresh(pad: Int) {
        padLevels[pad].value = fingers.values.filter { it.first == pad }.maxOfOrNull { it.second }
    }
    fun releaseAll() {
        engine.releaseAll()
        fingers.clear()
        padLevels.forEach { it.value = null }
    }
    val density = LocalDensity.current.density
    var size by remember { mutableStateOf(IntSize.Zero) }
    val currentSettings by rememberUpdatedState(settings)
    val currentLayout by rememberUpdatedState(layout)
    val currentEnabled by rememberUpdatedState(enabled)
    val currentPads by rememberUpdatedState(pads)
    val currentBank by rememberUpdatedState(bank)
    val currentOnSelect by rememberUpdatedState(onSelect)

    // Settings opening or a bank change: nothing may stay held or lit.
    LaunchedEffect(enabled, bank) { releaseAll() }

    Column(
        modifier
            .fillMaxWidth()
            .onSizeChanged { size = it }
            .systemGestureExclusion() // edge pads shouldn't trigger Back; Android caps this at 200 dp per edge (checked in Task 10)
            .pointerInteropFilter { e ->
                // Raw MotionEvents: Compose's pointer API doesn't expose touch size, which velocity needs.
                if (!currentEnabled) return@pointerInteropFilter false
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                        // A fresh gesture means no finger is down: drop stale state (missed UP, onPause panic).
                        if (e.actionMasked == MotionEvent.ACTION_DOWN) releaseAll()
                        val i = e.actionIndex
                        val pad = hitTest(e.getX(i), e.getY(i), size.width.toFloat(), size.height.toFloat(), currentLayout)
                        val played = pad?.let { played(currentPads[it], currentBank) } // null: out of range in this bank, silent
                        if (pad != null) currentOnSelect(pad) // dimmed pads can still be selected for editing
                        if (pad != null && played != null) {
                            val s = currentSettings
                            val v = velocity(e.getTouchMajor(i) / density, s.sensitivity, s.fixedVelocity, s.fixedValue)
                            val id = e.getPointerId(i)
                            engine.press(id, played, v)
                            fingers.remove(id)?.let { refresh(it.first) } // stale entry from a missed UP
                            fingers[id] = pad to v / 127f
                            refresh(pad)
                            if (s.haptics) haptics.tick(v)
                        }
                    }
                    MotionEvent.ACTION_POINTER_UP -> {
                        val id = e.getPointerId(e.actionIndex)
                        engine.release(id)
                        fingers.remove(id)?.let { refresh(it.first) }
                    }
                    // Last finger lifted: nothing may stay held, whatever events were missed.
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> releaseAll()
                    // ACTION_MOVE ignored on purpose: sliding onto another pad doesn't trigger it.
                }
                true
            },
    ) {
        for (row in 0 until layout.rows) {
            Row(Modifier.weight(1f)) {
                for (column in 0 until layout.columns) {
                    val index = padIndex(row, column, layout)
                    PadCell(
                        index, played(pads[index], bank), padLevels[index], selected == index, selected == index && armed,
                        Modifier.weight(1f).fillMaxHeight().padding(3.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun PadCell(index: Int, pad: Pad?, level: State<Float?>, selected: Boolean, armed: Boolean, modifier: Modifier) {
    val label = pad?.let { padLabel(it.note, it.channel) } ?: "—"
    val velocity = level.value // read here, not in PadGrid, so a hit recomposes only this pad
    val lit = velocity != null
    // Instant on (snap), 150 ms linear fade on release. No ripple.
    val glow = animateFloatAsState(
        targetValue = if (velocity != null) 0.25f + 0.4f * velocity else 0f,
        animationSpec = if (lit) snap() else tween(durationMillis = 150, easing = LinearEasing),
        label = "pad$index",
    )
    // Armed for MIDI learn: the outline blinks. Only the armed pad runs the animation.
    val blink = if (armed) {
        rememberInfiniteTransition(label = "learn").animateFloat(
            initialValue = 1f, targetValue = 0.1f,
            animationSpec = infiniteRepeatable(tween(450, easing = LinearEasing), RepeatMode.Reverse),
            label = "blink$index",
        )
    } else null
    val base = Rhythm.Groups[colourGroup(index)]
    Box(
        modifier
            .alpha(if (pad == null) 0.3f else 1f)
            .semantics { contentDescription = "Pad ${index + 1}, $label" }
            .clip(PadShape)
            .drawBehind {
                // glow is read in the draw phase: the fade redraws each frame without recomposing.
                drawRect(lerp(base, Color.White, glow.value))
                // Stroke is centred on the edge and the clip hides the outer half, so 4 dp draws 2 dp inside.
                val outline = if (lit) 1f else blink?.value ?: if (selected) 1f else 0f
                if (outline > 0f) drawRect(Color.White.copy(alpha = outline), style = Stroke(width = 4.dp.toPx()))
            },
    ) {
        val ink = Color.Black.copy(alpha = 0.55f)
        Text("${index + 1}", Modifier.align(Alignment.TopStart).padding(6.dp), style = Rhythm.label(ink))
        Text(label, Modifier.align(Alignment.BottomEnd).padding(6.dp), style = Rhythm.label(ink))
    }
}

@Composable
private fun RepeatRow(noteRepeat: NoteRepeat, panelOpen: Boolean, rate: Rate, onRate: () -> Unit, onTempo: (Int) -> Unit) {
    var pressed by remember { mutableStateOf(false) }
    val tapTempo = remember { TapTempo { SystemClock.uptimeMillis() } }
    val currentOnTempo by rememberUpdatedState(onTempo)
    DisposableEffect(Unit) { onDispose { noteRepeat.hold(false) } } // row hidden mid-hold
    LaunchedEffect(panelOpen) { if (panelOpen) pressed = false } // hold(false) is forced when settings opens: don't stay lit
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .height(48.dp)
                .widthIn(min = 96.dp)
                .semantics { contentDescription = "Note repeat" }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val id = awaitFirstDown(requireUnconsumed = false).id
                        pressed = true
                        noteRepeat.hold(true)
                        try {
                            // Hold until this finger lifts, wherever it has drifted (waitForUpOrCancellation would end at the bounds).
                            do { val event = awaitPointerEvent() } while (event.changes.any { it.id == id && it.pressed })
                        } finally { pressed = false; noteRepeat.hold(false) }
                    }
                }
                .padding(end = 16.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text("REPEAT", style = Rhythm.label(if (pressed) Rhythm.Led else Rhythm.Ink))
        }
        Box(
            Modifier
                .heightIn(min = 48.dp)
                .widthIn(min = 48.dp)
                .semantics { contentDescription = "Repeat rate ${rate.label}" }
                .clickable(interactionSource = null, indication = null, onClick = onRate)
                .padding(horizontal = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(rate.label, style = Rhythm.label(Rhythm.Ink))
        }
        Spacer(Modifier.weight(1f))
        Box(
            Modifier
                .heightIn(min = 48.dp)
                .widthIn(min = 48.dp)
                .semantics { contentDescription = "Tempo" }
                .clickable(interactionSource = null, indication = null) {
                    if (noteRepeat.source == ClockMode.INT) tapTempo.tap()?.let { currentOnTempo(it) }
                }
                .padding(horizontal = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("${noteRepeat.source.name} ${noteRepeat.bpm}", style = Rhythm.label(Rhythm.Ink))
        }
    }
}

@Composable
private fun SettingsStrip(settings: Settings, editing: Boolean, onEdit: () -> Unit, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(enabled = !editing, interactionSource = null, indication = null, onClickLabel = "Open settings", onClick = onClick)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .heightIn(min = 48.dp)
                .clickable(interactionSource = null, indication = null, onClickLabel = "Edit pads", onClick = onEdit)
                .padding(end = 16.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text("EDIT", style = Rhythm.label(if (editing) Rhythm.Led else Rhythm.Ink))
        }
        val mode = if (settings.fixedVelocity) "Velocity: Fixed ${settings.fixedValue}" else "Velocity: Area"
        Text(mode.uppercase(), style = Rhythm.label(Rhythm.Ink))
        Spacer(Modifier.weight(1f))
        Text("SENS ${settings.sensitivity}", style = Rhythm.label(Rhythm.Ink))
    }
}

@Composable
private fun SettingsPanel(settings: Settings, onChange: (Settings) -> Unit, onDone: () -> Unit, modifier: Modifier) {
    val sliderColors = SliderDefaults.colors(
        thumbColor = Rhythm.Ink,
        activeTrackColor = Rhythm.Led,
        inactiveTrackColor = Rhythm.Ink.copy(alpha = 0.2f),
        activeTickColor = Color.Transparent,
        inactiveTickColor = Color.Transparent,
    )
    Column(
        modifier
            .fillMaxWidth()
            .background(Rhythm.Panel)
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        SettingLabel("Sensitivity ${settings.sensitivity}")
        Slider(
            value = settings.sensitivity.toFloat(),
            onValueChange = { onChange(settings.copy(sensitivity = it.roundToInt())) },
            valueRange = SENSITIVITY_MIN.toFloat()..SENSITIVITY_MAX.toFloat(),
            steps = SENSITIVITY_MAX - SENSITIVITY_MIN - 1,
            colors = sliderColors,
            modifier = Modifier.semantics { contentDescription = "Sensitivity" },
        )
        SettingSwitch("Fixed velocity", settings.fixedVelocity) { onChange(settings.copy(fixedVelocity = it)) }
        if (settings.fixedVelocity) {
            SettingLabel("Level ${settings.fixedValue}")
            Slider(
                value = settings.fixedValue.toFloat(),
                onValueChange = { onChange(settings.copy(fixedValue = it.roundToInt().coerceIn(1, 127))) },
                valueRange = 1f..127f,
                colors = sliderColors,
                modifier = Modifier.semantics { contentDescription = "Fixed velocity level" },
            )
        }
        SettingLabel("Internal tempo ${settings.internalBpm}")
        Slider(
            value = settings.internalBpm.toFloat(),
            onValueChange = { onChange(settings.copy(internalBpm = it.roundToInt().coerceIn(BPM_MIN, BPM_MAX))) },
            valueRange = BPM_MIN.toFloat()..BPM_MAX.toFloat(),
            colors = sliderColors,
            modifier = Modifier.semantics { contentDescription = "Internal tempo" },
        )
        SettingSwitch("Haptics", settings.haptics) { onChange(settings.copy(haptics = it)) }
        SettingSwitch("Big pads when folded", settings.bigPadsWhenFolded) { onChange(settings.copy(bigPadsWhenFolded = it)) }
        Text(
            "DONE",
            style = Rhythm.label(Rhythm.Led),
            modifier = Modifier
                .align(Alignment.End)
                .heightIn(min = 48.dp)
                .clickable(interactionSource = null, indication = null, onClick = onDone)
                .padding(horizontal = 8.dp, vertical = 16.dp),
        )
    }
}

@Composable
private fun SettingLabel(text: String) =
    Text(text.uppercase(), style = Rhythm.label(Rhythm.Ink), modifier = Modifier.padding(top = 8.dp))

@Composable
private fun SettingSwitch(text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(value = checked, interactionSource = null, indication = null, role = Role.Switch, onValueChange = onChange)
            .semantics(mergeDescendants = true) { contentDescription = text },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text.uppercase(), style = Rhythm.label(Rhythm.Ink))
        Spacer(Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = null, // the whole row is the target
            colors = SwitchDefaults.colors(
                checkedThumbColor = Rhythm.Ink,
                checkedTrackColor = Rhythm.Led,
                uncheckedThumbColor = Rhythm.Ink.copy(alpha = 0.6f),
                uncheckedTrackColor = Rhythm.Body,
                uncheckedBorderColor = Rhythm.Ink.copy(alpha = 0.3f),
            ),
        )
    }
}
