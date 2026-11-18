package com.reelforge.editor.engine

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint

/** How the title sits on a cover picture. */
enum class CoverStyle(val label: String) {
    BOTTOM("Bottom"),
    CENTER("Big centre"),
    BOX("Box"),
    NONE("No title"),
}

/**
 * The cover (thumbnail) picture for a video: a frame or picture filling the video's shape, with a
 * title. Instagram and YouTube Shorts let you upload this as the cover.
 */
object CoverMaker {

    /** Where [source] (w×h) is drawn to fill [width]×[height], cropping the overflow. */
    fun fillRect(sourceW: Int, sourceH: Int, width: Int, height: Int): RectF {
        val scale = maxOf(width.toFloat() / sourceW, height.toFloat() / sourceH)
        val w = sourceW * scale
        val h = sourceH * scale
        return RectF((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2)
    }

    fun render(source: Bitmap, width: Int, height: Int, title: String, style: CoverStyle): Bitmap {
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.BLACK)
        canvas.drawBitmap(source, null, fillRect(source.width, source.height, width, height), Paint(Paint.FILTER_BITMAP_FLAG))
        val text = title.trim()
        if (text.isEmpty() || style == CoverStyle.NONE) return out

        val big = style == CoverStyle.CENTER
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textSize = minOf(width, height) * (if (big) 0.13f else 0.085f)
            setShadowLayer(textSize * 0.08f, 0f, textSize * 0.04f, 0xCC000000.toInt())
        }
        val margin = (width * 0.08f).toInt()
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, width - 2 * margin)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setMaxLines(4)
            .build()
        val top = when (style) {
            CoverStyle.CENTER -> (height - layout.height) / 2f
            else -> height * 0.86f - layout.height
        }
        when (style) {
            CoverStyle.BOTTOM -> {
                // A dark fade behind the text so it reads on any picture.
                val shade = Paint().apply {
                    shader = LinearGradient(0f, top - height * 0.15f, 0f, height.toFloat(),
                        0x00000000, 0xB0000000.toInt(), Shader.TileMode.CLAMP)
                }
                canvas.drawRect(0f, top - height * 0.15f, width.toFloat(), height.toFloat(), shade)
            }
            CoverStyle.BOX -> {
                val pad = paint.textSize * 0.4f
                val box = RectF(margin - pad, top - pad, width - margin + pad, top + layout.height + pad)
                canvas.drawRoundRect(box, pad, pad, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xE6FFC107.toInt() })
                paint.color = Color.BLACK
                paint.clearShadowLayer()
            }
            else -> Unit
        }
        canvas.save()
        canvas.translate(margin.toFloat(), top)
        layout.draw(canvas)
        canvas.restore()
        return out
    }

    /** Saves [picture] as a JPEG in Pictures/CutMitra. Returns where it went, or null. */
    fun saveToGallery(context: Context, picture: Bitmap, name: String): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "$name.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/CutMitra")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        return try {
            resolver.openOutputStream(uri)?.use { picture.compress(Bitmap.CompressFormat.JPEG, 92, it) }
                ?: error("cannot write")
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            uri
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            null
        }
    }
}
