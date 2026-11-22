package com.reelforge.editor.ui

import android.content.Intent
import android.speech.tts.TextToSpeech
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.reelforge.editor.util.AiVoices
import kotlin.math.roundToInt

/**
 * AI Voice: type words and the phone speaks them in a natural voice (Hindi, Telugu, Tamil and
 * more), with speed and pitch. Uses the phone's own speech voices, so it is free and works offline;
 * missing languages download from the phone's speech settings.
 */
@Composable
fun AiVoicePanel(vm: EditorViewModel, startText: String = "") {
    val context = LocalContext.current
    LaunchedEffect(Unit) { vm.loadAiVoices() }
    DisposableEffect(Unit) { onDispose { vm.stopVoicePreview() } }
    var words by rememberSaveable { mutableStateOf(startText) }
    val voices = vm.aiVoices
    val chosen = voices.firstOrNull { it.name == vm.aiVoiceName }
    var language by rememberSaveable { mutableStateOf<String?>(null) }
    val shownLanguage = language ?: chosen?.language ?: voices.firstOrNull { it.installed }?.language ?: "en-IN"
    val hint = LocalHint.current

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).testTag("ai_voice"),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.RecordVoiceOver, null, tint = ToolColors.Sound)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("AI Voice", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Text(
                        "Type words and a natural voice reads them. Free, works offline.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            OutlinedTextField(
                value = words,
                onValueChange = { words = it.take(3_900) },
                label = { Text("What should the voice say?") },
                modifier = Modifier.fillMaxWidth().testTag("ai_voice_text"),
                minLines = 2,
                maxLines = 5,
            )

            when (vm.voiceEngineReady) {
                null -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Finding the phone's voices…", style = MaterialTheme.typography.bodySmall)
                }
                false -> {
                    Text(
                        "This phone has no speech engine. Install \"Speech Services by Google\" from the " +
                            "Play Store, then come back.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    TextButton(onClick = { vm.loadAiVoices(again = true) }) { Text("Try again") }
                }
                true -> {
                    Text("Language", style = MaterialTheme.typography.labelLarge)
                    val languages = AiVoices.LANGUAGES.filter { (tag, _) -> voices.any { it.language == tag } }
                    HintRow(spacing = 8.dp) {
                        languages.forEach { (tag, name) ->
                            val ready = voices.any { it.language == tag && it.installed }
                            FilterChip(
                                selected = shownLanguage == tag,
                                onClick = {
                                    language = tag
                                    vm.aiVoiceName = voices.firstOrNull { it.language == tag && it.installed }?.name
                                },
                                label = { Text(if (ready) name else "↓ $name") },
                            )
                        }
                    }
                    val here = voices.filter { it.language == shownLanguage }
                    val installedHere = here.filter { it.installed }
                    if (installedHere.isEmpty()) {
                        Text(
                            "This voice is not on the phone yet. Download it free from the phone's speech " +
                                "settings, then tap Refresh.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = {
                                runCatching {
                                    context.startActivity(
                                        Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)
                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    )
                                }.onFailure { hint("Open Settings → Text-to-speech → Install voice data") }
                            }) {
                                Icon(Icons.Rounded.Download, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Get voice")
                            }
                            TextButton(onClick = { vm.loadAiVoices(again = true) }) { Text("Refresh") }
                        }
                    } else {
                        Text("Voice", style = MaterialTheme.typography.labelLarge)
                        HintRow(spacing = 8.dp) {
                            installedHere.forEach { v ->
                                FilterChip(
                                    selected = vm.aiVoiceName == v.name,
                                    onClick = { vm.aiVoiceName = v.name },
                                    label = { Text(v.label) },
                                )
                            }
                        }
                    }
                    Text("Speed ${"%.2f".format(vm.aiRate).trimEnd('0').trimEnd('.')}x", style = MaterialTheme.typography.labelLarge)
                    Slider(value = vm.aiRate, onValueChange = { vm.aiRate = (it * 20).roundToInt() / 20f }, valueRange = 0.5f..2f)
                    Text(
                        "Pitch " + when {
                            vm.aiPitch < 0.95f -> "deeper"
                            vm.aiPitch > 1.05f -> "higher"
                            else -> "normal"
                        },
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Slider(value = vm.aiPitch, onValueChange = { vm.aiPitch = (it * 20).roundToInt() / 20f }, valueRange = 0.5f..2f)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(onClick = { vm.previewVoice(words) }, modifier = Modifier.testTag("ai_voice_listen")) {
                            Icon(if (vm.previewingVoice) Icons.Rounded.Stop else Icons.Rounded.PlayArrow, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(if (vm.previewingVoice) "Stop" else "Listen")
                        }
                        Button(
                            onClick = {
                                when {
                                    vm.isSpeaking -> hint("Still making the last voice, one moment")
                                    words.isBlank() -> hint("Type the words to be spoken first")
                                    vm.project == null -> hint("Open a video first")
                                    else -> vm.speakText(words)
                                }
                            },
                            modifier = Modifier.testTag("ai_voice_add"),
                        ) {
                            Text(if (vm.isSpeaking) "Making voice…" else "Add to video")
                        }
                    }
                    Text(
                        "Added at the red line as a voice-over: the video's sound goes quieter under it, " +
                            "and Auto captions can write it out.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
    Spacer(Modifier.height(4.dp))
}
