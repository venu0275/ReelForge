package com.reelforge.editor.ui

import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.WindowManager
import androidx.compose.runtime.LaunchedEffect
import com.reelforge.editor.data.ScreenMode
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ReelForgeTheme {
                val vm: EditorViewModel = viewModel()
                val settings = vm.settings
                LaunchedEffect(settings.screen) {
                    requestedOrientation = when (settings.screen) {
                        ScreenMode.AUTO -> ActivityInfo.SCREEN_ORIENTATION_SENSOR
                        ScreenMode.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                        ScreenMode.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                    }
                }
                LaunchedEffect(settings.keepScreenOn) {
                    if (settings.keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
                when {
                    vm.showSettings -> SettingsScreen(vm)
                    vm.project == null -> HomeScreen(vm)
                    else -> EditorScreen(vm)
                }
                // "Update available" box, checked every time the app is opened.
                UpdatePrompt()
            }
        }
    }
}

private val Colors = darkColorScheme(
    primary = Color(0xFFFF4D5E),
    onPrimary = Color.White,
    // Selected chips and tabs use this, so a chosen option is clearly red, not a faint grey.
    secondaryContainer = Color(0xFFFF4D5E),
    onSecondaryContainer = Color.White,
    secondary = Color(0xFF4DD0E1),
    background = Color(0xFF101114),
    surface = Color(0xFF181A1F),
    surfaceVariant = Color(0xFF24272E),
)

@Composable
fun ReelForgeTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Colors) {
        Surface(color = MaterialTheme.colorScheme.background, content = content)
    }
}
