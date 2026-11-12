package com.reelforge.editor.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
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
                awaitFirstDown(requireUnconsumed = false).consume()
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

/** Calls [action] when an empty part of this area is tapped (items on it take their own taps). */
fun Modifier.onEmptyTap(action: () -> Unit): Modifier = composed {
    val run by rememberUpdatedState(action)
    pointerInput(Unit) { detectTapGestures { run() } }
}

private enum class HandleKind { TURN, SIZE, DELETE, EDIT }

private fun turned(v: Offset, degrees: Float): Offset {
    val a = Math.toRadians(degrees.toDouble())
    val c = cos(a).toFloat()
    val s = sin(a).toFloat()
    return Offset(v.x * c - v.y * s, v.x * s + v.y * c)
}

/**
 * The frame and round handles around the selected item on the preview: turn (top right),
 * size (bottom right), delete (top left) and, for text, edit (bottom left).
 *
 * [centre] is the item's middle in frame pixels, [halfWidth] / [halfHeight] its half size and
 * [angle] its turn in degrees clockwise, all as drawn before any live gesture in [state].
 * Dragging a handle updates [state] so the item follows; [onCommit] is called once on release.
 */
@Composable
fun BoxScope.SelectionHandles(
    centre: Offset,
    halfWidth: Float,
    halfHeight: Float,
    angle: Float,
    state: DragState,
    onCommit: (zoom: Float, turn: Float) -> Unit,
    onEdit: (() -> Unit)?,
    onDelete: () -> Unit,
) {
    val liveCentre = centre + state.pan
    val liveAngle = angle + state.turn
    val hw = halfWidth * state.zoom
    val hh = halfHeight * state.zoom
    val topLeft = liveCentre + turned(Offset(-hw, -hh), liveAngle)
    val topRight = liveCentre + turned(Offset(hw, -hh), liveAngle)
    val bottomRight = liveCentre + turned(Offset(hw, hh), liveAngle)
    val bottomLeft = liveCentre + turned(Offset(-hw, hh), liveAngle)

    // The frame.
    Box(
        Modifier.matchParentSize().drawBehind {
            val w = 1.5.dp.toPx()
            drawLine(Color.White, topLeft, topRight, w)
            drawLine(Color.White, topRight, bottomRight, w)
            drawLine(Color.White, bottomRight, bottomLeft, w)
            drawLine(Color.White, bottomLeft, topLeft, w)
        }
    )

    // Where each drag started, measured from the item's middle.
    val turnStart = remember { mutableStateOf(Offset.Zero) }
    val sizeStart = remember { mutableStateOf(Offset.Zero) }
    val finish: () -> Unit = {
        onCommit(state.zoom, state.turn)
        state.reset()
    }

    Handle(
        topRight, HandleKind.TURN,
        onStart = { turnStart.value = turned(Offset(halfWidth, -halfHeight), angle) },
        onDrag = { total ->
            val from = turnStart.value
            val to = from + total
            var degrees = Math.toDegrees((atan2(to.y, to.x) - atan2(from.y, from.x)).toDouble()).toFloat()
            if (degrees > 180f) degrees -= 360f
            if (degrees < -180f) degrees += 360f
            state.turn = degrees
            state.active = true
        },
        onEnd = finish,
        onCancel = { state.reset() },
    )
    Handle(
        bottomRight, HandleKind.SIZE,
        onStart = { sizeStart.value = turned(Offset(halfWidth, halfHeight), angle) },
        onDrag = { total ->
            val from = sizeStart.value
            val before = from.getDistance().coerceAtLeast(1f)
            state.zoom = ((from + total).getDistance() / before).coerceIn(0.2f, 6f)
            state.active = true
        },
        onEnd = finish,
        onCancel = { state.reset() },
    )
    Handle(topLeft, HandleKind.DELETE, onClick = onDelete)
    if (onEdit != null) Handle(bottomLeft, HandleKind.EDIT, onClick = onEdit)
}

@Composable
private fun BoxScope.Handle(
    at: Offset,
    kind: HandleKind,
    onClick: (() -> Unit)? = null,
    onStart: () -> Unit = {},
    onDrag: ((total: Offset) -> Unit)? = null,
    onEnd: () -> Unit = {},
    onCancel: () -> Unit = {},
) {
    val radiusPx = with(LocalDensity.current) { 14.dp.toPx() }
    val start by rememberUpdatedState(onStart)
    val drag by rememberUpdatedState(onDrag)
    val end by rememberUpdatedState(onEnd)
    val cancel by rememberUpdatedState(onCancel)
    val click by rememberUpdatedState(onClick)
    val draggable = onDrag != null
    Box(
        Modifier
            .align(Alignment.TopStart)
            .offset { IntOffset((at.x - radiusPx).roundToInt(), (at.y - radiusPx).roundToInt()) }
            .size(28.dp)
            .clip(CircleShape)
            .background(Color.White)
            .pointerInput(kind, draggable) {
                if (draggable) {
                    var total = Offset.Zero
                    detectDragGestures(
                        onDragStart = {
                            total = Offset.Zero
                            start()
                        },
                        onDragEnd = { end() },
                        onDragCancel = { cancel() },
                    ) { change, amount ->
                        change.consume()
                        total += amount
                        drag?.invoke(total)
                    }
                } else {
                    detectTapGestures { click?.invoke() }
                }
            }
            .drawBehind {
                val ink = Color(0xFF202124)
                val w = 2.dp.toPx()
                val s = size.minDimension
                when (kind) {
                    HandleKind.TURN -> {
                        drawArc(
                            ink, startAngle = 40f, sweepAngle = 280f, useCenter = false,
                            topLeft = Offset(s * 0.28f, s * 0.28f), size = Size(s * 0.44f, s * 0.44f),
                            style = Stroke(w),
                        )
                        drawCircle(ink, radius = w, center = Offset(s * 0.70f, s * 0.38f))
                    }
                    HandleKind.SIZE -> {
                        drawLine(ink, Offset(s * 0.30f, s * 0.30f), Offset(s * 0.70f, s * 0.70f), w)
                        drawLine(ink, Offset(s * 0.30f, s * 0.30f), Offset(s * 0.46f, s * 0.30f), w)
                        drawLine(ink, Offset(s * 0.30f, s * 0.30f), Offset(s * 0.30f, s * 0.46f), w)
                        drawLine(ink, Offset(s * 0.70f, s * 0.70f), Offset(s * 0.54f, s * 0.70f), w)
                        drawLine(ink, Offset(s * 0.70f, s * 0.70f), Offset(s * 0.70f, s * 0.54f), w)
                    }
                    HandleKind.DELETE -> {
                        drawLine(Color(0xFFD32F2F), Offset(s * 0.32f, s * 0.32f), Offset(s * 0.68f, s * 0.68f), w)
                        drawLine(Color(0xFFD32F2F), Offset(s * 0.68f, s * 0.32f), Offset(s * 0.32f, s * 0.68f), w)
                    }
                    HandleKind.EDIT -> {
                        drawLine(ink, Offset(s * 0.36f, s * 0.64f), Offset(s * 0.68f, s * 0.32f), w * 1.6f)
                        drawCircle(ink, radius = w * 0.8f, center = Offset(s * 0.31f, s * 0.69f))
                    }
                }
            }
    )
}
