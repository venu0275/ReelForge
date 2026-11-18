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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import com.reelforge.editor.data.LibraryAlbum
import com.reelforge.editor.data.LibraryItem
import com.reelforge.editor.data.MediaAccess
import com.reelforge.editor.data.MediaLibrary
import com.reelforge.editor.util.MediaUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Small pictures for the media screen, kept while the app runs. */
private object LibraryThumbs {
    val cache = LruCache<String, ImageBitmap>(400)
}

private enum class LibraryTab(val label: String) { ALL("All"), VIDEO("Video"), IMAGE("Image"), COLOUR("Colour") }

private fun LibraryTab.keeps(item: LibraryItem) = when (this) {
    LibraryTab.ALL -> true
    LibraryTab.VIDEO -> item.isVideo
    LibraryTab.IMAGE -> !item.isVideo
    LibraryTab.COLOUR -> false
}

/**
 * The media screen, like a gallery inside the app: folders first, then the photos and videos in a
 * folder. Tapping one adds it to the end of the video at once; items already in the project are
 * outlined and show how many times they are used.
 */
@Composable
fun MediaBrowser(
    vm: EditorViewModel,
    usedCount: Map<String, Int>,
    modifier: Modifier,
    onClose: () -> Unit,
    onGallery: () -> Unit,
    onFiles: () -> Unit,
) {
    val context = LocalContext.current
    var tab by remember { mutableStateOf(LibraryTab.ALL) }
    // null = the folder list; "" = everything; otherwise a folder id.
    var albumId by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var asked by remember { mutableStateOf(false) }
    val requestAccess = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { reload++ }

    val access = remember(reload) { MediaLibrary.access(context) }
    val items by produceState(emptyList<LibraryItem>(), reload) {
        value = if (access == MediaAccess.NONE) emptyList()
        else withContext(Dispatchers.IO) { MediaLibrary.load(context) }
    }
    LaunchedEffect(access) {
        // Ask once when the screen first opens; after that only when a button is pressed.
        if (access == MediaAccess.NONE && !asked) {
            asked = true
            requestAccess.launch(MediaLibrary.permissions())
        }
    }
    BackHandler { if (albumId != null) albumId = null else onClose() }

    val shown = items.filter { tab.keeps(it) }
    val albums = remember(shown) { MediaLibrary.albums(shown) }
    val album: LibraryAlbum? = when (albumId) {
        null -> null
        "" -> LibraryAlbum("", "All", shown)
        else -> albums.firstOrNull { it.id == albumId }
    }

    Row(modifier.background(MaterialTheme.colorScheme.surface)) {
        // Left rail: All, Video, Image.
        Column(
            Modifier.width(64.dp).fillMaxHeight().padding(vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            LibraryTab.entries.forEach { t ->
                val on = t == tab
                Column(
                    Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (on) MaterialTheme.colorScheme.primary else Color.Transparent)
                        .clickable { tab = t }
                        .padding(6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    TabGlyph(t, if (on) Color.White else Color.Gray)
                    Text(t.label, fontSize = 11.sp, color = if (on) Color.White else Color.Gray)
                }
            }
        }
        Box(Modifier.width(1.dp).fillMaxHeight().background(Color.White.copy(alpha = 0.12f)))

        Column(Modifier.weight(1f).fillMaxHeight()) {
            Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (album != null) {
                    IconButton(onClick = { albumId = null }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to folders")
                    }
                } else {
                    Spacer(Modifier.width(12.dp))
                }
                Text(
                    if (tab == LibraryTab.COLOUR) "Colour backgrounds" else album?.name ?: "Media",
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                TextButton(onClick = onFiles) { Text("Files") }
                IconButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = "Close media") }
            }

            when {
                // Plain colours and blends need no permission: they are made on the phone.
                tab == LibraryTab.COLOUR -> ColourGrid(vm)
                access == MediaAccess.NONE -> NoAccess(
                    onAllow = { requestAccess.launch(MediaLibrary.permissions()) },
                    onGallery = onGallery,
                    onFiles = onFiles,
                )
                else -> {
                    if (access == MediaAccess.PARTIAL) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "You allowed only some photos and videos.",
                                Modifier.weight(1f),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            TextButton(onClick = { requestAccess.launch(MediaLibrary.permissions()) }) { Text("Choose more") }
                        }
                    }
                    if (album == null) {
                        FolderGrid(shown, albums) { albumId = it }
                    } else {
                        ItemGrid(album.items, usedCount) { item ->
                            vm.addMedia(listOf(Uri.parse(item.uri)))
                            vm.message = "Added: ${item.name}"
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NoAccess(onAllow: () -> Unit, onGallery: () -> Unit, onFiles: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "Allow CutMitra to show your photos and videos here, so you can tap one to add it.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Button(onClick = onAllow) { Text("Allow access") }
        Row {
            TextButton(onClick = onGallery) { Text("Use the gallery picker") }
            TextButton(onClick = onFiles) { Text("Choose from Files") }
        }
    }
}

@Composable
private fun FolderGrid(all: List<LibraryItem>, albums: List<LibraryAlbum>, onOpen: (String) -> Unit) {
    if (all.isEmpty()) {
        Text(
            "No photos or videos found.",
            Modifier.padding(24.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(132.dp),
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "all") { FolderTile("All", all.size, all.first().uri) { onOpen("") } }
        items(albums, key = { it.id }) { album ->
            FolderTile(album.name, album.items.size, album.items.first().uri) { onOpen(album.id) }
        }
    }
}

@Composable
private fun FolderTile(name: String, count: Int, coverUri: String, onClick: () -> Unit) {
    Column(Modifier.clickable(onClick = onClick)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1.3f)
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFF2A2A2E)),
        ) {
            Thumb(coverUri, Modifier.fillMaxSize())
        }
        Text(
            name,
            Modifier.fillMaxWidth().padding(top = 4.dp),
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text("$count", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ItemGrid(items: List<LibraryItem>, usedCount: Map<String, Int>, onAdd: (LibraryItem) -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(112.dp),
        contentPadding = PaddingValues(8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Text(
                "Tap to add to the end of your video. Outlined ones are already in it.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        items(items, key = { it.uri }) { item ->
            val used = usedCount[item.uri] ?: 0
            val shape = RoundedCornerShape(6.dp)
            Box(
                Modifier
                    .aspectRatio(1f)
                    .clip(shape)
                    .background(Color(0xFF2A2A2E))
                    .then(if (used > 0) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, shape) else Modifier)
                    .clickable { onAdd(item) },
            ) {
                Thumb(item.uri, Modifier.fillMaxSize())
                if (used > 0) {
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .padding(5.dp)
                            .size(22.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(if (used == 1) "✓" else "$used", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
                // The file name and, for videos, the length.
                Row(
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.55f))
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        item.name,
                        Modifier.weight(1f),
                        color = Color.White,
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (item.isVideo) {
                        Text(
                            MediaUtils.formatTime(item.durationMs).substringBefore('.'),
                            color = Color.White,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }
    }
}

/** A small picture of a photo or video, loaded in the background and remembered. */
@Composable
private fun Thumb(uri: String, modifier: Modifier) {
    val context = LocalContext.current
    val image by produceState<ImageBitmap?>(LibraryThumbs.cache.get(uri), uri) {
        if (value != null) return@produceState
        val loaded = withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.loadThumbnail(Uri.parse(uri), android.util.Size(256, 256), null).asImageBitmap()
            }.getOrNull()
        }
        if (loaded != null) {
            LibraryThumbs.cache.put(uri, loaded)
            value = loaded
        }
    }
    image?.let { Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = modifier) }
}

/** Plain drawn icons for the rail (the app ships without the large icon set). */
@Composable
private fun TabGlyph(tab: LibraryTab, color: Color) {
    Canvas(Modifier.size(24.dp)) {
        val s = size.minDimension
        when (tab) {
            LibraryTab.ALL -> {
                val g = s * 0.1f
                val c = (s - g) / 2f
                for (x in 0..1) for (y in 0..1) {
                    drawRoundRect(
                        color,
                        topLeft = Offset(x * (c + g), y * (c + g)),
                        size = Size(c, c),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(s * 0.06f),
                    )
                }
            }
            LibraryTab.VIDEO -> {
                drawRoundRect(color, size = Size(s, s * 0.8f), topLeft = Offset(0f, s * 0.1f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(s * 0.12f))
                val p = Path().apply {
                    moveTo(s * 0.4f, s * 0.32f); lineTo(s * 0.68f, s * 0.5f); lineTo(s * 0.4f, s * 0.68f); close()
                }
                drawPath(p, Color.Black)
            }
            LibraryTab.COLOUR -> {
                drawCircle(color, radius = s * 0.24f, center = Offset(s * 0.5f, s * 0.3f), style = Stroke(s * 0.08f))
                drawCircle(color, radius = s * 0.24f, center = Offset(s * 0.3f, s * 0.66f))
                drawCircle(color, radius = s * 0.24f, center = Offset(s * 0.7f, s * 0.66f), style = Stroke(s * 0.08f))
            }
            LibraryTab.IMAGE -> {
                drawRoundRect(color, size = Size(s, s * 0.8f), topLeft = Offset(0f, s * 0.1f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(s * 0.12f), style = Stroke(s * 0.09f))
                val p = Path().apply {
                    moveTo(s * 0.12f, s * 0.78f); lineTo(s * 0.4f, s * 0.42f); lineTo(s * 0.6f, s * 0.64f)
                    lineTo(s * 0.72f, s * 0.52f); lineTo(s * 0.88f, s * 0.78f); close()
                }
                drawPath(p, color)
                drawCircle(color, radius = s * 0.07f, center = Offset(s * 0.7f, s * 0.32f))
            }
        }
    }
}

private val SolidColours = listOf(
    0xFF000000, 0xFFFFFFFF, 0xFF9E9E9E, 0xFF37474F, 0xFFD32F2F, 0xFFFF5252, 0xFFFF9800, 0xFFFFC107,
    0xFFFFEB3B, 0xFF8BC34A, 0xFF2E7D32, 0xFF009688, 0xFF03A9F4, 0xFF1565C0, 0xFF1A237E, 0xFF7C4DFF,
    0xFF8E24AA, 0xFFFF80AB, 0xFF795548, 0xFFF5E6CA, 0xFF800000, 0xFF0D1B2A,
).map { it.toInt() }

private val Gradients = listOf(
    0xFFFF512F to 0xFFDD2476, 0xFF2193B0 to 0xFF6DD5ED, 0xFFF7971E to 0xFFFFD200, 0xFF11998E to 0xFF38EF7D,
    0xFF8E2DE2 to 0xFF4A00E0, 0xFF232526 to 0xFF414345, 0xFFEE9CA7 to 0xFFFFDDE1, 0xFF0F2027 to 0xFF2C5364,
    0xFFFC4A1A to 0xFFF7B733, 0xFF000428 to 0xFF004E92,
).map { (a, b) -> a.toInt() to b.toInt() }

/**
 * Plain colours and blends to add as a scene (for titles, intros, endings and gaps). They are
 * drawn on the phone in the project's shape, so nothing is downloaded.
 */
@Composable
private fun ColourGrid(vm: EditorViewModel) {
    var custom by remember { mutableStateOf(0xFF2196F3.toInt()) }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(64.dp),
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Text(
                "Tap a colour to add it as a scene at the end of your video. Then add text on it for a title.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item(span = { GridItemSpan(maxLineSpan) }) { Text("Plain colours", fontWeight = FontWeight.SemiBold) }
        items(SolidColours) { c ->
            Box(
                Modifier
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(c))
                    .border(1.dp, Color.White.copy(alpha = 0.25f), RoundedCornerShape(10.dp))
                    .clickable { vm.addColourClip(c) },
            )
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Text("Colour blends", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
        }
        items(Gradients) { (top, bottom) ->
            Box(
                Modifier
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color(top), Color(bottom))))
                    .clickable { vm.addColourClip(top, bottom) },
            )
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Any colour", fontWeight = FontWeight.SemiBold)
                ColorPicker(custom) { custom = it }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)).background(Color(custom)))
                    Spacer(Modifier.width(10.dp))
                    Button(onClick = { vm.addColourClip(custom) }) { Text("Add this colour") }
                }
            }
        }
    }
}
