package com.reelforge.editor.ui

import android.os.Bundle
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
                if (vm.project == null) HomeScreen(vm) else EditorScreen(vm)
            }
        }
    }
}

private val Colors = darkColorScheme(
    primary = Color(0xFFFF4D5E),
    onPrimary = Color.White,
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
