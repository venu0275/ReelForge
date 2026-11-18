package com.reelforge.editor.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** The privacy policy as headings and paragraphs. Keep it in step with the published policy. */
private val PrivacyPolicy: List<Pair<String, List<String>>> = listOf(
    "" to listOf(
        "This privacy policy describes how Venugopal Thandra (\"we\", \"us\" or \"our\") handles information " +
            "in connection with your use of our mobile application, CutMitra (the \"Service\").",
        "By using the Service, you agree to the handling of information in accordance with this policy.",
    ),
    "Information we collect" to listOf(
        "We do not collect any personally identifiable information from you, such as your name, email or " +
            "phone number. The Service is used without logging in or creating an account.",
        "Editing, previewing and exporting videos all happen on your device. The Service has no server of " +
            "its own and does not send your videos, photos, recordings or projects to us.",
    ),
    "Your videos, photos and projects (stored only on your device)" to listOf(
        "• Your projects, voice recordings, drawings and downloaded items (fonts, stickers, music) are " +
            "stored only in the app's private storage on your device.",
        "• The videos and photos you add stay where they already are on your device; the app only reads " +
            "the ones you pick.",
        "• None of this is uploaded to us. We cannot see, access or recover it.",
        "• If your videos include other people, please make sure they are comfortable with you using and " +
            "sharing them.",
    ),
    "Device permissions and features" to listOf(
        "• Microphone: used only while you record a voice-over. The recording is saved inside the app on " +
            "your device and is never sent to us. You can refuse this permission and still use the rest of the app.",
        "• Photos and videos: if you allow it, the app lists your photos and videos in its own media " +
            "screen so you can tap one to add it. They are read on your device only and never sent to us. " +
            "If you don't allow it, you can still pick items with the Android photo picker or Files.",
        "• Screen recording: only when you press Start, after Android asks for your permission. The " +
            "recording, and microphone sound if you turn it on, is saved on your device only and never sent to us.",
        "• Music and audio: if you allow it, the app lists the songs and audio files on your phone in " +
            "its own music screen. They are read on your device only and never sent to us.",
        "• Files: music files and font files you import are chosen by you through the Android file picker.",
        "• Saving videos: exported videos are saved to the Movies/CutMitra folder on your device. Files " +
            "there can be seen by your gallery and by other apps that you have given media access.",
        "• Sharing: an exported video is sent to another app only when you tap Share. It is then handled " +
            "by that app under its own privacy policy.",
        "• Internet: used only for the store, music search, the one-time model downloads and the update check described below.",
    ),
    "Internet use and third-party services" to listOf(
        "The Service does not show advertisements and does not include any advertising, analytics or " +
            "tracking services. It connects to the internet only when you use these features:",
        "• Fonts and stickers: when you download a font or sticker from the in-app store, the file is " +
            "fetched from public open-source projects hosted on GitHub (Google Fonts and Noto Emoji). " +
            "GitHub receives a normal web request, which includes your IP address.",
        "• Music search: the words you type are sent to Openverse (openverse.org), a public catalogue of " +
            "openly licensed media. When you pick a result, the audio file is downloaded from the website " +
            "that hosts it. Those services receive a normal web request, which includes your IP address.",
        "• Auto captions: the first time you make captions in a language, its speech model is downloaded " +
            "once from alphacephei.com (the Vosk project). Listening to your video and voice happens on " +
            "your phone; your sound and the captions are never uploaded. The site receives a normal web " +
            "request, which includes your IP address.",
        "• Remove background: the first time you use it, a cut-out model is downloaded once from Google's " +
            "public model storage (storage.googleapis.com). Your pictures and videos are processed on your " +
            "phone and never uploaded. Google receives a normal web request, which includes your IP address.",
        "• Clean voice: noise removal uses your phone's own audio features and the app's code, on your phone.",
        "• Update check: when the app opens, it asks the Google Play Store app on your phone whether a " +
            "newer version is available. This is handled by Google Play under Google's privacy policy; the " +
            "app sends nothing of yours.",
        "• Bug reports: only if you choose Report a bug. The report opens in your own email app, and " +
            "nothing is sent until you press Send there. It contains what you type and, if you leave them " +
            "on, your phone model, Android version, the app's own recent log and any screenshots you add.",
        "• Text to speech: \"Read aloud\" uses the text-to-speech engine installed on your phone. The text " +
            "you type is handled by that engine under its own privacy policy.",
        "• Android backup: if backup is turned on in your phone settings, Android may back up the app's " +
            "projects and settings to your Google account, under Google's privacy policy.",
        "We do not run any of these services and we do not receive any information from them about you.",
    ),
    "Data security, retention and deletion" to listOf(
        "• You control your data: you can edit or delete projects, recordings and downloads at any time " +
            "inside the app.",
        "• Uninstalling the app deletes the projects, recordings and downloads stored inside it. Exported " +
            "videos in Movies/CutMitra stay on your device until you delete them.",
        "• Projects and exported videos are not encrypted. Keep your phone locked and share videos only " +
            "with people you trust.",
    ),
    "Your data rights (GDPR, CCPA/CPRA, CalOPPA)" to listOf(
        "Because all data stays on your device and we do not hold any of it, you can exercise your rights " +
            "directly on your device: your exported videos are ordinary files you can copy anywhere, you can " +
            "edit or delete any project, and you can stop using the Service at any time.",
        "We do not collect, sell or share personal information, the Service contains no advertising, and " +
            "it does not track you.",
    ),
    "Children's privacy" to listOf(
        "The Service is a video editing tool and is not directed to anyone under the age of 13. We do not " +
            "knowingly collect personally identifiable information from children under 13.",
    ),
    "Changes to this privacy policy" to listOf(
        "We may update this policy from time to time. We will post the new policy within the Service or on " +
            "our store listing. Please review it periodically.",
    ),
    "Contact us" to listOf(
        "If you have any questions about this policy, please write to the developer contact email shown on " +
            "CutMitra's Google Play page.",
    ),
)

/** The privacy policy, readable inside the app with no internet. */
@Composable
fun PrivacyScreen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    Column(
        Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Privacy policy", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
            TextButton(onClick = onBack) { Text("Back") }
        }
        Text("Last updated: 14 November 2026", style = MaterialTheme.typography.bodySmall)
        PrivacyPolicy.forEach { (heading, paragraphs) ->
            if (heading.isNotEmpty()) {
                Text(
                    heading,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            paragraphs.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
        }
    }
}
