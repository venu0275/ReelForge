@file:OptIn(ExperimentalMaterial3Api::class)

package com.reelforge.editor.ui

import androidx.compose.foundation.clickable
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

    Scaffold(
        topBar = { TopAppBar(title = { Text("ReelForge") }) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showNew = true },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("New project") },
            )
        },
    ) { padding ->
        if (vm.projects.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(
                    "No projects yet.\nTap “New project” to start editing.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            // One column on a portrait phone, two or more in landscape and on tablets.
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 300.dp),
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(vm.projects, key = { it.id }) { p ->
                    Card(Modifier.fillMaxWidth().clickable { vm.openProject(p) }) {
                        Row(
                            Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(p.name, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "${p.aspect.label} · ${p.clips.size} clips · " +
                                        MediaUtils.formatTime(p.durationMs),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(onClick = { renaming = p.id; newName = p.name }) {
                                Icon(Icons.Default.Edit, contentDescription = "Rename project")
                            }
                            IconButton(onClick = { vm.deleteProject(p.id) }) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete project")
                            }
                        }
                    }
                }
            }
        }
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
