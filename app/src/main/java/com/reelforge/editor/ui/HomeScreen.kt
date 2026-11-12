@file:OptIn(ExperimentalMaterial3Api::class)

package com.reelforge.editor.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.reelforge.editor.model.AspectRatio
import com.reelforge.editor.util.MediaUtils

@Composable
fun HomeScreen(vm: EditorViewModel) {
    var showNew by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<String?>(null) }
    var newName by remember { mutableStateOf("") }
    var deleting by remember { mutableStateOf<String?>(null) }
    // The project whose three-dot menu is open.
    var menuFor by remember { mutableStateOf<String?>(null) }
    // 0 = newest first, 1 = by name, 2 = longest first.
    var sort by remember { mutableIntStateOf(0) }
    val sortLabels = listOf("Date modified", "Name", "Length")
    val shown = when (sort) {
        1 -> vm.projects.sortedBy { it.name.lowercase() }
        2 -> vm.projects.sortedByDescending { it.durationMs }
        else -> vm.projects.sortedByDescending { it.updatedAt }
    }
    val dateFormat = remember { SimpleDateFormat("d MMM yyyy, h:mm a", Locale.getDefault()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("CutMitra") },
                actions = { TextButton(onClick = { vm.showSettings = true }) { Text("Settings") } },
            )
        },
    ) { padding ->
        // One column on a portrait phone, two or more in landscape and on tablets.
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 300.dp),
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // The big "start here" tile.
            item(key = "new", span = { GridItemSpan(maxLineSpan) }) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .height(96.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.primary)
                        .clickable { showNew = true },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary)
                    Text(
                        "New project",
                        color = MaterialTheme.colorScheme.onPrimary,
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }

            item(key = "sort", span = { GridItemSpan(maxLineSpan) }) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { sort = (sort + 1) % sortLabels.size }) {
                        Text("Sort: ${sortLabels[sort]}")
                    }
                    Spacer(Modifier.weight(1f))
                    Text(
                        if (vm.projects.size == 1) "1 project" else "${vm.projects.size} projects",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (vm.projects.isEmpty()) {
                item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        "No projects yet.\nTap “New project” to start editing.",
                        modifier = Modifier.padding(vertical = 32.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            items(shown, key = { it.id }) { p ->
                val first = p.clips.firstOrNull()
                if (first != null) LaunchedEffect(first.uri) { vm.requestThumb(first) }
                val thumb = first?.let { vm.thumbs[vm.thumbKey(it)] }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { vm.openProject(p) }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // A picture of the first clip.
                    Box(
                        Modifier
                            .size(72.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (thumb != null) {
                            Image(
                                bitmap = thumb,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(
                            p.name,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            dateFormat.format(Date(p.updatedAt)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            "${p.aspect.label}  |  ${MediaUtils.formatTime(p.durationMs)}  |  " +
                                if (p.clips.size == 1) "1 clip" else "${p.clips.size} clips",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { menuFor = p.id }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "Project options")
                    }
                }
            }
        }
    }

    // The three-dot menu slides up from the bottom, one action per row.
    val menuTarget = vm.projects.firstOrNull { it.id == menuFor }
    if (menuTarget != null) {
        ModalBottomSheet(onDismissRequest = { menuFor = null }) {
            Text(
                menuTarget.name,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            SheetRow(Icons.Default.PlayArrow, "Open") {
                menuFor = null
                vm.openProject(menuTarget)
            }
            SheetRow(Icons.Default.Edit, "Rename") {
                menuFor = null
                renaming = menuTarget.id
                newName = menuTarget.name
            }
            SheetRow(Icons.Default.Add, "Duplicate") {
                menuFor = null
                vm.duplicateProject(menuTarget)
            }
            SheetRow(Icons.Default.Delete, "Delete") {
                menuFor = null
                deleting = menuTarget.id
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    val deleteTarget = vm.projects.firstOrNull { it.id == deleting }
    if (deleteTarget != null) {
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete project?") },
            text = { Text("“${deleteTarget.name}” will be removed. Your videos and photos stay on the phone.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteProject(deleteTarget.id)
                    deleting = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }

    val renameTarget = vm.projects.firstOrNull { it.id == renaming }
    if (renameTarget != null) {
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Rename project") },
            text = {
                OutlinedTextField(value = newName, onValueChange = { newName = it }, singleLine = true)
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.renameProject(renameTarget, newName)
                    renaming = null
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } },
        )
    }

    if (showNew) {
        var name by remember { mutableStateOf("") }
        var aspect by remember { mutableStateOf(AspectRatio.LANDSCAPE) }
        AlertDialog(
            onDismissRequest = { showNew = false },
            title = { Text("New project") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Name") },
                        singleLine = true,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text("Aspect ratio", style = MaterialTheme.typography.labelLarge)
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        AspectRatio.entries.forEach { a ->
                            FilterChip(
                                selected = aspect == a,
                                onClick = { aspect = a },
                                label = { Text(a.label) },
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showNew = false
                    vm.createProject(name, aspect)
                }) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = { showNew = false }) { Text("Cancel") } },
        )
    }
}

/** One action in the project menu: an icon and a label across the full width. */
@Composable
private fun SheetRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null)
        Text(label, Modifier.padding(start = 20.dp), style = MaterialTheme.typography.bodyLarge)
    }
}
