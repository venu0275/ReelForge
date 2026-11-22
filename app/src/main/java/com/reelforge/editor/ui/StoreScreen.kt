package com.reelforge.editor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.rounded.Storefront
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.reelforge.editor.engine.CaptionLanguage

/** Something downloaded in the Store that the user wants to use now; the editor takes them there. */
sealed interface StoreUse {
    /** A font pack: a new text in that language opens on its Font tab. */
    data class Fonts(val group: String) : StoreUse
    /** A caption language: Captions opens with it chosen. */
    data class Captions(val language: CaptionLanguage) : StoreUse
    /** The Vocal Separator: the music, or a sound, opens at "Split voice and music". */
    data object VocalSeparator : StoreUse
}

/**
 * The Store on the whole screen, like KineMaster's asset store: big previews, more cards side by
 * side on wide screens, and the plug-in parts listed down the left in landscape. Adding something
 * to the video closes it; "Use" on a download takes the user to the tool it is for.
 */
@Composable
fun StoreScreen(vm: EditorViewModel, onClose: () -> Unit) {
    Surface(Modifier.fillMaxSize().testTag("store_screen"), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().systemBarsPadding().displayCutoutPadding()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onClose) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close store")
                }
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = ToolColors.Style.copy(alpha = 0.2f),
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(Icons.Rounded.Storefront, null, tint = ToolColors.Style, modifier = Modifier.padding(7.dp))
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("Store", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(
                        "Effects, fonts, caption languages, AI tools, stickers and sounds. Free.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val columns = when {
                    maxWidth >= 1000.dp -> 4
                    maxWidth >= 640.dp -> 3
                    else -> 2
                }
                CompositionLocalProvider(
                    LocalStoreColumns provides columns,
                    LocalStoreWide provides (maxWidth >= 640.dp),
                    LocalStoreAdded provides onClose,
                ) {
                    Column(
                        Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp)
                            .padding(bottom = 24.dp),
                    ) {
                        StorePanel(vm)
                        Spacer(Modifier.height(16.dp))
                    }
                }
            }
        }
    }
}
