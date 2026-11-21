package com.reelforge.editor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.rounded.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.text.style.TextOverflow
import com.reelforge.editor.util.MediaUtils
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reelforge.editor.data.SocialSticker
import com.reelforge.editor.data.SoundEffect

/**
 * The store: things to drop onto the video. Stickers download the first time they are used;
 * channel buttons and sound effects are made by the app, so they work with no internet.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StorePanel(vm: EditorViewModel) {
    // -1 = Animated (shown first), 0 stickers, 1 buttons, 2 sounds, 3 music.
    var tab by remember { mutableIntStateOf(-1) }
    // All four parts are always in view (no hidden tabs), each with an icon.
    val I = androidx.compose.material.icons.Icons.Rounded
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf(
            Triple("Animated", I.AutoAwesome, ToolColors.Cut),
            Triple("Stickers", I.EmojiEmotions, ToolColors.Style),
            Triple("Buttons", I.ThumbUp, ToolColors.Create),
            Triple("Sounds", I.GraphicEq, ToolColors.Sound),
            Triple("Music", I.MusicNote, ToolColors.Sound),
        ).forEachIndexed { index, (name, icon, color) ->
            val i = index - 1
            val on = tab == i
            androidx.compose.material3.Surface(
                onClick = { tab = i },
                shape = RoundedCornerShape(14.dp),
                color = if (on) color.copy(alpha = 0.22f) else MaterialTheme.colorScheme.surfaceVariant,
                border = if (on) androidx.compose.foundation.BorderStroke(1.5.dp, color) else null,
                modifier = Modifier.weight(1f),
            ) {
                androidx.compose.foundation.layout.Column(
                    Modifier.padding(vertical = 7.dp, horizontal = 2.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    androidx.compose.material3.Icon(icon, null, tint = color, modifier = Modifier.size(22.dp))
                    Text(name, style = MaterialTheme.typography.labelSmall, fontSize = 10.sp, maxLines = 1, softWrap = false)
                }
            }
        }
    }
    Spacer(Modifier.height(8.dp))
    when (tab) {
        -1 -> {
            Text(
                "Buttons that move on your video. Tap one to add it at the red line; drag or pinch it " +
                    "on the video to place it.",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(6.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                com.reelforge.editor.engine.AnimatedButton.entries.forEach { kind ->
                    AnimatedButtonCard(kind) { vm.addAnimatedButton(kind) }
                }
            }
        }
        0 -> {
            val missing = vm.storeStickers.size - vm.installedStickers.size
            val pack = vm.stickerPackProgress
            if (pack != null) {
                ActionCard(
                    "Downloading ${pack.first} of ${pack.second}…", "Tap to stop. Stickers already downloaded stay.",
                    I.Downloading, ToolColors.Style, main = true,
                ) { vm.downloadAllStickers() }
                androidx.compose.material3.LinearProgressIndicator(
                    progress = { pack.first / pack.second.coerceAtLeast(1).toFloat() },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                )
            } else if (missing > 0) {
                ActionCard(
                    "Download all stickers", "$missing more stickers, so they all work without internet.",
                    I.Download, ToolColors.Style,
                ) { vm.downloadAllStickers() }
            }
            Text(
                "Tap a sticker to add it at the red line. Faded ones download when you tap them.",
                style = MaterialTheme.typography.bodySmall,
            )
            vm.storeStickers.groupBy { it.group }.forEach { (group, stickers) ->
                Text(group, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 6.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    stickers.forEach { sticker ->
                        val installed = sticker.code in vm.installedStickers
                        val busy = vm.stickerBusy.containsKey(sticker.code)
                        Box(
                            Modifier
                                .size(46.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .clickable(enabled = !busy) { vm.addSticker(sticker) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                if (busy) "…" else sticker.label,
                                fontSize = 24.sp,
                                color = Color.White.copy(alpha = if (installed) 1f else 0.45f),
                            )
                        }
                    }
                }
            }
            if (vm.installedStickers.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${vm.installedStickers.size} downloaded",
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    TextButton(onClick = { vm.removeUnusedStickers() }) { Text("Delete unused") }
                }
            }
        }
        1 -> {
            Text("Tap a button to add it at the red line. No download needed.", style = MaterialTheme.typography.bodySmall)
            FlowRow(
                Modifier.padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SocialSticker.entries.filter { !it.burst }.forEach { sticker ->
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(sticker.background))
                            .clickable { vm.addSocialSticker(sticker) }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    ) {
                        Text(sticker.label, color = Color(sticker.foreground), fontWeight = FontWeight.Bold)
                    }
                }
            }
            Text("Comedy reactions", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
            FlowRow(
                Modifier.padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SocialSticker.entries.filter { it.burst }.forEach { sticker ->
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFF37474F))
                            .clickable { vm.addSocialSticker(sticker) }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    ) {
                        Text(sticker.label, color = Color(sticker.foreground), fontWeight = FontWeight.Black)
                    }
                }
            }
            Text(
                "Some of these move on their own. Select the bar on the timeline, then Layers, to change " +
                    "\"Keep moving\", size or place.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        3 -> MusicFinder(vm)
        else -> {
            Text("Tap a sound to add it at the red line. No download needed.", style = MaterialTheme.typography.bodySmall)
            FlowRow(
                Modifier.padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SoundEffect.entries.filter { !it.comedy }.forEach { effect ->
                    FilterChip(selected = false, onClick = { vm.addSoundEffect(effect) }, label = { Text(effect.title) })
                }
            }
            Text("Comedy sounds", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SoundEffect.entries.filter { it.comedy }.forEach { effect ->
                    FilterChip(selected = false, onClick = { vm.addSoundEffect(effect) }, label = { Text(effect.title) })
                }
            }
            Text(
                "For laughs, applause and crowds, use Find music and tap a quick search.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/** Search for free, public-domain music and sounds online and drop one into the project. */
@Composable
fun MusicFinder(vm: EditorViewModel) {
    var query by remember { mutableStateOf("") }
    var effects by remember { mutableStateOf(false) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = !effects, onClick = { effects = false }, label = { Text("Music") })
        FilterChip(selected = effects, onClick = { effects = true }, label = { Text("Sounds") })
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it.take(60) },
            label = { Text("Search, e.g. happy, piano, rain") },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        val hint = LocalHint.current
        Button(onClick = {
            when {
                vm.musicSearching -> hint("Still searching, one moment")
                query.isBlank() -> hint("Type what to search for first, e.g. happy or rain")
                else -> vm.searchMusic(query, effects)
            }
        }) {
            Text(if (vm.musicSearching) "…" else "Search")
        }
    }
    HintRow(spacing = 8.dp) {
        listOf("laugh", "applause", "crowd cheer", "crickets", "scream", "boo", "funny", "cartoon").forEach { word ->
            FilterChip(
                selected = false,
                onClick = {
                    query = word
                    effects = true
                    vm.searchMusic(word, true)
                },
                label = { Text(word) },
            )
        }
    }
    if (vm.musicSearchNote.isNotEmpty()) Text(vm.musicSearchNote, style = MaterialTheme.typography.bodySmall)
    vm.musicResults.forEach { result ->
        val progress = vm.musicProgress[result.id]
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(result.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOf(
                        if (result.durationMs > 0) MediaUtils.formatTime(result.durationMs) else "",
                        result.creator,
                    ).filter { it.isNotEmpty() }.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            if (progress != null) Text("$progress%", Modifier.padding(horizontal = 12.dp))
            else {
                TextButton(onClick = { vm.useMusicResult(result, asMusic = true) }) { Text("As music") }
                TextButton(onClick = { vm.useMusicResult(result, asMusic = false) }) { Text("As sound") }
            }
        }
    }
    Text(
        "Free to use in your videos. Each one downloads when you pick it.",
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(top = 6.dp),
    )
}


/** A Store card playing an animated button on a loop, with its name under it. */
@Composable
private fun AnimatedButtonCard(kind: com.reelforge.editor.engine.AnimatedButton, onClick: () -> Unit) {
    // Plays the animation, rests a moment on the last frame, and starts again.
    val cycle = (kind.defaultMs + 700).toInt()
    val time by androidx.compose.animation.core.rememberInfiniteTransition(label = kind.name).animateFloat(
        0f, cycle.toFloat(),
        androidx.compose.animation.core.infiniteRepeatable(
            androidx.compose.animation.core.tween(cycle, easing = androidx.compose.animation.core.LinearEasing),
        ),
        label = kind.name,
    )
    androidx.compose.material3.Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        androidx.compose.foundation.layout.Column(
            Modifier.padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            androidx.compose.foundation.Canvas(
                Modifier
                    .height(52.dp)
                    .aspectRatio(com.reelforge.editor.engine.AnimatedButtons.aspect(kind))
                    .background(Color(0xFF1B1D22), RoundedCornerShape(8.dp)),
            ) {
                drawIntoCanvas { c ->
                    com.reelforge.editor.engine.AnimatedButtons.draw(
                        c.nativeCanvas, kind, time.toLong().coerceAtMost(kind.defaultMs), kind.defaultMs,
                        size.width, size.height,
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(kind.title, style = MaterialTheme.typography.labelMedium)
        }
    }
}
