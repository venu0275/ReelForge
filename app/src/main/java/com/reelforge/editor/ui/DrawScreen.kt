@file:OptIn(ExperimentalLayoutApi::class)

package com.reelforge.editor.ui

import android.content.res.Configuration
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.reelforge.editor.model.Project

private val PenColors = listOf(
    0xFFFFFFFF, 0xFF000000, 0xFFFF4D5E, 0xFFFFC107, 0xFF4CAF50, 0xFF4DD0E1, 0xFF7C4DFF,
).map { it.toInt() }

/** Full-screen handwriting / drawing pad. The result becomes an image layer at the playhead. */
@Composable
fun DrawScreen(project: Project, onCancel: () -> Unit, onDone: (List<DrawStroke>) -> Unit) {
    val strokes = remember { mutableStateListOf<DrawStroke>() }
    val current = remember { mutableStateListOf<Offset>() }
    var color by remember { mutableIntStateOf(PenColors.first()) }

    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    var width by remember { mutableFloatStateOf(PenSizes[1].second) }
    val I = androidx.compose.material.icons.Icons.Rounded

    val closeButton: @Composable () -> Unit = {
        IconButton(onClick = onCancel) { Icon(I.Close, contentDescription = "Cancel drawing") }
    }
    val editButtons: @Composable () -> Unit = {
        IconButton(
            onClick = { if (strokes.isNotEmpty()) strokes.removeAt(strokes.lastIndex) },
            enabled = strokes.isNotEmpty(),
        ) { Icon(I.Undo, contentDescription = "Undo") }
        IconButton(onClick = { strokes.clear() }, enabled = strokes.isNotEmpty()) {
            Icon(I.DeleteSweep, contentDescription = "Clear all")
        }
    }
    val doneButton: @Composable (Modifier) -> Unit = { modifier ->
        Button(
            onClick = { onDone(strokes.toList()) },
            enabled = strokes.isNotEmpty(),
            modifier = modifier,
            shape = RoundedCornerShape(14.dp),
        ) {
            Icon(I.Check, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Add to video")
        }
    }

    val colors: @Composable () -> Unit = {
        PenColors.forEach { c ->
            val on = color == c
            Box(
                Modifier
                    .size(34.dp)
                    .border(if (on) 2.dp else 0.dp, if (on) MaterialTheme.colorScheme.primary else Color.Transparent, CircleShape)
                    .padding(if (on) 4.dp else 2.dp)
                    .clip(CircleShape)
                    .background(Color(c))
                    .border(1.dp, Color.White.copy(alpha = 0.25f), CircleShape)
                    .clickable { color = c }
            )
        }
    }

    val sizes: @Composable () -> Unit = {
        PenSizes.forEach { (name, w) ->
            val on = width == w
            Surface(
                onClick = { width = w },
                shape = RoundedCornerShape(12.dp),
                color = if (on) MaterialTheme.colorScheme.primary.copy(alpha = 0.2f) else MaterialTheme.colorScheme.surfaceVariant,
                border = if (on) androidx.compose.foundation.BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null,
            ) {
                Column(
                    Modifier.width(52.dp).padding(vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
                        Box(Modifier.size((w * 900).dp).clip(CircleShape).background(Color(color)))
                    }
                    Text(name, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }

    val pad: @Composable (Modifier) -> Unit = { modifier ->
        Box(modifier.padding(12.dp), contentAlignment = Alignment.Center) {
            Canvas(
                Modifier
                    .aspectRatio(project.aspect.w.toFloat() / project.aspect.h)
                    .background(Color(0xFF3A3D45))
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = { start ->
                                current.clear()
                                current.add(Offset(start.x / size.width, start.y / size.height))
                            },
                            onDragEnd = {
                                if (current.isNotEmpty()) strokes.add(DrawStroke(color, current.toList(), width))
                                current.clear()
                            },
                            onDragCancel = { current.clear() },
                            onDrag = { change, _ ->
                                current.add(
                                    Offset(
                                        (change.position.x / size.width).coerceIn(0f, 1f),
                                        (change.position.y / size.height).coerceIn(0f, 1f),
                                    )
                                )
                            },
                        )
                    }
            ) {
                fun draw(points: List<Offset>, argb: Int, share: Float) {
                    val pen = Stroke(width = size.height * share, cap = StrokeCap.Round, join = StrokeJoin.Round)
                    if (points.isEmpty()) return
                    val path = Path()
                    points.forEachIndexed { i, p ->
                        val x = p.x * size.width
                        val y = p.y * size.height
                        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    drawPath(path, Color(argb), style = pen)
                }
                strokes.forEach { draw(it.points, it.color, it.width) }
                draw(current, color, width)
            }
        }
    }

    val root = Modifier
        .fillMaxSize()
        .background(MaterialTheme.colorScheme.background)
        .systemBarsPadding()
        .displayCutoutPadding()

    if (landscape) {
        // Landscape: tools in a column on the left, the drawing pad on the right.
        Row(root) {
            Column(
                Modifier
                    .width(200.dp)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    closeButton()
                    Text("Draw", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    editButtons()
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    ToolSection("Colour", ToolColors.Create)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) { colors() }
                    ToolSection("Pen size", ToolColors.Create)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { sizes() }
                }
                doneButton(Modifier.fillMaxWidth().padding(top = 6.dp))
            }
            pad(Modifier.weight(1f).fillMaxHeight())
        }
    } else {
        Column(root) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                closeButton()
                Text("Draw", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                editButtons()
                doneButton(Modifier.padding(end = 8.dp))
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                colors()
                Spacer(Modifier.width(8.dp))
                sizes()
            }
            pad(Modifier.fillMaxSize())
        }
    }
}

/** Pen sizes as a share of the picture's height. */
private val PenSizes = listOf("Thin" to 0.006f, "Medium" to 0.012f, "Thick" to 0.024f)
