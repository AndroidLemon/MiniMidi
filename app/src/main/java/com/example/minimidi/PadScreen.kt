package com.example.minimidi

import android.view.MotionEvent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.systemGestureExclusion
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val PadShape = RoundedCornerShape(3.dp)

@Composable
fun PadScreen(
    settings: Settings,
    onSettingsChange: (Settings) -> Unit,
    connected: Boolean,
    engine: PadEngine,
    haptics: Haptics,
) {
    var panelOpen by remember { mutableStateOf(false) }
    BoxWithConstraints(Modifier.fillMaxSize().background(Rhythm.Body)) {
        val folded = maxWidth < 600.dp
        val layout = layoutFor(folded, settings.bigPadsWhenFolded)
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 8.dp)) {
            StatusBar(connected)
            PadGrid(layout, settings, engine, haptics, enabled = !panelOpen, modifier = Modifier.weight(1f))
            SettingsStrip(settings, onClick = { panelOpen = true })
        }
    }
}

@Composable
private fun StatusBar(connected: Boolean) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 8.dp)
            .semantics(mergeDescendants = true) { contentDescription = if (connected) "MIDI connected" else "MIDI not connected" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("MiniMidi", style = TextStyle(fontFamily = Rhythm.Wordmark, fontSize = 16.sp, color = Rhythm.Ink))
        Spacer(Modifier.weight(1f))
        Box(Modifier.size(7.dp).clip(CircleShape).background(if (connected) Rhythm.Led else Rhythm.Led.copy(alpha = 0.2f)))
        Spacer(Modifier.width(6.dp))
        Text(if (connected) "USB · 10" else "USB → MIDI", style = Rhythm.label(Rhythm.Ink))
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun PadGrid(
    layout: GridLayout,
    settings: Settings,
    engine: PadEngine,
    haptics: Haptics,
    enabled: Boolean,
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

    LaunchedEffect(enabled) {
        if (!enabled) releaseAll()
    }

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
                        val i = e.actionIndex
                        val pad = hitTest(e.getX(i), e.getY(i), size.width.toFloat(), size.height.toFloat(), currentLayout)
                        if (pad != null) {
                            val s = currentSettings
                            val v = velocity(e.getTouchMajor(i) / density, s.sensitivity, s.fixedVelocity, s.fixedValue)
                            val id = e.getPointerId(i)
                            engine.press(id, DEFAULT_PADS[pad], v)
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
                    PadCell(index, padLevels[index], Modifier.weight(1f).fillMaxHeight().padding(3.dp))
                }
            }
        }
    }
}

@Composable
private fun PadCell(index: Int, level: State<Float?>, modifier: Modifier) {
    val pad = DEFAULT_PADS[index]
    val velocity = level.value // read here, not in PadGrid, so a hit recomposes only this pad
    val lit = velocity != null
    // Instant on (snap), 150 ms linear fade on release. No ripple.
    val glow = animateFloatAsState(
        targetValue = if (velocity != null) 0.25f + 0.4f * velocity else 0f,
        animationSpec = if (lit) snap() else tween(durationMillis = 150, easing = LinearEasing),
        label = "pad$index",
    )
    val base = Rhythm.Groups[colourGroup(index)]
    Box(
        modifier
            .semantics { contentDescription = "Pad ${index + 1}, ${pad.label}" }
            .clip(PadShape)
            .drawBehind {
                // glow is read in the draw phase: the fade redraws each frame without recomposing.
                drawRect(lerp(base, Color.White, glow.value))
                // Stroke is centred on the edge and the clip hides the outer half, so 4 dp draws 2 dp inside.
                if (lit) drawRect(Color.White, style = Stroke(width = 4.dp.toPx()))
            },
    ) {
        val ink = Color.Black.copy(alpha = 0.55f)
        Text("${index + 1}", Modifier.align(Alignment.TopStart).padding(6.dp), style = Rhythm.label(ink))
        Text(pad.label, Modifier.align(Alignment.BottomEnd).padding(6.dp), style = Rhythm.label(ink))
    }
}

@Composable
private fun SettingsStrip(settings: Settings, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val mode = if (settings.fixedVelocity) "Velocity: Fixed ${settings.fixedValue}" else "Velocity: Area"
        Text(mode.uppercase(), style = Rhythm.label(Rhythm.Ink))
        Spacer(Modifier.weight(1f))
        Text("SENS ${settings.sensitivity}", style = Rhythm.label(Rhythm.Ink))
    }
}
