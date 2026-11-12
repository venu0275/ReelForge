package com.reelforge.editor.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.reelforge.editor.data.AppSettings
import com.reelforge.editor.data.ScreenMode

/** All app-wide choices in one place. Every change is saved at once. */
@Composable
fun SettingsScreen(vm: EditorViewModel) {
    val s = vm.settings
    BackHandler { vm.showSettings = false }
    LaunchedEffect(Unit) { vm.refreshDownloadSize() }
    Column(
        Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Settings", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
            TextButton(onClick = { vm.showSettings = false }) { Text("Done") }
        }

        Section("Screen direction")
        Choice(ScreenMode.entries.map { it to it.label }, s.screen) { mode -> vm.updateSettings { it.copy(screen = mode) } }
        Note("Auto turns the app when you turn the phone, even if the phone's own rotation lock is on. " +
            "Portrait keeps it upright and Landscape keeps it sideways.")
        Toggle("Keep the screen on while the app is open", s.keepScreenOn) { on -> vm.updateSettings { it.copy(keepScreenOn = on) } }

        Section("Editing")
        Label("New photos stay for")
        Choice(AppSettings.LENGTHS.map { it to "${it / 1000} s" }, s.photoMs) { v -> vm.updateSettings { it.copy(photoMs = v) } }
        Label("New text, stickers and pictures stay for")
        Choice(AppSettings.LENGTHS.map { it to "${it / 1000} s" }, s.itemMs) { v -> vm.updateSettings { it.copy(itemMs = v) } }
        Toggle("Snap to the red line and to edges when dragging", s.snap) { on -> vm.updateSettings { it.copy(snap = on) } }

        Section("Export (what the Export window starts with)")
        Label("Resolution")
        Choice(
            AppSettings.SIDES.map { it to if (it == 2160) "4K" else "${it}p" }, s.exportSide,
        ) { v -> vm.updateSettings { it.copy(exportSide = v) } }
        Label("Frames per second")
        Choice(AppSettings.FPS.map { it to "$it" }, s.exportFps) { v -> vm.updateSettings { it.copy(exportFps = v) } }
        Label("Quality")
        Choice(listOf(0 to "Small file", 1 to "Balanced", 2 to "Best"), s.exportQuality) { v ->
            vm.updateSettings { it.copy(exportQuality = v) }
        }
        Toggle("Use HEVC (smaller files, if the phone supports it)", s.exportHevc) { on ->
            vm.updateSettings { it.copy(exportHevc = on) }
        }
        Note("Sizes this phone cannot produce are left out of the Export window automatically.")

        Section("Downloads")
        Note("Fonts, stickers and music from the Store use ${vm.downloadSizeLabel} on this phone.")
        TextButton(onClick = { vm.deleteUnusedDownloads() }) { Text("Delete downloads no project uses") }

        Section("Reset")
        TextButton(onClick = { vm.updateSettings { AppSettings() } }) { Text("Put all settings back to normal") }

        Section("About")
        Note(
            "CutMitra ${vm.appVersion}. Built only with free, open-source libraries (AndroidX Media3, Jetpack Compose). " +
                "Stickers: Noto Emoji (Apache 2.0). Fonts: Google Fonts (open licences). " +
                "Music search: public-domain audio from openverse.org. Buttons and sound effects are made by the app."
        )
    }
}

@Composable
private fun Section(title: String) {
    HorizontalDivider(Modifier.padding(top = 8.dp))
    Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun Label(text: String) = Text(text, style = MaterialTheme.typography.labelLarge)

@Composable
private fun Note(text: String) = Text(text, style = MaterialTheme.typography.bodySmall)

@Composable
private fun Toggle(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = value, onCheckedChange = onChange)
    }
}

@Composable
private fun <T> Choice(options: List<Pair<T, String>>, value: T, onChange: (T) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (option, label) ->
            FilterChip(selected = option == value, onClick = { onChange(option) }, label = { Text(label) })
        }
    }
}
