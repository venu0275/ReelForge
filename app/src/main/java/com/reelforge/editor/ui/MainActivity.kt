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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {
    override fun onStart() {
        super.onStart()
        com.reelforge.editor.work.AppVisibility.visible = true
    }

    override fun onStop() {
        com.reelforge.editor.work.AppVisibility.visible = false
        super.onStop()
    }

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
                // Anything switched off can explain itself with a hint toast, on every screen.
                androidx.compose.runtime.CompositionLocalProvider(
                    LocalHint provides { text -> vm.toast(text, ToastKind.WARNING) },
                ) {
                    when {
                        vm.showSettings -> SettingsScreen(vm)
                        vm.project == null -> HomeScreen(vm)
                        else -> EditorScreen(vm)
                    }
                }
                // Toasts show on every screen (home, editor, settings).
                ToastHost(vm)
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
    surfaceVariant = Color(0xFF262A33),
    onSurfaceVariant = Color(0xFFB4B9C4),
    outline = Color(0xFF4A4F5A),
)

@Composable
fun ReelForgeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = Colors,
        // Softer, rounder corners everywhere (chips, cards, sheets).
        shapes = androidx.compose.material3.Shapes(
            extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
            small = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
            medium = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
            large = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
            extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
        ),
    ) {
        Surface(color = MaterialTheme.colorScheme.background, content = content)
    }
}
