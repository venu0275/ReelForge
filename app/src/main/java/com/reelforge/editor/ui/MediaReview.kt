package com.reelforge.editor.ui

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.reelforge.editor.model.ClipType
import com.reelforge.editor.util.MediaUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** What the review list shows for one picked file. */
private class PickedInfo(val name: String, val type: ClipType?, val durationMs: Long, val thumb: ImageBitmap?)

private fun loadPicked(context: Context, uri: Uri): PickedInfo {
    val name = MediaUtils.displayName(context, uri)
    val info = MediaUtils.probe(context, uri)
    val thumb: Bitmap? = runCatching {
        context.contentResolver.loadThumbnail(uri, android.util.Size(240, 240), null)
    }.getOrNull() ?: runCatching {
        if (info?.type == ClipType.IMAGE) MediaUtils.decodeImage(context, uri, 240)
        else MediaUtils.videoFrame(context, uri, 0, 240)
    }.getOrNull()
    return PickedInfo(name, info?.type, info?.durationMs ?: 0L, thumb?.asImageBitmap())
}

/**
 * A full screen that lists the photos and videos picked so far, in the order they will be added,
 * each with its picture, file name and length. Items can be added from the gallery or from Files
 * (which shows file names while choosing), moved up or down, or removed before they go in.
 */
@Composable
fun MediaReview(
    picked: SnapshotStateList<Uri>,
    onGallery: () -> Unit,
    onFiles: () -> Unit,
    onCancel: () -> Unit,
    onAdd: (List<Uri>) -> Unit,
) {
    BackHandler(onBack = onCancel)
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            // Swallow touches so nothing in the editor underneath reacts.
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
            .safeDrawingPadding()
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onCancel) { Text("Cancel") }
                Text(
                    if (picked.isEmpty()) "Add media" else "${picked.size} selected",
                    Modifier.weight(1f).padding(horizontal = 8.dp),
                    style = MaterialTheme.typography.titleLarge,
                )
                Button(onClick = { onAdd(picked.toList()) }, enabled = picked.isNotEmpty()) {
                    Text(if (picked.isEmpty()) "Add" else "Add ${picked.size}")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onGallery) { Text("+ Gallery") }
                OutlinedButton(onClick = onFiles) { Text("+ Files (shows names)") }
            }
            Text(
                if (picked.isEmpty()) {
                    "Choose from the gallery, or from Files to see each file's name while choosing. " +
                        "You can check everything here before it is added."
                } else {
                    "They are added in this order. Use the arrows to change it, or ✕ to leave one out."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                itemsIndexed(picked, key = { _, uri -> uri.toString() }) { index, uri ->
                    PickedRow(index, uri, picked)
                }
            }
        }
    }
}

@Composable
private fun PickedRow(index: Int, uri: Uri, picked: SnapshotStateList<Uri>) {
    val context = LocalContext.current
    val info by produceState<PickedInfo?>(null, uri) {
        value = withContext(Dispatchers.IO) { runCatching { loadPicked(context, uri) }.getOrNull() }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Its place in the order.
        Box(
            Modifier.size(26.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center,
        ) {
            Text("${index + 1}", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold)
        }
        Box(
            Modifier
                .padding(start = 8.dp)
                .size(64.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.Black),
        ) {
            info?.thumb?.let {
                Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
        }
        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
            Text(
                info?.name ?: "Loading…",
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyLarge,
            )
            val detail = when (info?.type) {
                ClipType.VIDEO -> "Video · " + MediaUtils.formatTime(info?.durationMs ?: 0L)
                ClipType.IMAGE -> "Photo"
                null -> if (info == null) "" else "This file can't be used"
            }
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TextButton(onClick = { if (index > 0) picked.add(index - 1, picked.removeAt(index)) }, enabled = index > 0) {
            Text("↑")
        }
        TextButton(
            onClick = { if (index < picked.lastIndex) picked.add(index + 1, picked.removeAt(index)) },
            enabled = index < picked.lastIndex,
        ) { Text("↓") }
        TextButton(onClick = { picked.removeAt(index) }) { Text("✕") }
    }
}

/**
 * A soft, blurred copy of [image] filling the area, for the backdrop around a clip set to Fit.
 * The picture is first shrunk to a few pixels, so stretching it back up blurs it on every Android
 * version; on Android 12 and later it is blurred a little more on top.
 */
@Composable
fun BlurredBackdrop(image: ImageBitmap?, modifier: Modifier = Modifier) {
    if (image == null) return
    val small = remember(image) {
        runCatching {
            val src = image.asAndroidBitmap()
            val w = 32
            val h = (w * src.height.toFloat() / src.width.coerceAtLeast(1)).roundToInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(src, w, h, true).asImageBitmap()
        }.getOrDefault(image)
    }
    Box(modifier.fillMaxSize()) {
        Image(
            bitmap = small,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().blur(16.dp),
        )
        // Darken it a little so the clip itself stands out.
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.3f)))
    }
}
