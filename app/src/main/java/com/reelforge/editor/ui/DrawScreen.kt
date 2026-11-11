package com.reelforge.editor.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding()
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onCancel) { Text("Cancel") }
            Text("Draw", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            TextButton(
                onClick = { if (strokes.isNotEmpty()) strokes.removeAt(strokes.lastIndex) },
                enabled = strokes.isNotEmpty(),
            ) { Text("Undo") }
            TextButton(onClick = { onDone(strokes.toList()) }, enabled = strokes.isNotEmpty()) {
                Text("Done")
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PenColors.forEach { c ->
                Box(
                    Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(Color(c))
                        .border(
                            width = if (color == c) 3.dp else 1.dp,
                            color = if (color == c) MaterialTheme.colorScheme.primary else Color.Gray,
                            shape = CircleShape,
                        )
                        .clickable { color = c }
                )
            }
        }
        Box(Modifier.fillMaxSize().padding(12.dp), contentAlignment = Alignment.Center) {
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
                                if (current.isNotEmpty()) strokes.add(DrawStroke(color, current.toList()))
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
                val pen = Stroke(
                    width = size.height * 0.012f,
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round,
                )
                fun draw(points: List<Offset>, argb: Int) {
                    if (points.isEmpty()) return
                    val path = Path()
                    points.forEachIndexed { i, p ->
                        val x = p.x * size.width
                        val y = p.y * size.height
                        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    drawPath(path, Color(argb), style = pen)
                }
                strokes.forEach { draw(it.points, it.color) }
                draw(current, color)
            }
        }
    }
}
