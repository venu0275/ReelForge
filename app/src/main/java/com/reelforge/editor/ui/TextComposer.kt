package com.reelforge.editor.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.reelforge.editor.model.TextItem

/**
 * The full screen for typing a text: a cross to cancel, a tick to save, one box and the keyboard.
 * With [existing] it changes that text's words; without, it adds a new text at the playhead.
 * Nothing changes in the project until the tick. Colour, font and the rest live in the Text tool.
 */
@Composable
fun TextComposer(vm: EditorViewModel, existing: TextItem?, onClose: () -> Unit, onSaved: (String) -> Unit) {
    var words by remember { mutableStateOf(existing?.text ?: "") }
    val focus = remember { FocusRequester() }
    val save: () -> Unit = {
        val typed = words.trim()
        if (typed.isEmpty()) {
            onClose()
        } else if (existing != null) {
            vm.updateText(existing.id) { it.copy(text = typed) }
            onSaved(existing.id)
        } else {
            val id = vm.addTextItem(TextItem(text = typed))
            if (id.isNotEmpty()) onSaved(id) else onClose()
        }
    }

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
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onClose) { Text("✕", fontSize = 24.sp) }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = save, enabled = words.isNotBlank()) { Text("✓", fontSize = 26.sp) }
                }
                OutlinedTextField(
                    value = words,
                    onValueChange = { typed -> words = typed.take(300) },
                    placeholder = { Text("Enter text") },
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    minLines = 2,
                    maxLines = 5,
                )
            }
        }
    }
}
