package com.reelforge.editor.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/** What the fingers have done to one item on the preview since they touched it. */
@Stable
class DragState {
    /** Movement in screen pixels. */
    var pan by mutableStateOf(Offset.Zero)

    /** Pinch size change; 1 means unchanged. */
    var zoom by mutableFloatStateOf(1f)

    /** Twist in degrees, clockwise. */
    var turn by mutableFloatStateOf(0f)

    var active by mutableStateOf(false)

    fun reset() {
        pan = Offset.Zero
        zoom = 1f
        turn = 0f
        active = false
    }
}

@Composable
fun rememberDragState(): DragState = remember { DragState() }

/**
 * Finger control for one item on the preview: drag to move, pinch to resize, twist to turn.
 * While the fingers are down only [state] changes (so the item follows them); [onEnd] is called
 * once when they lift, with the total movement. A touch that does not move is a tap.
 * Put this before graphicsLayer in the modifier chain so movement is measured in screen pixels.
 */
fun Modifier.itemGestures(
    state: DragState,
    enabled: Boolean,
    onTap: () -> Unit,
    onEnd: (pan: Offset, zoom: Float, turn: Float) -> Unit,
): Modifier = composed {
    val tap by rememberUpdatedState(onTap)
    val end by rememberUpdatedState(onEnd)
    val on by rememberUpdatedState(enabled)
    pointerInput(state) {
        awaitEachGesture {
            try {
                awaitFirstDown(requireUnconsumed = false)
                var pan = Offset.Zero
                var zoom = 1f
                var turn = 0f
                var moved = false
                while (true) {
                    val event = awaitPointerEvent()
                    if (on) {
                        pan += event.calculatePan()
                        zoom *= event.calculateZoom()
                        turn += event.calculateRotation()
                        if (!moved) {
                            moved = pan.getDistance() > viewConfiguration.touchSlop ||
                                abs(zoom - 1f) > 0.04f || abs(turn) > 3f
                        }
                        if (moved) {
                            state.pan = pan
                            state.zoom = zoom
                            state.turn = turn
                            state.active = true
                            event.changes.forEach { it.consume() }
                        }
                    }
                    if (event.changes.none { it.pressed }) break
                }
                if (moved) end(pan, zoom, turn) else tap()
            } finally {
                // Also runs when the touch is taken away by something else, so nothing stays shifted.
                state.reset()
            }
        }
    }
}

/** A thin white frame around the item while it is being moved. Put this after graphicsLayer. */
fun Modifier.dragOutline(state: DragState): Modifier =
    if (state.active) border(1.dp, Color.White) else this

/**
 * Draws the faded "rest of the file" part of a timeline bar, from [startPx] for [widthPx].
 * It may reach past the row's own width (for music that is longer than the video).
 */
fun Modifier.ghostBar(startPx: Float, widthPx: Float, color: Color): Modifier =
    if (widthPx <= 0f) this else drawBehind {
        val pad = 2.dp.toPx()
        val corner = 4.dp.toPx()
        drawRoundRect(
            color = color,
            topLeft = Offset(startPx, pad),
            size = Size(widthPx, (size.height - 2 * pad).coerceAtLeast(0f)),
            cornerRadius = CornerRadius(corner, corner),
        )
    }
