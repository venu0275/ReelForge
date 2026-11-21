package com.reelforge.editor.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.delay

private fun ToastKind.color() = when (this) {
    ToastKind.SUCCESS -> Color(0xFF66BB6A)
    ToastKind.INFO -> Color(0xFF64B5F6)
    ToastKind.WARNING -> Color(0xFFFFB74D)
    ToastKind.ERROR -> Color(0xFFFF5370)
}

private fun ToastKind.icon() = when (this) {
    ToastKind.SUCCESS -> Icons.Rounded.CheckCircle
    ToastKind.INFO -> Icons.Rounded.Info
    ToastKind.WARNING -> Icons.Rounded.Lightbulb
    ToastKind.ERROR -> Icons.Rounded.ErrorOutline
}

/** How long a toast stays: longer for longer words, problems and ones with Undo. */
fun toastMillis(toast: Toast): Long {
    val base = 1_800L + toast.text.length * 35L
    val extra = when {
        toast.kind == ToastKind.ERROR -> 1_500L
        toast.undo -> 1_200L
        else -> 0L
    }
    return (base + extra).coerceIn(2_200L, 6_500L)
}

/**
 * Shows the editor's toast floating at the top of the screen, over everything, without taking
 * touches from the editor. Tap it to close it early.
 */
@Composable
fun ToastHost(vm: EditorViewModel) {
    val toast = vm.toast
    // The last toast stays drawn while it fades out.
    var shown by remember { mutableStateOf<Toast?>(null) }
    if (toast != null) shown = toast

    LaunchedEffect(toast?.id) {
        val t = toast ?: return@LaunchedEffect
        delay(toastMillis(t))
        vm.dismissToast(t.id)
    }

    val current = shown ?: return
    Popup(
        alignment = Alignment.TopCenter,
        offset = IntOffset(0, 24),
        properties = PopupProperties(focusable = false),
    ) {
        AnimatedVisibility(
            visible = toast != null,
            enter = slideInVertically { -it } + fadeIn(),
            exit = slideOutVertically { -it } + fadeOut(),
        ) {
            val color = current.kind.color()
            Surface(
                onClick = { vm.dismissToast(current.id) },
                shape = RoundedCornerShape(18.dp),
                color = Color(0xFF23262D),
                shadowElevation = 10.dp,
                tonalElevation = 4.dp,
                border = androidx.compose.foundation.BorderStroke(1.dp, color.copy(alpha = 0.45f)),
                modifier = Modifier.padding(horizontal = 16.dp).widthIn(min = 200.dp, max = 520.dp),
            ) {
                Row(
                    Modifier.padding(start = 10.dp, end = if (current.undo) 4.dp else 14.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.size(30.dp).background(color.copy(alpha = 0.2f), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(current.kind.icon(), contentDescription = null, tint = color, modifier = Modifier.size(19.dp))
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(
                        current.text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (current.undo) {
                        Spacer(Modifier.width(6.dp))
                        TextButton(onClick = {
                            vm.dismissToast(current.id)
                            vm.undo()
                        }) {
                            Icon(Icons.Rounded.Undo, contentDescription = null, modifier = Modifier.size(17.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Undo", fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }
}
