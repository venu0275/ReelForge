package com.reelforge.editor.ui

import android.net.Uri
import android.util.LruCache
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.reelforge.editor.data.MusicLibrary
import com.reelforge.editor.data.SongGroup
import com.reelforge.editor.data.SongItem
import com.reelforge.editor.data.SoundEffect
import com.reelforge.editor.util.MediaUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Album pictures, kept while the app runs. Null entries remember "no picture". */
private object SongArt {
    val cache = LruCache<String, ImageBitmap>(300)
    val missing = HashSet<String>()
}

private enum class MusicTab(val label: String) { SONGS("Songs"), ALBUMS("Albums"), FOLDERS("Folders"), FREE("Free") }

/**
 * The music screen, like the media screen: songs, albums and folders on the phone, plus free
 * music and sound effects. Each song can be listened to first, then used as background music or
 * added as a sound at the red line.
 */
@Composable
fun MusicBrowser(
    vm: EditorViewModel,
    usedUris: Set<String>,
    modifier: Modifier,
    onClose: () -> Unit,
    onFiles: () -> Unit,
) {
    val context = LocalContext.current
    var tab by remember { mutableStateOf(MusicTab.SONGS) }
    var groupId by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var reload by remember { mutableIntStateOf(0) }
    var asked by remember { mutableStateOf(false) }
    var choosing by remember { mutableStateOf<SongItem?>(null) }
    val requestAccess = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { reload++ }
    val access = remember(reload) { MusicLibrary.hasAccess(context) }
    val songs by produceState(emptyList<SongItem>(), reload) {
        value = if (!access) emptyList() else withContext(Dispatchers.IO) { MusicLibrary.load(context) }
    }
    LaunchedEffect(access) {
        if (!access && !asked) {
            asked = true
            requestAccess.launch(MusicLibrary.permission())
        }
    }

    // One short preview at a time; it stops when the screen closes.
    val preview = remember { ExoPlayer.Builder(context).build() }
    var playing by remember { mutableStateOf<String?>(null) }
    DisposableEffect(Unit) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) playing = null
            }
        }
        preview.addListener(listener)
        onDispose {
            preview.removeListener(listener)
            preview.release()
        }
    }
    val listen: (SongItem) -> Unit = { song ->
        if (playing == song.uri) {
            preview.stop()
            playing = null
        } else {
            vm.pause()
            preview.setMediaItem(MediaItem.fromUri(song.uri))
            preview.prepare()
            // Start a little way in, where most songs have begun.
            preview.seekTo(minOf(30_000L, song.durationMs / 4))
            preview.play()
            playing = song.uri
        }
    }

    BackHandler { if (groupId != null) groupId = null else onClose() }

    val albums = remember(songs) { MusicLibrary.albums(songs) }
    val folders = remember(songs) { MusicLibrary.folders(songs) }
    val group: SongGroup? = when {
        groupId == null -> null
        tab == MusicTab.ALBUMS -> albums.firstOrNull { it.id == groupId }
        tab == MusicTab.FOLDERS -> folders.firstOrNull { it.id == groupId }
        else -> null
    }

    Row(modifier.background(MaterialTheme.colorScheme.surface)) {
        Column(
            Modifier.width(64.dp).fillMaxHeight().padding(vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            MusicTab.entries.forEach { t ->
                val on = t == tab
                Column(
                    Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (on) MaterialTheme.colorScheme.primary else Color.Transparent)
                        .clickable {
                            tab = t
                            groupId = null
                        }
                        .padding(6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    MusicGlyph(t, if (on) Color.White else Color.Gray)
                    Text(t.label, fontSize = 11.sp, color = if (on) Color.White else Color.Gray)
                }
            }
        }
        Box(Modifier.width(1.dp).fillMaxHeight().background(Color.White.copy(alpha = 0.12f)))

        Column(Modifier.weight(1f).fillMaxHeight()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (group != null) {
                    IconButton(onClick = { groupId = null }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                } else {
                    Spacer(Modifier.width(12.dp))
                }
                Text(
                    group?.name ?: "Music",
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                TextButton(onClick = onFiles) { Text("Files") }
                IconButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = "Close music") }
            }

            when {
                tab == MusicTab.FREE -> FreeAudio(vm)
                !access -> Column(
                    Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("Allow CutMitra to show the songs and audio files on your phone.")
                    Button(onClick = { requestAccess.launch(MusicLibrary.permission()) }) { Text("Allow access") }
                    TextButton(onClick = onFiles) { Text("Choose from Files") }
                    TextButton(onClick = { tab = MusicTab.FREE }) { Text("Free music and sounds") }
                }
                tab == MusicTab.SONGS || group != null -> {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it.take(40) },
                        placeholder = { Text("Search songs, artists, albums") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    )
                    val list = MusicLibrary.search(group?.songs ?: songs, query)
                    if (list.isEmpty()) {
                        Text(
                            if (songs.isEmpty()) "No songs found on this phone." else "Nothing matches “$query”.",
                            Modifier.padding(24.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    LazyColumn(
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        items(list, key = { it.uri }) { song ->
                            SongRow(
                                song,
                                used = song.uri in usedUris,
                                playing = playing == song.uri,
                                onListen = { listen(song) },
                                onAdd = {
                                    preview.stop()
                                    playing = null
                                    choosing = song
                                },
                            )
                        }
                    }
                }
                else -> {
                    val groups = if (tab == MusicTab.ALBUMS) albums else folders
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(120.dp),
                        contentPadding = PaddingValues(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(groups, key = { it.id }) { g ->
                            Column(Modifier.clickable { groupId = g.id; query = "" }) {
                                Box(
                                    Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(10.dp))
                                        .background(Color(0xFF2A2A2E)),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    SongPicture(g.songs.first().uri, Modifier.fillMaxSize())
                                }
                                Text(g.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${g.songs.size} songs", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }

    choosing?.let { song ->
        AlertDialog(
            onDismissRequest = { choosing = null },
            title = { Text(song.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            text = { Text("How should this be used?") },
            confirmButton = {
                TextButton(onClick = {
                    vm.setMusic(Uri.parse(song.uri))
                    vm.message = "Background music: ${song.title}"
                    choosing = null
                    onClose()
                }) { Text("Background music") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        vm.addAudioClip(Uri.parse(song.uri))
                        vm.message = "Added at the red line: ${song.title}"
                        choosing = null
                        onClose()
                    }) { Text("Add at red line") }
                    TextButton(onClick = { choosing = null }) { Text("Cancel") }
                }
            },
        )
    }
}

@Composable
private fun SongRow(song: SongItem, used: Boolean, playing: Boolean, onListen: () -> Unit, onAdd: () -> Unit) {
    val red = MaterialTheme.colorScheme.primary
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (playing) red.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surfaceVariant)
            .then(if (used) Modifier.border(2.dp, red, RoundedCornerShape(10.dp)) else Modifier)
            .clickable(onClick = onAdd)
            .padding(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF2A2A2E)),
            contentAlignment = Alignment.Center,
        ) {
            SongPicture(song.uri, Modifier.fillMaxSize())
            if (used) {
                Box(
                    Modifier.align(Alignment.TopEnd).size(16.dp).clip(CircleShape).background(red),
                    contentAlignment = Alignment.Center,
                ) { Text("✓", color = Color.White, fontSize = 10.sp) }
            }
        }
        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
            Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
            Text(
                listOf(song.artist, MediaUtils.formatTime(song.durationMs).substringBefore('.'))
                    .filter { it.isNotEmpty() }.joinToString(" · "),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        TextButton(onClick = onListen) { Text(if (playing) "■ Stop" else "▶ Listen") }
        Button(onClick = onAdd) { Text("+ Add") }
    }
}

/** The album picture of a song, or a music note when there is none. */
@Composable
private fun SongPicture(uri: String, modifier: Modifier) {
    val context = LocalContext.current
    val image by produceState<ImageBitmap?>(SongArt.cache.get(uri), uri) {
        if (value != null || uri in SongArt.missing) return@produceState
        val loaded = withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.loadThumbnail(Uri.parse(uri), android.util.Size(200, 200), null).asImageBitmap()
            }.getOrNull()
        }
        if (loaded != null) {
            SongArt.cache.put(uri, loaded)
            value = loaded
        } else {
            SongArt.missing += uri
        }
    }
    val art = image
    if (art != null) {
        Image(art, contentDescription = null, contentScale = ContentScale.Crop, modifier = modifier)
    } else {
        Box(modifier, contentAlignment = Alignment.Center) { Text("♪", color = Color.Gray, fontSize = 22.sp) }
    }
}

/** Free public-domain music search and the built-in sound effects. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FreeAudio(vm: EditorViewModel) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("Free music and sounds (public domain, no credit needed)", fontWeight = FontWeight.SemiBold)
        MusicFinder(vm)
        Spacer(Modifier.size(8.dp))
        Text("Sound effects (made by the app)", fontWeight = FontWeight.SemiBold)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SoundEffect.entries.forEach { effect ->
                FilterChip(selected = false, onClick = { vm.addSoundEffect(effect) }, label = { Text(effect.title) })
            }
        }
        Spacer(Modifier.size(12.dp))
    }
}

/** Plain drawn icons for the rail. */
@Composable
private fun MusicGlyph(tab: MusicTab, color: Color) {
    Canvas(Modifier.size(24.dp)) {
        val s = size.minDimension
        when (tab) {
            MusicTab.SONGS -> {
                drawCircle(color, radius = s * 0.16f, center = Offset(s * 0.32f, s * 0.76f))
                drawCircle(color, radius = s * 0.16f, center = Offset(s * 0.78f, s * 0.66f))
                drawLine(color, Offset(s * 0.46f, s * 0.76f), Offset(s * 0.46f, s * 0.18f), s * 0.08f)
                drawLine(color, Offset(s * 0.92f, s * 0.66f), Offset(s * 0.92f, s * 0.08f), s * 0.08f)
                drawLine(color, Offset(s * 0.46f, s * 0.18f), Offset(s * 0.92f, s * 0.08f), s * 0.1f)
            }
            MusicTab.ALBUMS -> {
                drawCircle(color, radius = s * 0.44f, center = Offset(s * 0.5f, s * 0.5f), style = Stroke(s * 0.08f))
                drawCircle(color, radius = s * 0.12f, center = Offset(s * 0.5f, s * 0.5f))
            }
            MusicTab.FOLDERS -> {
                val p = Path().apply {
                    moveTo(s * 0.05f, s * 0.22f); lineTo(s * 0.4f, s * 0.22f); lineTo(s * 0.5f, s * 0.34f)
                    lineTo(s * 0.95f, s * 0.34f); lineTo(s * 0.95f, s * 0.84f); lineTo(s * 0.05f, s * 0.84f); close()
                }
                drawPath(p, color, style = Stroke(s * 0.08f))
            }
            MusicTab.FREE -> {
                drawRoundRect(
                    color, topLeft = Offset(s * 0.1f, s * 0.3f), size = Size(s * 0.8f, s * 0.6f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(s * 0.08f), style = Stroke(s * 0.08f),
                )
                drawLine(color, Offset(s * 0.5f, s * 0.3f), Offset(s * 0.5f, s * 0.9f), s * 0.08f)
                drawCircle(color, radius = s * 0.12f, center = Offset(s * 0.38f, s * 0.2f), style = Stroke(s * 0.07f))
                drawCircle(color, radius = s * 0.12f, center = Offset(s * 0.62f, s * 0.2f), style = Stroke(s * 0.07f))
            }
        }
    }
}
