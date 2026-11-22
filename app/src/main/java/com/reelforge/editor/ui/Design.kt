package com.reelforge.editor.ui

import androidx.compose.foundation.background
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.rounded.*
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.animateFloat
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
/** Shows a hint toast; the editor provides it, so tiles can explain why they are switched off. */
val LocalHint = androidx.compose.runtime.staticCompositionLocalOf<(String) -> Unit> { {} }

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
    /** Said in a toast when the tile is tapped while switched off. */
    whyOff: String? = null,
    /** Pulses to point the user at this tile (used by the guided tours). */
    highlight: Boolean = false,
    /** Shows a finger tapping the tile (used by the guided tours). */
    tapping: Boolean = false,
    /** A small dot in the corner: something new is waiting here (from the Store). */
    dot: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val hint = LocalHint.current
    val pulse = if (highlight) {
        val t = androidx.compose.animation.core.rememberInfiniteTransition(label = "pulse")
        t.animateFloat(
            0.35f, 1f,
            androidx.compose.animation.core.infiniteRepeatable(
                androidx.compose.animation.core.tween(600),
                androidx.compose.animation.core.RepeatMode.Reverse,
            ),
            label = "pulse",
        ).value
    } else 0f
    val press by androidx.compose.animation.core.animateFloatAsState(if (tapping) 0.9f else 1f, label = "press")
    Box(modifier) {
    Surface(
        onClick = { if (enabled) onClick() else if (whyOff != null) hint(whyOff) },
        enabled = enabled || whyOff != null,
        shape = RoundedCornerShape(16.dp),
        color = if (active || highlight) color.copy(alpha = 0.28f) else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = if (highlight) androidx.compose.foundation.BorderStroke(2.dp, color.copy(alpha = pulse)) else null,
        modifier = Modifier
            .width(80.dp)
            .graphicsLayer { scaleX = press; scaleY = press }
            .alpha(if (enabled) 1f else 0.38f),
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
    if (highlight) {
        // A finger pointing at the tile, pressing it on "tapping".
        Icon(
            androidx.compose.material.icons.Icons.Rounded.TouchApp, contentDescription = null,
            tint = Color.White,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 2.dp, bottom = 2.dp)
                .size(if (tapping) 24.dp else 28.dp)
                .background(color, CircleShape)
                .padding(3.dp),
        )
    }
    if (dot) {
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .padding(top = 6.dp, end = 8.dp)
                .size(10.dp)
                .background(Color(0xFFFF5252), CircleShape)
                .semantics { contentDescription = "New" },
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
    // Same look everywhere; on short screens (a phone held sideways) a little smaller, so more of
    // the content fits above the buttons.
    val short = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp < 500
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(dismissOnClickOutside = dismissOnClickOutside),
    ) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            modifier = modifier.widthIn(max = if (short) 480.dp else 420.dp),
        ) {
            // Only the message scrolls, so the buttons always stay in view. Content passed in must
            // not scroll by itself (a scroll inside a scroll crashes).
            Column(
                Modifier.padding(horizontal = 22.dp, vertical = if (short) 14.dp else 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (icon != null) {
                    Box(
                        Modifier.size(if (short) 40.dp else 52.dp).background(color.copy(alpha = 0.18f), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(if (short) 22.dp else 28.dp))
                    }
                    Spacer(Modifier.height(if (short) 6.dp else 12.dp))
                }
                if (title != null) {
                    CompositionLocalProvider(
                        androidx.compose.material3.LocalTextStyle provides
                            (if (short) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge).copy(
                                fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
                            ),
                    ) { title() }
                    Spacer(Modifier.height(if (short) 4.dp else 8.dp))
                }
                if (text != null) {
                    CompositionLocalProvider(
                        androidx.compose.material3.LocalTextStyle provides MaterialTheme.typography.bodyMedium.copy(
                            textAlign = if (centred) TextAlign.Center else TextAlign.Start,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .weight(1f, fill = false)
                                .verticalScroll(rememberScrollState()),
                            contentAlignment = if (centred) Alignment.TopCenter else Alignment.TopStart,
                        ) { text() }
                    }
                    Spacer(Modifier.height(if (short) 10.dp else 18.dp))
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
    val short = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp < 500
    androidx.compose.material3.Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(if (short) 42.dp else 48.dp),
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

// ------------------------------------------------------------------ inside the tools

/**
 * A big, clear action inside a tool ("Add text", "Record voice"): coloured icon, a name and a
 * line saying what happens, across the full width.
 */
@Composable
fun ActionCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    color: Color,
    enabled: Boolean = true,
    main: Boolean = false,
    /** Said in a toast when the card is tapped while switched off. */
    whyOff: String? = null,
    onClick: () -> Unit,
) {
    val hint = LocalHint.current
    Surface(
        onClick = { if (enabled) onClick() else if (whyOff != null) hint(whyOff) },
        enabled = enabled || whyOff != null,
        shape = RoundedCornerShape(16.dp),
        color = if (main) color.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surfaceVariant,
        border = if (main) androidx.compose.foundation.BorderStroke(1.dp, color.copy(alpha = 0.55f)) else null,
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp).alpha(if (enabled) 1f else 0.4f),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(38.dp).background(if (main) color else color.copy(alpha = 0.18f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, null, tint = if (main) Color.Black else color, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                if (subtitle.isNotEmpty()) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Icon(
                androidx.compose.material.icons.Icons.AutoMirrored.Rounded.KeyboardArrowRight, null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Several small actions side by side, each a tile with an icon (e.g. Add video / Add photo / Draw). */
@Composable
fun ActionRow(content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

/** One of the small actions in an [ActionRow]. */
@Composable
fun androidx.compose.foundation.layout.RowScope.SmallAction(
    label: String,
    icon: ImageVector,
    color: Color,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.weight(1f).alpha(if (enabled) 1f else 0.4f),
    ) {
        Column(Modifier.padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.size(34.dp).background(color.copy(alpha = 0.18f), CircleShape),
                contentAlignment = Alignment.Center,
            ) { Icon(icon, null, tint = color, modifier = Modifier.size(19.dp)) }
            Spacer(Modifier.height(5.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center, maxLines = 2)
        }
    }
}

/**
 * Something already in the video (a text, a layer, a sound), with Edit and Delete as icons.
 * Tapping the card edits it too.
 */
@Composable
fun ItemCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    color: Color,
    onEdit: (() -> Unit)?,
    onDelete: (() -> Unit)?,
) {
    Surface(
        onClick = { onEdit?.invoke() },
        enabled = onEdit != null,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
    ) {
        Row(Modifier.padding(start = 10.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(32.dp).background(color.copy(alpha = 0.18f), RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center,
            ) { Icon(icon, null, tint = color, modifier = Modifier.size(18.dp)) }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (subtitle.isNotEmpty()) {
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (onEdit != null) {
                androidx.compose.material3.IconButton(onClick = onEdit) {
                    Icon(androidx.compose.material.icons.Icons.Rounded.Edit, "Edit", modifier = Modifier.size(20.dp))
                }
            }
            if (onDelete != null) {
                androidx.compose.material3.IconButton(onClick = onDelete) {
                    Icon(
                        androidx.compose.material.icons.Icons.Rounded.DeleteOutline, "Delete",
                        tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

/** A tip or explanation in a soft box with a light-bulb, so help text is easy to tell from settings. */
@Composable
fun TipBox(text: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
            .padding(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            androidx.compose.material.icons.Icons.Rounded.Lightbulb, null,
            tint = ToolColors.Style, modifier = Modifier.size(16.dp).padding(top = 1.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A small "Delete all" style link, quiet but clear, at the end of a list. */
@Composable
fun QuietAction(label: String, icon: ImageVector, onClick: () -> Unit) {
    androidx.compose.material3.TextButton(onClick = onClick) {
        Icon(icon, null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(label)
    }
}

// ------------------------------------------------------------------ rows that scroll sideways

/** Rows that already showed their "there is more" peek in this run of the app. */
private val peekedRows = mutableSetOf<Int>()

/**
 * A row that scrolls sideways and shows that it does: a fade and an arrow at an edge with more
 * items behind it (tap the arrow to scroll), and once per app run a small slide to the side and
 * back, so it is clear more can be found by swiping.
 */
@Composable
fun HintRow(
    modifier: Modifier = Modifier,
    spacing: androidx.compose.ui.unit.Dp = 8.dp,
    verticalAlignment: Alignment.Vertical = Alignment.Top,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    val state = rememberScrollState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val key = androidx.compose.runtime.currentCompositeKeyHash
    androidx.compose.runtime.LaunchedEffect(state.maxValue > 0) {
        if (state.maxValue > 0 && peekedRows.add(key)) {
            kotlinx.coroutines.delay(450)
            state.animateScrollTo(minOf(state.maxValue, 140), androidx.compose.animation.core.tween(420))
            kotlinx.coroutines.delay(200)
            state.animateScrollTo(0, androidx.compose.animation.core.tween(380))
        }
    }
    val bg = MaterialTheme.colorScheme.surface
    Box(modifier) {
        Row(
            Modifier.horizontalScroll(state),
            horizontalArrangement = Arrangement.spacedBy(spacing),
            verticalAlignment = verticalAlignment,
            content = content,
        )
        if (state.canScrollBackward) {
            EdgeHint(Alignment.CenterStart, bg, left = true) {
                scope.launch { state.animateScrollTo((state.value - state.viewportSize * 2 / 3).coerceAtLeast(0)) }
            }
        }
        if (state.canScrollForward) {
            EdgeHint(Alignment.CenterEnd, bg, left = false) {
                scope.launch { state.animateScrollTo(state.value + state.viewportSize * 2 / 3) }
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.EdgeHint(
    align: Alignment,
    bg: Color,
    left: Boolean,
    onClick: () -> Unit,
) {
    val fade = if (left) listOf(bg, bg.copy(alpha = 0f)) else listOf(bg.copy(alpha = 0f), bg)
    Box(
        Modifier
            .align(align)
            .matchParentSize()
            .wrapContentWidth(if (left) Alignment.Start else Alignment.End),
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .width(44.dp)
                .background(androidx.compose.ui.graphics.Brush.horizontalGradient(fade))
                .clickable(onClick = onClick),
            contentAlignment = if (left) Alignment.CenterStart else Alignment.CenterEnd,
        ) {
            Box(
                Modifier.size(24.dp).background(MaterialTheme.colorScheme.surfaceVariant, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (left) androidx.compose.material.icons.Icons.AutoMirrored.Rounded.KeyboardArrowLeft
                    else androidx.compose.material.icons.Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                    contentDescription = if (left) "Scroll back" else "More",
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}
