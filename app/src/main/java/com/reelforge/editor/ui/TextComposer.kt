package com.reelforge.editor.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.reelforge.editor.engine.EffectFactory
import com.reelforge.editor.model.TextAlignH
import com.reelforge.editor.model.TextFont
import com.reelforge.editor.model.TextItem

private val ComposerColours = listOf(
    0xFFFFFFFF, 0xFF000000, 0xFFFFEB3B, 0xFFFF5252, 0xFFFF9800, 0xFF69F0AE,
    0xFF40C4FF, 0xFF7C4DFF, 0xFFFF80AB, 0xFF795548, 0xFF9E9E9E,
).map { it.toInt() }

/**
 * A full screen for writing a new text. Nothing is added to the project until Save; after that
 * the text is on the video and can be dragged into place.
 */
@Composable
fun TextComposer(vm: EditorViewModel, onClose: () -> Unit, onSaved: (String) -> Unit) {
    var draft by remember { mutableStateOf(TextItem(text = "")) }
    val focus = remember { FocusRequester() }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            // Open the keyboard straight away.
            LaunchedEffect(Unit) {
                kotlinx.coroutines.delay(150)
                runCatching { focus.requestFocus() }
            }
            Column(
                Modifier
                    .fillMaxSize()
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onClose) { Text("Cancel") }
                    Text("New text", Modifier.weight(1f).padding(horizontal = 8.dp), style = MaterialTheme.typography.titleLarge)
                    Button(
                        enabled = draft.text.isNotBlank(),
                        onClick = {
                            val id = vm.addTextItem(draft.copy(text = draft.text.trim()))
                            if (id.isNotEmpty()) onSaved(id) else onClose()
                        },
                    ) { Text("Save") }
                }

                // How it will look.
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(120.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF3A3F4B)),
                    contentAlignment = Alignment.Center,
                ) {
                    if (draft.text.isBlank()) {
                        Text("Your text will show here", color = Color.White.copy(alpha = 0.5f))
                    } else {
                        val look = draft.copy(x = 0f, y = 0f, rotation = 0f, opacity = 1f)
                        val bitmap = remember(look) {
                            runCatching { EffectFactory.renderText(look, 800).asImageBitmap() }.getOrNull()
                        }
                        if (bitmap != null) {
                            Image(
                                bitmap = bitmap,
                                contentDescription = null,
                                contentScale = ContentScale.Inside,
                                modifier = Modifier.fillMaxSize().padding(8.dp),
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = draft.text,
                    onValueChange = { typed -> draft = draft.copy(text = typed.take(300)) },
                    label = { Text("Type your text") },
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    maxLines = 4,
                )

                Text("Colour", style = MaterialTheme.typography.labelLarge)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ComposerColours.forEach { c ->
                        Box(
                            Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(Color(c))
                                .border(
                                    if (draft.color == c) 3.dp else 1.dp,
                                    if (draft.color == c) MaterialTheme.colorScheme.primary else Color.Gray,
                                    CircleShape,
                                )
                                .clickable { draft = draft.copy(color = c) },
                        )
                    }
                }

                Text("Size", style = MaterialTheme.typography.labelLarge)
                Slider(
                    value = draft.size.coerceIn(0.03f, 0.3f),
                    onValueChange = { v -> draft = draft.copy(size = v) },
                    valueRange = 0.03f..0.3f,
                )

                Text("Font", style = MaterialTheme.typography.labelLarge)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextFont.entries.forEach { font ->
                        FilterChip(
                            selected = draft.font == font,
                            onClick = { draft = draft.copy(font = font, fontPath = null) },
                            label = { Text(font.label) },
                        )
                    }
                }

                Text("Style", style = MaterialTheme.typography.labelLarge)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(draft.bold, onClick = { draft = draft.copy(bold = !draft.bold) }, label = { Text("Bold") })
                    FilterChip(draft.italic, onClick = { draft = draft.copy(italic = !draft.italic) }, label = { Text("Italic") })
                    FilterChip(draft.outline, onClick = { draft = draft.copy(outline = !draft.outline) }, label = { Text("Outline") })
                    FilterChip(
                        draft.background,
                        onClick = { draft = draft.copy(background = !draft.background) },
                        label = { Text("Background box") },
                    )
                }

                Text("Line up", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextAlignH.entries.forEach { a ->
                        FilterChip(draft.align == a, onClick = { draft = draft.copy(align = a) }, label = { Text(a.label) })
                    }
                }

                Text("Start position (you can drag it afterwards)", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Top" to 0.75f, "Centre" to 0f, "Bottom" to -0.75f).forEach { (name, y) ->
                        FilterChip(draft.y == y, onClick = { draft = draft.copy(x = 0f, y = y) }, label = { Text(name) })
                    }
                }

                Text(
                    "After Save, drag the text on the video to move it, pinch to resize and twist to turn. " +
                        "Tap it for more options (background colour, animation, timing).",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
