package com.reelforge.editor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
    var tab by remember { mutableIntStateOf(0) }
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("Stickers", "Subscribe and like", "Sound effects", "Find music").forEachIndexed { i, name ->
            FilterChip(selected = tab == i, onClick = { tab = i }, label = { Text(name) })
        }
    }
    Spacer(Modifier.height(4.dp))
    when (tab) {
        0 -> {
            Text(
                "Tap a sticker to add it at the red line. Faded ones download first (needs internet).",
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
            Text("Stickers: Noto Emoji by Google, Apache 2.0 licence.", style = MaterialTheme.typography.bodySmall)
        }
        1 -> {
            Text("Tap a button to add it at the red line. No download needed.", style = MaterialTheme.typography.bodySmall)
            FlowRow(
                Modifier.padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SocialSticker.entries.forEach { sticker ->
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
            Text(
                "After adding, select its bar on the timeline to move, resize or animate it.",
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
                SoundEffect.entries.forEach { effect ->
                    FilterChip(selected = false, onClick = { vm.addSoundEffect(effect) }, label = { Text(effect.title) })
                }
            }
        }
    }
}

/** Search for free, public-domain music and sounds online and drop one into the project. */
@Composable
private fun MusicFinder(vm: EditorViewModel) {
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
        Button(onClick = { vm.searchMusic(query, effects) }, enabled = query.isNotBlank() && !vm.musicSearching) {
            Text(if (vm.musicSearching) "…" else "Search")
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
                        result.creator, "free, no credit",
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
        "Only public-domain (CC0) results from openverse.org are shown: free to use, no payment and no credit " +
            "needed. Each one downloads when you pick it.",
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(top = 6.dp),
    )
}
