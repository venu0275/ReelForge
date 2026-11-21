package com.reelforge.editor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * Colours for each group of tools, so similar tools look alike and are easy to find again.
 */
object ToolColors {
    val Create = Color(0xFFFF6B7A)   // media, text, captions, layers, drawing
    val Sound = Color(0xFF4DD0E1)    // voice, sounds, music
    val Style = Color(0xFFFFB74D)    // templates, ratio, backdrop, stickers
    val Finish = Color(0xFFB388FF)   // cover, screen recording, settings
    val Cut = Color(0xFFFF4D5E)      // trims and splits
    val Time = Color(0xFF64B5F6)     // speed, reverse, volume, replace
    val Look = Color(0xFF81C784)     // filters, adjust, fade, photo zoom
    val Frame = Color(0xFFFFD54F)    // rotate, mirror, fit, position
    val Arrange = Color(0xFFB0BEC5)  // copy, move, delete
}

/** A small heading above a group of tool tiles. */
@Composable
fun ToolSection(title: String, color: Color) {
    Row(
        Modifier.padding(start = 4.dp, top = 10.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(width = 3.dp, height = 12.dp).background(color, RoundedCornerShape(2.dp)))
        Spacer(Modifier.width(6.dp))
        Text(
            title.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * One tool: a coloured round icon with its name under it. [active] (recording, working) fills the
 * tile with [color]; [label] may be a short progress text then.
 */
@Composable
fun ToolTile(
    label: String,
    icon: ImageVector,
    color: Color,
    enabled: Boolean = true,
    active: Boolean = false,
    iconContent: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(16.dp),
        color = if (active) color.copy(alpha = 0.28f) else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.width(80.dp).alpha(if (enabled) 1f else 0.38f),
    ) {
        Column(
            Modifier.padding(horizontal = 4.dp, vertical = 9.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier
                    .size(38.dp)
                    .background(if (active) color else color.copy(alpha = 0.18f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                if (iconContent != null) {
                    iconContent()
                } else {
                    Icon(
                        icon,
                        contentDescription = null,
                        tint = if (active) Color.Black else color,
                        modifier = Modifier.size(21.dp),
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.height(32.dp),
            )
        }
    }
}

/** The top of an open tool: its icon, name and one line saying what it does. */
@Composable
fun ToolHeader(title: String, help: String, icon: ImageVector, color: Color, close: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(end = 12.dp, top = 4.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        close()
        Box(
            Modifier.size(34.dp).background(color.copy(alpha = 0.18f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(19.dp))
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (help.isNotEmpty()) {
                Text(
                    help,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * The app's dialog: an icon in a coloured circle, a title, the message and the buttons stacked
 * full-width (main action first), so choices are clear and easy to tap. Same parameters as
 * Material's AlertDialog, plus [icon] and [color].
 */
@Composable
fun CutDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    icon: ImageVector? = null,
    color: Color = ToolColors.Cut,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    dismissOnClickOutside: Boolean = true,
    /** Centre the message; turn off for dialogs holding options. */
    centred: Boolean = true,
) {
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(dismissOnClickOutside = dismissOnClickOutside),
    ) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            modifier = modifier.widthIn(max = 420.dp),
        ) {
            Column(
                Modifier.padding(horizontal = 22.dp, vertical = 20.dp).verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (icon != null) {
                    Box(
                        Modifier.size(52.dp).background(color.copy(alpha = 0.18f), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(28.dp))
                    }
                    Spacer(Modifier.height(12.dp))
                }
                if (title != null) {
                    CompositionLocalProvider(
                        androidx.compose.material3.LocalTextStyle provides MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
                        ),
                    ) { title() }
                    Spacer(Modifier.height(8.dp))
                }
                if (text != null) {
                    CompositionLocalProvider(
                        androidx.compose.material3.LocalTextStyle provides MaterialTheme.typography.bodyMedium.copy(
                            textAlign = if (centred) TextAlign.Center else TextAlign.Start,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    ) { Box(Modifier.fillMaxWidth(), contentAlignment = if (centred) Alignment.Center else Alignment.TopStart) { text() } }
                    Spacer(Modifier.height(18.dp))
                }
                DialogButtons {
                    confirmButton()
                    dismissButton?.invoke()
                }
            }
        }
    }
}

/** Stacks dialog buttons full-width under each other. */
@Composable
private fun DialogButtons(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        content = content,
    )
}

/** The main button of a dialog: filled and full width. */
@Composable
fun DialogMainButton(text: String, onClick: () -> Unit) {
    androidx.compose.material3.Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(48.dp),
        shape = RoundedCornerShape(14.dp),
    ) { Text(text, fontWeight = FontWeight.SemiBold) }
}

/** Another choice in a dialog: outlined, full width. */
@Composable
fun DialogOtherButton(text: String, onClick: () -> Unit) {
    androidx.compose.material3.OutlinedButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(46.dp),
        shape = RoundedCornerShape(14.dp),
    ) { Text(text) }
}

/** The quiet "cancel" choice in a dialog. */
@Composable
fun DialogQuietButton(text: String, onClick: () -> Unit) {
    androidx.compose.material3.TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A dialog's main action: filled, full width. Takes content like Material's Button. */
@Composable
fun WideButton(onClick: () -> Unit, content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    androidx.compose.material3.Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(48.dp),
        shape = RoundedCornerShape(14.dp),
        content = content,
    )
}

/** A dialog's second choice: outlined, full width. */
@Composable
fun WideOutlinedButton(onClick: () -> Unit, content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    androidx.compose.material3.OutlinedButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(46.dp),
        shape = RoundedCornerShape(14.dp),
        content = content,
    )
}
