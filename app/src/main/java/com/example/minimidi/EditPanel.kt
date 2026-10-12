package com.example.minimidi

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** Docked under the grid in edit mode. [selected] is the 0-based pad being edited, or null before a tap. */
@Composable
fun EditPanel(
    selected: Int?,
    label: String,
    noteText: String,
    channel: Int,
    armed: Boolean,
    inputConnected: Boolean,
    onNote: (Int) -> Unit,
    onOctave: (Int) -> Unit,
    onChannel: (Int) -> Unit,
    onLearn: () -> Unit,
    onReset: () -> Unit,
    onDone: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 152.dp) // same height before and after selection, so the grid doesn't jump
            .background(Rhythm.Panel)
            .padding(horizontal = 8.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            val title = if (selected == null) "TAP A PAD" else "PAD ${selected + 1} · $label"
            Text(title, style = Rhythm.label(Rhythm.Ink), modifier = Modifier.weight(1f))
            EditButton("DONE", "Done editing", Rhythm.Led, onDone)
        }
        if (selected != null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Stepper("NOTE", noteText, "Note down", "Note up", onNote)
                Stepper("CH", "$channel", "Channel down", "Channel up", onChannel)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Stepper("OCT", null, "Octave down", "Octave up", onOctave)
                if (inputConnected) EditButton("LEARN", "Learn", if (armed) Rhythm.Led else Rhythm.Ink, onLearn)
                EditButton("RESET PAD", "Reset pad", Rhythm.Ink, onReset)
            }
        }
    }
}

/** `NAME − value +`; [value] null for OCT, which has no readout. Each button steps by ±1 (the caller scales it). */
@Composable
private fun Stepper(name: String, value: String?, downLabel: String, upLabel: String, onStep: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(name, style = Rhythm.label(Rhythm.Ink.copy(alpha = 0.6f)))
        StepButton("−", downLabel) { onStep(-1) }
        if (value != null) Text(value, style = Rhythm.label(Rhythm.Ink))
        StepButton("+", upLabel) { onStep(1) }
    }
}

@Composable
private fun StepButton(glyph: String, description: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(48.dp)
            .semantics { contentDescription = description }
            .clickable(interactionSource = null, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(glyph, style = Rhythm.label(Rhythm.Ink))
    }
}

@Composable
private fun EditButton(text: String, description: String, color: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    Box(
        Modifier
            .heightIn(min = 48.dp)
            .semantics { contentDescription = description }
            .clickable(interactionSource = null, indication = null, onClick = onClick)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = Rhythm.label(color))
    }
}
