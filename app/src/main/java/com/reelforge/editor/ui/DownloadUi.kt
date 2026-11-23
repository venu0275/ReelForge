package com.reelforge.editor.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** Where a downloadable plug-in is: the one download button and bar show each of these. */
sealed interface Dl {
    /** Not on the phone. */
    data object Idle : Dl

    /** Downloading: [progress] 0..1 (0 = still connecting); [detail] e.g. "8 of 21 fonts". */
    data class Working(val progress: Float, val detail: String? = null) : Dl

    /** The last try failed: [message] says why, in a few words. */
    data class Failed(val message: String) : Dl

    /** On the phone and ready. */
    data object Ready : Dl
}

/** True when the phone's animations are turned off (Settings → Accessibility → Remove animations). */
@Composable
fun animationsOff(): Boolean {
    val context = LocalContext.current
    return remember {
        runCatching {
            android.provider.Settings.Global.getFloat(
                context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f,
            ) == 0f
        }.getOrDefault(false)
    }
}

private enum class Face { DOWNLOAD, RING, CHEER, USE, RETRY }

/**
 * The Store's download button. Download (pill) shrinks into a progress ring (an arrow bobs while
 * it connects; the percent shows once data comes); tapping the ring stops it. When it finishes a
 * check pops in with a little burst, then turns into Use, which pulses twice. A failed download
 * shows Retry. With the phone's animations off it changes without the extras.
 */
@Composable
fun DownloadButton(
    state: Dl,
    onDownload: () -> Unit,
    onStop: () -> Unit,
    onUse: (() -> Unit)?,
    modifier: Modifier = Modifier,
    label: String = "Download",
) {
    val still = animationsOff()
    // A finish seen here (not one from before this screen opened) gets the celebration.
    var sawWorking by remember { mutableStateOf(false) }
    var cheering by remember { mutableStateOf(false) }
    LaunchedEffect(state) {
        when (state) {
            is Dl.Working -> sawWorking = true
            Dl.Ready -> if (sawWorking) {
                sawWorking = false
                try {
                    if (!still) {
                        cheering = true
                        kotlinx.coroutines.delay(1_300)
                    }
                } finally {
                    cheering = false
                }
            }
            else -> sawWorking = false
        }
    }
    val face = when {
        state is Dl.Working -> Face.RING
        state is Dl.Failed -> Face.RETRY
        state == Dl.Ready && cheering -> Face.CHEER
        state == Dl.Ready -> Face.USE
        else -> Face.DOWNLOAD
    }
    AnimatedContent(
        targetState = face,
        transitionSpec = {
            if (still) fadeIn(tween(0)) togetherWith fadeOut(tween(0))
            else (fadeIn(tween(220)) + scaleIn(tween(260), initialScale = 0.6f)) togetherWith
                (fadeOut(tween(160)) + scaleOut(tween(200), targetScale = 0.6f))
        },
        contentAlignment = Alignment.CenterEnd,
        modifier = modifier.testTag("download_button"),
        label = "download",
    ) { f ->
        when (f) {
            Face.DOWNLOAD -> Pill(label, Icons.Rounded.Download, ToolColors.Style, onDownload)
            Face.RING -> ProgressRing((state as? Dl.Working)?.progress ?: 0f, still, onStop)
            Face.CHEER -> Cheer()
            Face.USE -> if (onUse != null) UsePill(still, onUse) else Installed()
            Face.RETRY -> Pill("Retry", Icons.Rounded.Refresh, MaterialTheme.colorScheme.error, onDownload)
        }
    }
}

@Composable
private fun Pill(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, color: Color, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(50), color = color, contentColor = Color.White) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text(text, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun UsePill(still: Boolean, onClick: () -> Unit) {
    // Two gentle pulses when it appears, to show where to tap next.
    val pulse = remember { Animatable(1f) }
    LaunchedEffect(Unit) {
        if (!still) repeat(2) {
            pulse.animateTo(1.08f, tween(220))
            pulse.animateTo(1f, tween(220))
        }
    }
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = ToolColors.Create,
        contentColor = Color.White,
        modifier = Modifier.scale(pulse.value).testTag("store_use"),
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.PlayArrow, null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text("Use", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun Installed() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.Check, null, tint = ToolColors.Look, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(4.dp))
        Text("Installed", style = MaterialTheme.typography.labelMedium, color = ToolColors.Look)
    }
}

@Composable
private fun ProgressRing(progress: Float, still: Boolean, onStop: () -> Unit) {
    val shown by animateFloatAsState(progress.coerceIn(0f, 1f), tween(if (still) 0 else 300), label = "ring")
    val track = MaterialTheme.colorScheme.outlineVariant
    val arc = ToolColors.Style
    Box(
        Modifier
            .size(44.dp)
            .clip(CircleShape)
            .clickable(onClick = onStop)
            .semantics { contentDescription = "Downloading ${(progress * 100).roundToInt()}%, tap to stop" },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(40.dp)) {
            val w = 3.dp.toPx()
            drawArc(track, 0f, 360f, false, style = Stroke(w))
            drawArc(arc, -90f, 360f * shown, false, style = Stroke(w, cap = StrokeCap.Round))
        }
        if (progress <= 0.01f) {
            // Still connecting: an arrow bobs so it never looks stuck.
            val bob = if (still) 0f else rememberInfiniteTransition(label = "bob").animateFloat(
                -2f, 3f, infiniteRepeatable(tween(500, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "bob",
            ).value
            Icon(Icons.Rounded.ArrowDownward, null, tint = arc, modifier = Modifier.size(18.dp).offset { IntOffset(0, bob.dp.roundToPx()) })
        } else {
            Text("${(progress * 100).roundToInt()}", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** The finish: a green check pops in and a ring of coloured dots flies out. */
@Composable
private fun Cheer() {
    val pop = remember { Animatable(0.3f) }
    val burst = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        launch { burst.animateTo(1f, tween(700, easing = LinearEasing)) }
        pop.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium))
    }
    val colours = listOf(ToolColors.Create, ToolColors.Style, ToolColors.Sound, ToolColors.Look, Color(0xFFFFC107))
    Box(Modifier.size(64.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val t = burst.value
            if (t > 0f && t < 1f) {
                val centre = Offset(size.width / 2, size.height / 2)
                for (i in 0 until 14) {
                    val a = 2 * PI * i / 14
                    val d = (16.dp.toPx() + 18.dp.toPx() * t)
                    drawCircle(
                        colours[i % colours.size].copy(alpha = 1f - t),
                        radius = 3.dp.toPx() * (1f - t * 0.6f),
                        center = centre + Offset((cos(a) * d).toFloat(), (sin(a) * d).toFloat()),
                    )
                }
            }
        }
        Box(
            Modifier.size(40.dp).scale(pop.value).background(ToolColors.Look, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.Check, "Downloaded", tint = Color.White, modifier = Modifier.size(24.dp))
        }
    }
}

/** A thin bar along the bottom of a card: fills while downloading, red when it failed. */
@Composable
fun DownloadBar(state: Dl, modifier: Modifier = Modifier) {
    val progress = when (state) {
        is Dl.Working -> state.progress
        is Dl.Failed -> 1f
        else -> 0f
    }
    val shown by animateFloatAsState(progress.coerceIn(0f, 1f), tween(300), label = "bar")
    if (state !is Dl.Working && state !is Dl.Failed) return
    Box(modifier.fillMaxWidth().height(3.dp)) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(if (state is Dl.Failed) 1f else shown.coerceAtLeast(0.02f))
                .background(if (state is Dl.Failed) MaterialTheme.colorScheme.error else ToolColors.Style),
        )
    }
}

/**
 * The line under a plug-in's name: its size while idle, "6.2 of 19.7 MB · about 1 min left"
 * while downloading, why it failed, or [ready] when done.
 */
@Composable
fun downloadLine(state: Dl, totalBytes: Long, idle: String, ready: String): String {
    // When this download started (kept while it runs), to work out the time left.
    val startedAt = remember(state is Dl.Working) { System.currentTimeMillis() }
    val now = System.currentTimeMillis()
    return when (state) {
        Dl.Idle -> idle
        Dl.Ready -> ready
        is Dl.Failed -> state.message
        is Dl.Working -> {
            val p = state.progress
            if (p <= 0.01f) return state.detail ?: "Connecting…"
            val parts = ArrayList<String>()
            if (state.detail != null) parts += state.detail
            else if (totalBytes > 0) {
                val total = totalBytes / 1_000_000.0
                parts += String.format(java.util.Locale.US, "%.1f of %.1f MB", total * p, total)
            }
            val elapsed = now - startedAt
            if (p in 0.05f..0.98f && elapsed > 2_000) {
                val left = (elapsed * (1 - p) / p / 1000).toLong()
                parts += when {
                    left < 10 -> "a few seconds left"
                    left < 60 -> "about ${(left / 5 + 1) * 5} s left"
                    else -> "about ${(left + 30) / 60} min left"
                }
            }
            parts.joinToString(" · ")
        }
    }
}

/** Shakes its content sideways once each time [state] becomes a failure. */
@Composable
fun Modifier.shakeOnFail(state: Dl): Modifier {
    val x = remember { Animatable(0f) }
    val still = animationsOff()
    LaunchedEffect(state is Dl.Failed) {
        if (state is Dl.Failed && !still) {
            x.animateTo(
                0f,
                keyframes {
                    durationMillis = 360
                    (-8f).at(60)
                    (8f).at(140)
                    (-6f).at(220)
                    (4f).at(290)
                    (0f).at(360)
                },
            )
        }
    }
    return this.offset { IntOffset(x.value.dp.roundToPx(), 0) }
}

/** A row of small dots, one per font in a pack, filling in as each one arrives. */
@Composable
fun FontTicks(done: Int, total: Int, modifier: Modifier = Modifier) {
    Row(modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        val shown = total.coerceAtMost(30)
        for (i in 0 until shown) {
            val on = i < done * shown / total.coerceAtLeast(1)
            val scale by animateFloatAsState(if (on) 1f else 0.7f, tween(250), label = "tick")
            Box(
                Modifier
                    .padding(end = 3.dp)
                    .size(7.dp)
                    .scale(scale)
                    .background(if (on) ToolColors.Style else MaterialTheme.colorScheme.outlineVariant, CircleShape),
            )
        }
    }
}

/** A small ring for one font in a list (percent while it downloads). */
@Composable
fun MiniRing(percent: Int) {
    val shown by animateFloatAsState(percent / 100f, tween(250), label = "mini")
    val arc = ToolColors.Style
    val track = MaterialTheme.colorScheme.outlineVariant
    Canvas(Modifier.size(20.dp)) {
        val w = 2.5.dp.toPx()
        drawArc(track, 0f, 360f, false, style = Stroke(w))
        drawArc(arc, -90f, 360f * shown.coerceAtLeast(0.04f), false, style = Stroke(w, cap = StrokeCap.Round))
    }
}
