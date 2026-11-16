package com.reelforge.editor.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * A soft, blurred copy of [image] filling the area, for the backdrop around a clip set to Fit.
 * The picture is first shrunk to a few pixels, so stretching it back up blurs it on every Android
 * version; on Android 12 and later it is blurred a little more on top.
 */
@Composable
fun BlurredBackdrop(image: ImageBitmap?, modifier: Modifier = Modifier) {
    if (image == null) return
    val small = remember(image) {
        runCatching {
            val src = image.asAndroidBitmap()
            val w = 32
            val h = (w * src.height.toFloat() / src.width.coerceAtLeast(1)).roundToInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(src, w, h, true).asImageBitmap()
        }.getOrDefault(image)
    }
    Box(modifier.fillMaxSize()) {
        Image(
            bitmap = small,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().blur(16.dp),
        )
        // Darken it a little so the clip itself stands out.
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.3f)))
    }
}
