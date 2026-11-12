package com.reelforge.editor.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.reelforge.editor.engine.EffectFactory
import com.reelforge.editor.model.LoopFx
import com.reelforge.editor.model.TextFont
import com.reelforge.editor.model.TextItem
import com.reelforge.editor.model.TextReveal

private val EffectColours = listOf(
    0xFF000000, 0xFFFFFFFF, 0xFFFFEB3B, 0xFFFF5252, 0xFFFF9800, 0xFF69F0AE,
    0xFF40C4FF, 0xFF7C4DFF, 0xFFFF80AB,
).map { it.toInt() }

@Composable
private fun ColourDots(selected: Int, onPick: (Int) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        EffectColours.forEach { c ->
            val isSelected = (selected and 0xFFFFFF) == (c and 0xFFFFFF)
            Box(
                Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(Color(c))
                    .border(
                        if (isSelected) 3.dp else 1.dp,
                        if (isSelected) MaterialTheme.colorScheme.primary else Color.Gray,
                        CircleShape,
                    )
                    .clickable { onPick(c) },
            )
        }
    }
}

@Composable
private fun Label(text: String) = Text(text, style = MaterialTheme.typography.labelLarge)

@Composable
private fun Toggle(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = value, onCheckedChange = onChange)
    }
}

/** The built-in fonts as tiles, each showing the user's own words in that font. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FontTiles(text: TextItem, onPick: (TextFont) -> Unit) {
    val words = text.text.lineSequence().firstOrNull().orEmpty().trim().take(14).ifEmpty { "Abc" }
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TextFont.entries.forEach { font ->
            val selected = text.fontPath == null && text.font == font
            val sample = remember(words, font, text.bold, text.italic) {
                runCatching {
                    EffectFactory.renderText(
                        TextItem(text = words, font = font, bold = text.bold, italic = text.italic, size = 0.5f, shadow = false),
                        72,
                    ).asImageBitmap()
                }.getOrNull()
            }
            Box(
                Modifier
                    .width(112.dp)
                    .height(46.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .border(
                        if (selected) 2.dp else 0.dp,
                        if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
                        RoundedCornerShape(8.dp),
                    )
                    .clickable { onPick(font) },
                contentAlignment = Alignment.Center,
            ) {
                if (sample != null) {
                    Image(
                        bitmap = sample,
                        contentDescription = font.label,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize().padding(4.dp),
                    )
                } else {
                    Text(font.label)
                }
            }
        }
    }
}

/** Underline and spacing, shown on the Look tab. */
@Composable
fun TextLookExtras(vm: EditorViewModel, text: TextItem) {
    val id = text.id
    FilterChip(
        selected = text.underline,
        onClick = { vm.updateText(id) { it.copy(underline = !it.underline) } },
        label = { Text("Underline") },
    )
    Label("Space between letters")
    Slider(
        value = text.letterSpacing.coerceIn(-0.05f, 0.5f),
        onValueChange = { v -> vm.updateText(id) { it.copy(letterSpacing = v) } },
        valueRange = -0.05f..0.5f,
    )
    Label("Space between lines")
    Slider(
        value = text.lineSpacing.coerceIn(0.7f, 2.5f),
        onValueChange = { v -> vm.updateText(id) { it.copy(lineSpacing = v) } },
        valueRange = 0.7f..2.5f,
    )
}

/**
 * One part of the Effects tab: [section] 0 = outline thickness, 1 = shadow, 2 = glow.
 */
@Composable
fun TextEffectExtras(vm: EditorViewModel, text: TextItem, section: Int) {
    val id = text.id
    when (section) {
        0 -> if (text.outline) {
            Label("Outline thickness")
            Slider(
                value = text.outlineWidth.coerceIn(0.3f, 3f),
                onValueChange = { v -> vm.updateText(id) { it.copy(outlineWidth = v) } },
                valueRange = 0.3f..3f,
            )
        }
        1 -> {
            Toggle("Shadow", text.shadow) { on -> vm.updateText(id) { it.copy(shadow = on) } }
            if (text.shadow) {
                Label("Shadow colour")
                ColourDots(text.shadowColor) { c ->
                    // Keep the shadow's own see-through level.
                    vm.updateText(id) { it.copy(shadowColor = (c and 0xFFFFFF) or (it.shadowColor and (0xFF shl 24))) }
                }
                Label("Shadow size")
                Slider(
                    value = text.shadowStrength.coerceIn(0.3f, 3f),
                    onValueChange = { v -> vm.updateText(id) { it.copy(shadowStrength = v) } },
                    valueRange = 0.3f..3f,
                )
            }
        }
        else -> {
            Toggle("Glow", text.glow) { on -> vm.updateText(id) { it.copy(glow = on) } }
            if (text.glow) {
                Label("Glow colour")
                ColourDots(text.glowColor) { c -> vm.updateText(id) { it.copy(glowColor = c) } }
            }
        }
    }
}

/** The user's own saved looks and "apply to all", shown on the Styles tab. */
@Composable
fun TextStyleExtras(vm: EditorViewModel, text: TextItem) {
    val id = text.id
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { vm.saveTextStyle(id) }) { Text("Save my style") }
        TextButton(onClick = { vm.applyLookToAllTexts(id) }) { Text("Apply to all texts") }
    }
    if (vm.savedStyles.isNotEmpty()) Label("My styles")
    vm.savedStyles.forEach { style ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text(style.text) }
            TextButton(onClick = { vm.useSavedStyle(id, style) }) { Text("Use") }
            TextButton(onClick = { vm.deleteSavedStyle(style) }) { Text("Remove") }
        }
    }
}

/** Keep-moving effects, reveal and "apply to all", shown on the Motion tab. */
@Composable
fun TextMotionExtras(vm: EditorViewModel, text: TextItem) {
    val id = text.id
    Label("Keep moving")
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        LoopFx.entries.forEach { fx ->
            FilterChip(
                selected = text.loop == fx,
                onClick = { vm.updateText(id) { it.copy(loop = fx) } },
                label = { Text(fx.label) },
            )
        }
    }
    Label("How the words appear")
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextReveal.entries.forEach { reveal ->
            FilterChip(
                selected = text.reveal == reveal,
                onClick = { vm.updateText(id) { it.copy(reveal = reveal) } },
                label = { Text(reveal.label) },
            )
        }
    }
    TextButton(onClick = { vm.applyMotionToAllTexts(id) }) { Text("Apply this motion to all texts") }
}
