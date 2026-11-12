package com.reelforge.editor.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.reelforge.editor.model.Cut

enum class CutKind { LEFT, SPLIT, RIGHT, FREEZE }

/**
 * A small picture of what a cut does to a clip: the solid parts stay, the dashed part is removed
 * and the upright line is the playhead.
 */
@Composable
fun CutIcon(kind: CutKind, modifier: Modifier = Modifier) {
    val color = LocalContentColor.current
    Canvas(modifier.size(width = 34.dp, height = 14.dp)) {
        val w = size.width
        val h = size.height
        val corner = CornerRadius(h * 0.22f)
        val mid = w / 2f
        val gap = w * 0.07f
        fun kept(from: Float, to: Float) =
            drawRoundRect(color, Offset(from, 0f), Size(to - from, h), corner)
        fun removed(from: Float, to: Float) = drawRoundRect(
            color.copy(alpha = 0.55f), Offset(from, 0f), Size(to - from, h), corner,
            style = Stroke(
                width = h * 0.12f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(h * 0.28f, h * 0.2f)),
            ),
        )
        when (kind) {
            CutKind.LEFT -> {
                removed(0f, mid - gap)
                kept(mid + gap, w)
            }
            CutKind.RIGHT -> {
                kept(0f, mid - gap)
                removed(mid + gap, w)
            }
            CutKind.SPLIT -> {
                kept(0f, mid - gap)
                kept(mid + gap, w)
            }
            CutKind.FREEZE -> {
                // Two parts with a still frame held between them.
                kept(0f, w * 0.3f)
                drawRoundRect(
                    color, Offset(w * 0.38f, 0f), Size(w * 0.24f, h), corner,
                    style = Stroke(width = h * 0.14f),
                )
                kept(w * 0.7f, w)
            }
        }
        if (kind != CutKind.FREEZE) {
            drawLine(color, Offset(mid, -h * 0.25f), Offset(mid, h * 1.25f), strokeWidth = h * 0.12f)
        }
    }
}

/**
 * Trim left / Split / Trim right for one text, layer or sound, cutting at the red line. The
 * buttons are greyed out unless the red line is on the item.
 */
@Composable
fun CutRow(enabled: Boolean, onCut: (Cut) -> Unit) {
    Column {
        Text("Cut at the red line", style = MaterialTheme.typography.labelLarge)
        Row(
            Modifier.fillMaxWidth().padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            CutButton("Trim left", CutKind.LEFT, enabled, Modifier.weight(1f)) { onCut(Cut.LEFT) }
            CutButton("Split", CutKind.SPLIT, enabled, Modifier.weight(1f)) { onCut(Cut.SPLIT) }
            CutButton("Trim right", CutKind.RIGHT, enabled, Modifier.weight(1f)) { onCut(Cut.RIGHT) }
        }
        if (!enabled) {
            Text(
                "Move the red line onto this item to cut it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CutButton(label: String, kind: CutKind, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        modifier = modifier.alpha(if (enabled) 1f else 0.4f),
    ) {
        Column(
            Modifier.padding(horizontal = 6.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            CutIcon(kind)
            Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
        }
    }
}
