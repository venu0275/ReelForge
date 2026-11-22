package com.reelforge.editor.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.reelforge.editor.data.FontAsset
import com.reelforge.editor.data.FontPack
import com.reelforge.editor.data.FontPacks
import com.reelforge.editor.engine.CaptionLanguage
import com.reelforge.editor.engine.EffectFactory
import com.reelforge.editor.model.NoiseRemoval
import com.reelforge.editor.engine.SceneEffect
import com.reelforge.editor.model.TextPreset
import com.reelforge.editor.model.TextPresets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The parts of the plug-in store, shown as chips across the top. */
private enum class PluginPart(val label: String) {
    ALL("All"), EFFECTS("Effects"), CAPTIONS("Caption languages"), AUDIO("Audio"), AI("AI photo"),
    FONTS("Fonts"), TEXT("Text presets"), MINE("My plug-ins"),
}

/**
 * Plug-ins: extras people add when they need them, like KineMaster's asset store. Fonts download
 * on demand (open-licence Google Fonts); effects and text presets are made by the app.
 */
@Composable
fun PluginsPanel(vm: EditorViewModel) {
    var part by rememberSaveable { mutableStateOf(PluginPart.ALL) }
    HintRow(spacing = 6.dp) {
        PluginPart.entries.forEach { p ->
            FilterChip(
                selected = part == p,
                onClick = { part = p },
                label = { Text(p.label) },
                modifier = Modifier.testTag("plugins_${p.name.lowercase()}"),
            )
        }
    }
    Spacer(Modifier.height(6.dp))
    when (part) {
        PluginPart.ALL -> {
            SectionTitle("Effects", "Built in · no download")
            EffectGrid(vm, SceneEffect.entries.filter { it.group == "Festival" })
            TextButton(onClick = { part = PluginPart.EFFECTS }) { Text("See all ${SceneEffect.entries.size} effects") }
            SectionTitle("Auto caption languages", "Free download")
            CaptionLanguage.entries.filter { it.group == "India" }.take(4).forEach { SpeechCard(vm, it) }
            TextButton(onClick = { part = PluginPart.CAPTIONS }) { Text("See all ${CaptionLanguage.entries.size} languages") }
            SectionTitle("Audio", "Built in")
            AudioTeaser { part = PluginPart.AUDIO }
            NoiseRemoverCard(vm)
            if (vm.superResolutionReady) {
                SectionTitle("AI photo", "Built in")
                SuperResolutionCard(vm)
            }
            SectionTitle("Fonts by language", "Free download")
            vm.fontPacks.filter { it.group in FontPacks.LANGUAGES }.take(3).forEach { FontPackCard(vm, it) }
            TextButton(onClick = { part = PluginPart.FONTS }) { Text("See all font packs") }
            SectionTitle("Text presets", "Tap to add")
            PresetRow(vm, TextPresets.ALL.filter { it.group == "Festivals" })
            TextButton(onClick = { part = PluginPart.TEXT }) { Text("See all text presets") }
        }
        PluginPart.EFFECTS -> {
            Text(
                "Effects cover the whole video. Tap Add to put one at the red line, then drag its ends " +
                    "on the timeline to choose how long it plays.",
                style = MaterialTheme.typography.bodySmall,
            )
            SceneEffect.entries.groupBy { it.group }.forEach { (group, effects) ->
                SectionTitle(group, null)
                EffectGrid(vm, effects)
            }
        }
        PluginPart.FONTS -> {
            Text(
                "Download a pack once and its fonts work without internet. Use them in Text → Font.",
                style = MaterialTheme.typography.bodySmall,
            )
            SectionTitle("Indian languages", null)
            vm.fontPacks.filter { it.group in FontPacks.LANGUAGES }.forEach { FontPackCard(vm, it) }
            SectionTitle("English styles", null)
            vm.fontPacks.filter { it.group !in FontPacks.LANGUAGES }.forEach { FontPackCard(vm, it) }
        }
        PluginPart.TEXT -> {
            Text(
                "Words with a ready-made look and movement. Tap one to add it at the red line; " +
                    "change the words in Text afterwards. ↓ means its font downloads first.",
                style = MaterialTheme.typography.bodySmall,
            )
            TextPresets.GROUPS.forEach { group ->
                SectionTitle(group, null)
                PresetRow(vm, TextPresets.ALL.filter { it.group == group })
            }
        }
        PluginPart.CAPTIONS -> {
            Text(
                "Download a language once and Auto captions works with it offline. Your sound never " +
                    "leaves the phone.",
                style = MaterialTheme.typography.bodySmall,
            )
            CaptionLanguage.entries.groupBy { it.group }.forEach { (group, languages) ->
                SectionTitle(if (group == "India") "Indian languages" else "Other languages", null)
                languages.forEach { SpeechCard(vm, it) }
            }
            TipBox(
                "Tamil, Marathi, Bengali, Kannada and Malayalam are not available yet: there is no " +
                    "free offline speech model for them that fits on a phone. We will add them when there is.",
            )
        }
        PluginPart.AI -> if (vm.superResolutionReady) SuperResolutionCard(vm)
        else TipBox("Super Resolution is not in this version of the app.")
        PluginPart.AUDIO -> {
            AiVoicePanel(vm)
            NoiseRemoverCard(vm)
        }
        PluginPart.MINE -> MyPlugins(vm) { part = PluginPart.FONTS }
    }
}

@Composable
private fun SectionTitle(title: String, note: String?) {
    Row(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        if (note != null) {
            Text(note, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ---------------------------------------------------------------- effects

/** Milliseconds since this first showed, updated every frame, for live previews. */
@Composable
private fun clockMs(): Long {
    val time by produceState(0L) {
        val start = withFrameMillis { it }
        while (true) withFrameMillis { value = it - start }
    }
    return time
}

@Composable
private fun EffectGrid(vm: EditorViewModel, effects: List<SceneEffect>) {
    val now = clockMs()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        effects.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { EffectCard(it, now, Modifier.weight(1f)) { vm.addSceneEffect(it) } }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun EffectCard(effect: SceneEffect, nowMs: Long, modifier: Modifier, onAdd: () -> Unit) {
    Surface(
        onClick = onAdd,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier.testTag("effect_${effect.name.lowercase()}"),
    ) {
        Column {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 10f)
                    .clip(RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp))
                    .background(Brush.verticalGradient(listOf(Color(0xFF1A237E), Color(0xFF0B0B14)))),
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    drawIntoCanvas { c ->
                        // The preview loops every 6 seconds, without the fade at the start and end.
                        effect.draw(c.nativeCanvas, 500 + nowMs % 6_000, Long.MAX_VALUE / 4, size.width, size.height)
                    }
                }
            }
            Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(effect.title, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        effect.blurb, style = MaterialTheme.typography.labelSmall, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(Icons.Rounded.AddCircle, "Add ${effect.title}", tint = ToolColors.Create, modifier = Modifier.size(26.dp))
            }
        }
    }
}

// ---------------------------------------------------------------- fonts

/** The font as a Compose font family once it is on the phone, else null. */
@Composable
private fun downloadedFamily(vm: EditorViewModel, font: FontAsset?): FontFamily? {
    if (font == null || font.id !in vm.installedFonts) return null
    val path = vm.fontPath(font)
    return remember(path) {
        runCatching { FontFamily(android.graphics.Typeface.createFromFile(path)) }.getOrNull()
    }
}

@Composable
private fun FontPackCard(vm: EditorViewModel, pack: FontPack) {
    var open by rememberSaveable(pack.group) { mutableStateOf(false) }
    val have = pack.fonts.count { it.id in vm.installedFonts }
    val progress = vm.packProgress[pack.group]
    val showcase = pack.fonts.firstOrNull { it.id in vm.installedFonts } ?: pack.fonts.first()
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).animateContentSize()
            .testTag("fontpack_${pack.group}"),
    ) {
        Column(Modifier.clickable { open = !open }.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(52.dp).clip(RoundedCornerShape(12.dp)).background(ToolColors.Style.copy(alpha = 0.18f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        pack.sample.take(2),
                        fontFamily = downloadedFamily(vm, showcase), fontSize = 22.sp, maxLines = 1,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("${pack.group} fonts", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Text(
                        listOf(
                            "${pack.fonts.size} fonts",
                            FontPacks.size(pack.bytes),
                            if (have in 1 until pack.fonts.size) "$have on phone" else "",
                        ).filter { it.isNotEmpty() }.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        pack.sample, fontFamily = downloadedFamily(vm, showcase), maxLines = 1,
                        overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Spacer(Modifier.width(8.dp))
                PackButton(have, pack.fonts.size, progress) { vm.downloadFontPack(pack) }
            }
            if (open) {
                Spacer(Modifier.height(8.dp))
                pack.fonts.forEach { font -> FontLine(vm, font) }
                if (have > 0) {
                    TextButton(onClick = { vm.deleteFontPack(pack) }) {
                        Icon(Icons.Rounded.DeleteOutline, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Delete these fonts from the phone")
                    }
                }
            } else {
                Text(
                    "Tap to see every font",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

/** Download / progress ring / installed, the way the store shows each plug-in's state. */
@Composable
private fun PackButton(have: Int, total: Int, progress: Pair<Int, Int>?, onClick: () -> Unit) {
    when {
        progress != null -> Box(
            Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(
                progress = { progress.first / progress.second.coerceAtLeast(1).toFloat() },
                modifier = Modifier.size(40.dp),
                strokeWidth = 3.dp,
            )
            Icon(Icons.Rounded.Stop, "Stop download", modifier = Modifier.size(18.dp))
        }
        have >= total -> Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.CheckCircle, null, tint = ToolColors.Create, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text("Installed", style = MaterialTheme.typography.labelMedium, color = ToolColors.Create)
        }
        else -> Surface(
            onClick = onClick,
            shape = RoundedCornerShape(50),
            color = ToolColors.Style,
            contentColor = Color.White,
        ) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Download, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(if (have > 0) "Get ${total - have}" else "Download", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun FontLine(vm: EditorViewModel, font: FontAsset) {
    val installed = font.id in vm.installedFonts
    val percent = vm.fontProgress[font.id]
    val sample = FontPacks.sample(font.group)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(enabled = !installed && percent == null) { vm.useStoreFont(font, null) }
            .padding(vertical = 6.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                if (installed) "$sample  ${font.name}" else font.name,
                fontFamily = downloadedFamily(vm, font), fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                FontPacks.size(font.bytes), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        when {
            percent != null -> Text("$percent%", style = MaterialTheme.typography.labelMedium)
            installed -> Icon(Icons.Rounded.CheckCircle, "On phone", tint = ToolColors.Create, modifier = Modifier.size(20.dp))
            else -> Icon(Icons.Rounded.Download, "Download ${font.name}", modifier = Modifier.size(20.dp))
        }
    }
}

// ---------------------------------------------------------------- text presets

@Composable
private fun PresetRow(vm: EditorViewModel, presets: List<TextPreset>) {
    HintRow(spacing = 8.dp) {
        presets.forEach { PresetCard(vm, it) }
    }
}

@Composable
private fun PresetCard(vm: EditorViewModel, preset: TextPreset) {
    val font = preset.fontId?.let { id -> vm.storeFonts.firstOrNull { it.id == id } }
    val ready = font == null || font.id in vm.installedFonts
    val path = if (font != null && ready) vm.fontPath(font) else null
    val busy = vm.presetBusy.containsKey(preset.id)
    // Drawn by the same code as the video, in its own font once that is downloaded.
    val picture by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, preset.id, path) {
        value = withContext(Dispatchers.Default) {
            runCatching { EffectFactory.renderText(preset.item(path).copy(size = 0.11f), 420).asImageBitmap() }.getOrNull()
        }
    }
    Surface(
        onClick = { vm.addTextPreset(preset) },
        enabled = !busy,
        shape = RoundedCornerShape(14.dp),
        color = Color(0xFF15151F),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.width(150.dp).height(92.dp).testTag("preset_${preset.id}"),
    ) {
        Box(Modifier.padding(8.dp)) {
            picture?.let {
                Image(it, preset.text, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(bottom = 14.dp))
            }
            Row(Modifier.align(Alignment.BottomStart).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (!ready) {
                    Icon(Icons.Rounded.Download, null, tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(12.dp))
                    Spacer(Modifier.width(2.dp))
                    Text(FontPacks.size(font?.bytes ?: 0), fontSize = 10.sp, color = Color.White.copy(alpha = 0.7f))
                } else {
                    Text("Tap to add", fontSize = 10.sp, color = Color.White.copy(alpha = 0.6f))
                }
            }
            if (busy) {
                CircularProgressIndicator(Modifier.size(28.dp).align(Alignment.Center), strokeWidth = 3.dp)
            }
        }
    }
}

// ---------------------------------------------------------------- captions

/** A few words in the language, so people recognise it. */
private fun speechSample(language: CaptionLanguage): String = when (language) {
    CaptionLanguage.EN_IN, CaptionLanguage.EN_US -> "Hello, how are you?"
    CaptionLanguage.HI -> "नमस्ते, आप कैसे हैं?"
    CaptionLanguage.HINGLISH -> "Namaste, aap kaise hain? (uses the Hindi pack)"
    CaptionLanguage.TE -> "నమస్కారం, ఎలా ఉన్నారు?"
    CaptionLanguage.GU -> "નમસ્તે, કેમ છો?"
    CaptionLanguage.ES -> "Hola, ¿cómo estás?"
    CaptionLanguage.FR -> "Bonjour, ça va ?"
    CaptionLanguage.DE -> "Hallo, wie geht's?"
    CaptionLanguage.PT -> "Olá, tudo bem?"
    CaptionLanguage.IT -> "Ciao, come stai?"
    CaptionLanguage.RU -> "Привет, как дела?"
    CaptionLanguage.TR -> "Merhaba, nasılsın?"
    CaptionLanguage.VI -> "Xin chào, bạn khỏe không?"
    CaptionLanguage.KO -> "안녕하세요"
    CaptionLanguage.JA -> "こんにちは"
    CaptionLanguage.ZH -> "你好"
}

@Composable
private fun SpeechCard(vm: EditorViewModel, language: CaptionLanguage) {
    val installed = language.model in vm.speechModels
    val percent = vm.speechProgress[language.model]
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).testTag("speech_${language.name.lowercase()}"),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(ToolColors.Create.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.ClosedCaption, null, tint = ToolColors.Create)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(language.label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text(
                    speechSample(language), style = MaterialTheme.typography.bodySmall, maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "Auto captions · ${language.sizeMb} MB",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(8.dp))
            when {
                percent != null -> Box(
                    Modifier.size(44.dp).clip(CircleShape).clickable { vm.downloadCaptionModel(language) },
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(progress = { percent / 100f }, modifier = Modifier.size(40.dp), strokeWidth = 3.dp)
                    Text("$percent", fontSize = 10.sp)
                }
                installed -> PackButton(1, 1, null) {}
                else -> PackButton(0, 1, null) { vm.downloadCaptionModel(language) }
            }
        }
    }
}

// ---------------------------------------------------------------- audio

/** A small card that opens AI Voice (it is big, so the All page only points to it). */
@Composable
private fun AudioTeaser(open: () -> Unit) {
    Surface(
        onClick = open,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(ToolColors.Sound.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.RecordVoiceOver, null, tint = ToolColors.Sound) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("AI Voice", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Text(
                    "Type words, hear them in Hindi, Telugu, Tamil, English and more.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Icon(Icons.Rounded.ChevronRight, null)
        }
    }
}

@Composable
private fun NoiseRemoverCard(vm: EditorViewModel) {
    val clips = vm.project?.clips.orEmpty().filter { it.type == com.reelforge.editor.model.ClipType.VIDEO && it.hasAudio }
    val voices = vm.project?.audio.orEmpty().filter { it.voice }
    val levels = (clips.map { it.denoise } + voices.map { it.denoise }).toSet()
    val current = levels.singleOrNull()
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).testTag("noise_remover_card"),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(ToolColors.Sound.copy(alpha = 0.18f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.GraphicEq, null, tint = ToolColors.Sound)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Noise remover", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Text(
                        "Takes out fan, AC, hiss, hum and traffic noise. Built in · works offline.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            if (clips.isEmpty() && voices.isEmpty()) {
                Text(
                    "Add a video with sound or record a voice-over, then come back here.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }
            Text(
                "For all ${clips.size} video clip${if (clips.size == 1) "" else "s"}" +
                    (if (voices.isNotEmpty()) " and ${voices.size} voice-over${if (voices.size == 1) "" else "s"}" else ""),
                style = MaterialTheme.typography.labelLarge,
            )
            HintRow(spacing = 8.dp) {
                NoiseRemoval.entries.forEach { level ->
                    FilterChip(
                        selected = current == level,
                        onClick = { vm.setNoiseRemovalEverywhere(level) },
                        label = { Text(level.label) },
                        modifier = Modifier.testTag("noise_all_${level.name.lowercase()}"),
                    )
                }
            }
            Text(
                if (current == null) "Clips use different levels. Pick one to use it on all of them."
                else "For one clip only: select it, then Sound → Noise remover.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ---------------------------------------------------------------- AI photo

@Composable
private fun SuperResolutionCard(vm: EditorViewModel) {
    val (clips, layers) = vm.photosToSharpen()
    val count = clips.size + layers.size
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).testTag("super_resolution_card"),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(ToolColors.Look.copy(alpha = 0.18f)),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Rounded.AutoFixHigh, null, tint = ToolColors.Look) }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Super Resolution", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Text(
                        "Makes small, old or blurry photos 4x bigger and sharper. Built in · works offline.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            if (count == 0) {
                Text(
                    "Add a photo to your video, then come back. For one photo: select it, then Make sharper.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                SharpenRow(vm, "✨ Make all $count photo${if (count == 1) "" else "s"} sharper") {
                    vm.sharpenPhotos(clips, layers)
                }
                Text(
                    "Photos that are already large are left as they are. Works on photos, not videos.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ---------------------------------------------------------------- mine

@Composable
private fun MyPlugins(vm: EditorViewModel, openFonts: () -> Unit) {
    val packs = vm.fontPacks.filter { p -> p.fonts.any { it.id in vm.installedFonts } }
    val speech = CaptionLanguage.entries.filter { it.model in vm.speechModels }.distinctBy { it.model }
    if (packs.isEmpty() && speech.isEmpty()) {
        TipBox("Nothing downloaded yet. Effects, text presets and the noise remover are built in, so they are always here.")
        TextButton(onClick = openFonts) { Text("Browse font packs") }
    }
    speech.forEach { language ->
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        ) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.ClosedCaption, null, tint = ToolColors.Create)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (language == CaptionLanguage.HI) "Hindi + Hinglish captions" else "${language.label} captions",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text("Speech model · about ${language.sizeMb} MB", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = { vm.deleteCaptionModel(language) }) { Text("Delete") }
            }
        }
    }
    packs.forEach { pack ->
        val mine = pack.fonts.filter { it.id in vm.installedFonts }
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        ) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.FontDownload, null, tint = ToolColors.Style)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("${pack.group} fonts", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "${mine.size} of ${pack.fonts.size} on phone · ${FontPacks.size(mine.sumOf { it.bytes })}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { vm.deleteFontPack(pack) }) { Text("Delete") }
            }
        }
    }
    SectionTitle("Built in", "Always available")
    Text(
        "${SceneEffect.entries.size} effects, ${TextPresets.ALL.size} text presets, AI Voice and the noise remover. " +
            "They take no space and work without internet.",
        style = MaterialTheme.typography.bodySmall,
    )
}
