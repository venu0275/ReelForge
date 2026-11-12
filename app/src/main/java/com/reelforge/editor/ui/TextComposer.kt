package com.reelforge.editor.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import com.reelforge.editor.model.TextItem

/**
 * The full screen for typing a text: a cross to cancel, the box, and Done to save, all on one row
 * so they stay above the keyboard even when the phone is held sideways.
 * With [existing] it changes that text's words; without, it adds a new text at the playhead.
 * Nothing changes in the project until Done. Colour, font and the rest live in the Text tool.
 *
 * It is drawn over the editor (not in a separate window) so it follows the same safe area as the
 * rest of the app: clear of the status bar, the navigation buttons, the camera cut-out and the
 * keyboard.
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
    BackHandler(onBack = onClose)
    // Open the keyboard straight away.
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(150)
        runCatching { focus.requestFocus() }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            // Swallow touches so nothing in the editor underneath reacts.
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
            .safeDrawingPadding()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            TextButton(onClick = onClose) { Text("✕", fontSize = 22.sp) }
            OutlinedTextField(
                value = words,
                onValueChange = { typed -> words = typed.take(300) },
                placeholder = { Text("Enter text") },
                modifier = Modifier.weight(1f).focusRequester(focus),
                minLines = 1,
                maxLines = 3,
            )
            // Always shown; with nothing typed it simply closes the screen.
            Button(onClick = save) { Text("Done") }
        }
    }
}
