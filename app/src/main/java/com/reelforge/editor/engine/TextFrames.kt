package com.reelforge.editor.engine

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import com.reelforge.editor.model.TextFrame
import com.reelforge.editor.model.TextItem
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin

/**
 * Draws the sticker shapes around texts ([TextFrame]): chat bubbles, terminal windows, frosted
 * glass, notifications, sticky notes, neon, HUD frames, versus badges and more. The words are
 * drawn by [EffectFactory.renderText] as usual and placed inside, so they stay editable.
 * Everything is drawn here, by the app: nothing to download or license.
 */
object TextFrames {
    /** Space around the words, in letter heights: left, top, right, bottom. */
    private fun insets(frame: TextFrame): FloatArray = when (frame) {
        TextFrame.NONE -> floatArrayOf(0f, 0f, 0f, 0f)
        TextFrame.CHAT_IN -> floatArrayOf(0.35f, 0.2f, 0.35f, 0.55f)
        TextFrame.CHAT_OUT -> floatArrayOf(0.35f, 0.2f, 0.95f, 0.55f)
        TextFrame.TERMINAL -> floatArrayOf(0.35f, 0.95f, 0.35f, 0.3f)
        TextFrame.ALERT -> floatArrayOf(0.45f, 1.25f, 0.45f, 0.4f)
        TextFrame.FROSTED -> floatArrayOf(0.5f, 0.4f, 0.5f, 0.4f)
        TextFrame.NOTIFICATION -> floatArrayOf(1.55f, 0.7f, 0.45f, 0.3f)
        TextFrame.TOGGLE -> floatArrayOf(0.45f, 0.3f, 2.0f, 0.3f)
        TextFrame.STICKY -> floatArrayOf(0.55f, 0.85f, 0.55f, 0.6f)
        TextFrame.NEON -> floatArrayOf(0.7f, 0.6f, 0.7f, 0.6f)
        TextFrame.MOVIE -> floatArrayOf(0.25f, 0.6f, 0.25f, 0.6f)
        TextFrame.CLOUD -> floatArrayOf(0.75f, 0.65f, 0.75f, 1.2f)
        TextFrame.RIBBON -> floatArrayOf(1.05f, 0.25f, 1.05f, 0.45f)
        TextFrame.VERSUS -> floatArrayOf(0.95f, 0.55f, 0.95f, 0.55f)
        TextFrame.HUD -> floatArrayOf(0.65f, 0.6f, 0.65f, 0.6f)
        TextFrame.HUD_RING -> floatArrayOf(0f, 0f, 0f, 0f) // made square in [render]
        TextFrame.ERROR -> floatArrayOf(0.4f, 1.1f, 0.4f, 0.35f)
        TextFrame.LEVEL_UP -> floatArrayOf(1.0f, 0.85f, 1.0f, 0.4f)
        TextFrame.DANGER -> floatArrayOf(0.25f, 0.6f, 0.25f, 0.6f)
        TextFrame.BURST -> floatArrayOf(1.25f, 1.0f, 1.25f, 1.0f)
        TextFrame.PROGRESS -> floatArrayOf(0.4f, 0.3f, 0.4f, 1.0f)
        TextFrame.HEX -> floatArrayOf(0.95f, 0.45f, 0.95f, 0.45f)
    }

    /**
     * [words] (the text as drawn without a frame) inside [item]'s frame. [s] is the letter size
     * in pixels; every part of the frame is sized from it, so the sticker scales with the text.
     */
    fun render(item: TextItem, words: Bitmap, s: Float): Bitmap {
        val frame = item.frame
        if (frame == TextFrame.NONE) return words
        val inset = insets(frame).map { it * s }
        var left = inset[0]
        var top = inset[1]
        var width = words.width + inset[0] + inset[2]
        var height = words.height + inset[1] + inset[3]
        if (frame == TextFrame.HUD_RING) {
            // A square around a circle that holds the words.
            val side = (Math.hypot(words.width.toDouble(), words.height.toDouble()) + s * 1.6).toFloat()
            width = side
            height = side
            left = (side - words.width) / 2f
            top = (side - words.height) / 2f
        }
        val bitmap = Bitmap.createBitmap(ceil(width).toInt().coerceAtLeast(2), ceil(height).toInt().coerceAtLeast(2), Bitmap.Config.ARGB_8888)
        val c = Canvas(bitmap)
        val w = bitmap.width.toFloat()
        val h = bitmap.height.toFloat()
        val words2 = RectF(left, top, left + words.width, top + words.height)
        back(c, frame, w, h, words2, s, item.frameColor, item.accentColor)
        c.drawBitmap(words, left, top, null)
        front(c, frame, w, h, words2, s, item.frameColor, item.accentColor)
        return bitmap
    }

    // ------------------------------------------------------------------ helpers

    private fun fill(colour: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colour }

    private fun stroke(colour: Int, width: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colour
        style = Paint.Style.STROKE
        strokeWidth = width
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }

    private fun withAlpha(colour: Int, alpha: Int) = (colour and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)

    /** [colour] made darker (0..1 of the way to black). */
    private fun darker(colour: Int, by: Float): Int {
        val k = 1f - by
        val r = ((colour shr 16 and 0xFF) * k).toInt()
        val g = ((colour shr 8 and 0xFF) * k).toInt()
        val b = ((colour and 0xFF) * k).toInt()
        return (colour and 0xFF000000.toInt()) or (r shl 16) or (g shl 8) or b
    }

    /** A soft shadow under shapes, so stickers stand out on any picture. */
    private fun shadowed(colour: Int, s: Float) = fill(colour).apply {
        setShadowLayer(s * 0.18f, 0f, s * 0.06f, 0x55000000)
    }

    private fun label(size: Float, colour: Int, bold: Boolean = true, mono: Boolean = false) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = colour
            textSize = size
            typeface = Typeface.create(if (mono) Typeface.MONOSPACE else Typeface.DEFAULT, if (bold) Typeface.BOLD else Typeface.NORMAL)
        }

    private fun drawLabel(c: Canvas, text: String, x: Float, centreY: Float, p: Paint) =
        c.drawText(text, x, centreY - (p.descent() + p.ascent()) / 2f, p)

    private fun star(cx: Float, cy: Float, outer: Float, inner: Float, points: Int = 5, turn: Double = -PI / 2) = Path().apply {
        for (i in 0 until points * 2) {
            val r = if (i % 2 == 0) outer else inner
            val a = turn + i * PI / points
            val x = cx + (cos(a) * r).toFloat()
            val y = cy + (sin(a) * r).toFloat()
            if (i == 0) moveTo(x, y) else lineTo(x, y)
        }
        close()
    }

    // ------------------------------------------------------------------ behind the words

    private fun back(c: Canvas, frame: TextFrame, w: Float, h: Float, words: RectF, s: Float, main: Int, accent: Int) {
        when (frame) {
            TextFrame.NONE -> Unit

            TextFrame.CHAT_IN, TextFrame.CHAT_OUT -> {
                val sent = frame == TextFrame.CHAT_OUT
                val body = RectF(s * 0.05f, s * 0.05f, w - s * 0.05f, h - s * 0.4f)
                val r = s * 0.45f
                val tail = Path().apply {
                    if (sent) {
                        moveTo(body.right - s * 0.75f, body.bottom - s * 0.2f)
                        quadTo(body.right - s * 0.2f, body.bottom + s * 0.1f, body.right + s * 0.0f, h - s * 0.05f)
                        quadTo(body.right - s * 0.55f, body.bottom + s * 0.25f, body.right - s * 1.15f, body.bottom - s * 0.1f)
                    } else {
                        moveTo(body.left + s * 0.75f, body.bottom - s * 0.2f)
                        quadTo(body.left + s * 0.2f, body.bottom + s * 0.1f, body.left, h - s * 0.05f)
                        quadTo(body.left + s * 0.55f, body.bottom + s * 0.25f, body.left + s * 1.15f, body.bottom - s * 0.1f)
                    }
                    close()
                }
                val p = shadowed(main, s)
                c.drawPath(tail, p)
                c.drawRoundRect(body, r, r, p)
            }

            TextFrame.TERMINAL, TextFrame.ERROR -> {
                val body = RectF(s * 0.04f, s * 0.04f, w - s * 0.04f, h - s * 0.04f)
                val r = s * 0.16f
                c.drawRoundRect(body, r, r, shadowed(main, s))
                // The title bar.
                val bar = RectF(body.left, body.top, body.right, body.top + s * 0.7f)
                c.save()
                c.clipRect(bar)
                c.drawRoundRect(body, r, r, fill(if (frame == TextFrame.ERROR) accent else withAlpha(accent, 0x40)))
                c.restore()
                c.drawRoundRect(body, r, r, stroke(accent, s * 0.05f))
                val cy = bar.centerY()
                if (frame == TextFrame.TERMINAL) {
                    listOf(0xFFFF5F57.toInt(), 0xFFFEBC2E.toInt(), 0xFF28C840.toInt()).forEachIndexed { i, dot ->
                        c.drawCircle(body.left + s * (0.38f + i * 0.34f), cy, s * 0.12f, fill(dot))
                    }
                    val p = label(s * 0.32f, withAlpha(accent, 0xCC), bold = false, mono = true)
                    drawLabel(c, "terminal", body.left + s * 1.4f, cy, p)
                } else {
                    val p = label(s * 0.36f, 0xFFFFFFFF.toInt())
                    drawLabel(c, "⚠ ERROR", body.left + s * 0.3f, cy, p)
                    // A close box on the right.
                    val x = body.right - s * 0.45f
                    val box = RectF(x - s * 0.22f, cy - s * 0.22f, x + s * 0.22f, cy + s * 0.22f)
                    c.drawRect(box, stroke(0xFFFFFFFF.toInt(), s * 0.05f))
                    val k = s * 0.11f
                    c.drawLine(x - k, cy - k, x + k, cy + k, stroke(0xFFFFFFFF.toInt(), s * 0.05f))
                    c.drawLine(x + k, cy - k, x - k, cy + k, stroke(0xFFFFFFFF.toInt(), s * 0.05f))
                }
            }

            TextFrame.ALERT -> {
                val body = RectF(s * 0.05f, s * 0.55f, w - s * 0.05f, h - s * 0.05f)
                c.drawRect(body, shadowed(main, s))
                c.drawRect(body, stroke(accent, s * 0.06f))
                val inner = RectF(body.left + s * 0.14f, body.top + s * 0.14f, body.right - s * 0.14f, body.bottom - s * 0.14f)
                c.drawRect(inner, stroke(withAlpha(accent, 0x88), s * 0.025f))
                // A warning triangle sitting on the top edge.
                val cx = w / 2f
                val tri = Path().apply {
                    moveTo(cx, s * 0.05f)
                    lineTo(cx + s * 0.62f, s * 1.05f)
                    lineTo(cx - s * 0.62f, s * 1.05f)
                    close()
                }
                c.drawRect(cx - s * 0.85f, body.top - s * 0.04f, cx + s * 0.85f, body.top + s * 0.08f, fill(main))
                c.drawPath(tri, fill(main))
                c.drawPath(tri, stroke(accent, s * 0.07f))
                val p = label(s * 0.55f, accent).apply { textAlign = Paint.Align.CENTER }
                c.drawText("!", cx, s * 0.92f, p)
            }

            TextFrame.FROSTED -> {
                val body = RectF(s * 0.05f, s * 0.05f, w - s * 0.05f, h - s * 0.05f)
                val r = s * 0.55f
                c.drawRoundRect(body, r, r, fill(main).apply { setShadowLayer(s * 0.3f, 0f, s * 0.08f, 0x33000000) })
                // Light catching the top of the glass.
                val shine = fill(0).apply {
                    shader = LinearGradient(0f, body.top, 0f, body.centerY(), 0x66FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
                }
                c.drawRoundRect(body, r, r, shine)
                c.drawRoundRect(body, r, r, stroke(withAlpha(0xFFFFFFFF.toInt(), 0xAA), s * 0.04f))
            }

            TextFrame.NOTIFICATION, TextFrame.TOGGLE -> {
                val body = RectF(s * 0.05f, s * 0.05f, w - s * 0.05f, h - s * 0.05f)
                val r = if (frame == TextFrame.TOGGLE) body.height() / 2f else s * 0.4f
                c.drawRoundRect(body, r, r, shadowed(main, s))
                if (frame == TextFrame.NOTIFICATION) {
                    // App icon: a rounded square with a speech bubble.
                    val side = s * 0.95f
                    val ix = body.left + s * 0.3f
                    val iy = body.top + (body.height() - side) / 2f + s * 0.12f
                    val icon = RectF(ix, iy, ix + side, iy + side)
                    c.drawRoundRect(icon, side * 0.28f, side * 0.28f, fill(accent))
                    c.drawCircle(icon.centerX(), icon.centerY() - side * 0.04f, side * 0.26f, fill(0xFFFFFFFF.toInt()))
                    c.drawPath(Path().apply {
                        moveTo(icon.centerX() - side * 0.18f, icon.centerY() + side * 0.12f)
                        lineTo(icon.centerX() - side * 0.24f, icon.centerY() + side * 0.32f)
                        lineTo(icon.centerX() - side * 0.02f, icon.centerY() + side * 0.2f)
                        close()
                    }, fill(0xFFFFFFFF.toInt()))
                    val grey = label(s * 0.3f, 0xFF8A8A8E.toInt(), bold = false)
                    drawLabel(c, "MESSAGE", words.left + s * 0.25f, body.top + s * 0.42f, grey)
                    grey.textAlign = Paint.Align.RIGHT
                    drawLabel(c, "now", body.right - s * 0.35f, body.top + s * 0.42f, grey)
                } else {
                    // A switch, turned on.
                    val th = s * 0.72f
                    val tw = s * 1.3f
                    val tx = body.right - s * 0.4f - tw
                    val ty = body.centerY() - th / 2f
                    c.drawRoundRect(RectF(tx, ty, tx + tw, ty + th), th / 2f, th / 2f, fill(accent))
                    c.drawCircle(tx + tw - th / 2f, ty + th / 2f, th * 0.42f, shadowed(0xFFFFFFFF.toInt(), s * 0.5f))
                }
            }

            TextFrame.STICKY -> {
                val body = RectF(s * 0.1f, s * 0.35f, w - s * 0.1f, h - s * 0.1f)
                val fold = s * 0.55f
                val paper = Path().apply {
                    moveTo(body.left, body.top)
                    lineTo(body.right, body.top)
                    lineTo(body.right, body.bottom - fold)
                    lineTo(body.right - fold, body.bottom)
                    lineTo(body.left, body.bottom)
                    close()
                }
                c.drawPath(paper, shadowed(main, s))
                c.drawPath(Path().apply {
                    moveTo(body.right, body.bottom - fold)
                    lineTo(body.right - fold, body.bottom - fold)
                    lineTo(body.right - fold, body.bottom)
                    close()
                }, fill(darker(main, 0.18f)))
                // A strip of tape holding it up.
                c.save()
                c.rotate(-5f, w / 2f, body.top)
                c.drawRect(w / 2f - s * 0.85f, body.top - s * 0.28f, w / 2f + s * 0.85f, body.top + s * 0.28f, fill(withAlpha(accent, 0xB0)))
                c.restore()
            }

            TextFrame.NEON -> {
                val body = RectF(s * 0.3f, s * 0.3f, w - s * 0.3f, h - s * 0.3f)
                val r = s * 0.35f
                c.drawRoundRect(body, r, r, fill(main))
                val glow = stroke(accent, s * 0.16f).apply { maskFilter = BlurMaskFilter(s * 0.25f, BlurMaskFilter.Blur.NORMAL) }
                c.drawRoundRect(body, r, r, glow)
                c.drawRoundRect(body, r, r, glow)
                c.drawRoundRect(body, r, r, stroke(accent, s * 0.07f))
                c.drawRoundRect(body, r, r, stroke(withAlpha(0xFFFFFFFF.toInt(), 0xCC), s * 0.025f))
            }

            TextFrame.MOVIE -> {
                for (y in listOf(s * 0.3f, h - s * 0.3f)) {
                    val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        strokeWidth = s * 0.04f
                        shader = LinearGradient(0f, 0f, w, 0f, intArrayOf(withAlpha(accent, 0), accent, withAlpha(accent, 0)), null, Shader.TileMode.CLAMP)
                    }
                    c.drawLine(0f, y, w, y, p)
                    c.drawPath(Path().apply {
                        moveTo(w / 2f, y - s * 0.13f)
                        lineTo(w / 2f + s * 0.13f, y)
                        lineTo(w / 2f, y + s * 0.13f)
                        lineTo(w / 2f - s * 0.13f, y)
                        close()
                    }, fill(accent))
                }
            }

            TextFrame.CLOUD -> {
                val body = RectF(s * 0.45f, s * 0.4f, w - s * 0.45f, h - s * 0.85f)
                val p = shadowed(main, s)
                // Puffs along the edge, then the middle.
                val puff = s * 0.45f
                var x = body.left
                while (x <= body.right) {
                    c.drawCircle(x, body.top, puff, p)
                    c.drawCircle(x, body.bottom, puff, p)
                    x += puff * 1.2f
                }
                var y = body.top
                while (y <= body.bottom) {
                    c.drawCircle(body.left, y, puff, p)
                    c.drawCircle(body.right, y, puff, p)
                    y += puff * 1.2f
                }
                c.drawRect(body, fill(main))
                c.drawCircle(body.left + s * 0.5f, h - s * 0.45f, s * 0.2f, p)
                c.drawCircle(body.left + s * 0.15f, h - s * 0.15f, s * 0.12f, p)
            }

            TextFrame.RIBBON -> {
                val body = RectF(s * 0.6f, s * 0.05f, w - s * 0.6f, h - s * 0.3f)
                val tailTop = body.top + s * 0.3f
                val tailBottom = h - s * 0.02f
                val dark = darker(main, 0.3f)
                for (leftSide in listOf(true, false)) {
                    val edge = if (leftSide) 0f else w
                    val inner = if (leftSide) body.left + s * 0.4f else body.right - s * 0.4f
                    val notch = if (leftSide) s * 0.35f else w - s * 0.35f
                    c.drawPath(Path().apply {
                        moveTo(inner, tailTop)
                        lineTo(edge, tailTop)
                        lineTo(notch, (tailTop + tailBottom) / 2f)
                        lineTo(edge, tailBottom)
                        lineTo(inner, tailBottom)
                        close()
                    }, fill(dark))
                }
                c.drawRect(body, shadowed(main, s))
                c.drawLine(body.left, body.top + s * 0.12f, body.right, body.top + s * 0.12f, stroke(withAlpha(accent, 0xAA), s * 0.03f))
                c.drawLine(body.left, body.bottom - s * 0.12f, body.right, body.bottom - s * 0.12f, stroke(withAlpha(accent, 0xAA), s * 0.03f))
            }

            TextFrame.VERSUS -> {
                val slant = s * 0.6f
                val mid = w / 2f
                c.drawPath(Path().apply {
                    moveTo(slant, s * 0.1f); lineTo(mid + s * 0.2f, s * 0.1f); lineTo(mid - s * 0.2f, h - s * 0.1f); lineTo(0f, h - s * 0.1f); close()
                }, shadowed(main, s))
                c.drawPath(Path().apply {
                    moveTo(mid + s * 0.2f, s * 0.1f); lineTo(w, s * 0.1f); lineTo(w - slant, h - s * 0.1f); lineTo(mid - s * 0.2f, h - s * 0.1f); close()
                }, shadowed(accent, s))
                // A lightning crack down the middle.
                val bolt = Path().apply {
                    moveTo(mid + s * 0.35f, 0f)
                    lineTo(mid - s * 0.1f, h * 0.45f)
                    lineTo(mid + s * 0.2f, h * 0.5f)
                    lineTo(mid - s * 0.35f, h)
                }
                c.drawPath(bolt, stroke(0xFFFFFFFF.toInt(), s * 0.08f).apply { maskFilter = BlurMaskFilter(s * 0.08f, BlurMaskFilter.Blur.SOLID) })
            }

            TextFrame.HUD -> {
                val body = RectF(s * 0.15f, s * 0.15f, w - s * 0.15f, h - s * 0.15f)
                c.drawRect(body, fill(main))
                c.drawRect(body, stroke(withAlpha(accent, 0x66), s * 0.025f))
                val arm = s * 0.65f
                val p = stroke(accent, s * 0.08f).apply { strokeCap = Paint.Cap.SQUARE }
                for ((x, y, dx, dy) in listOf(
                    floatArrayOf(body.left, body.top, 1f, 1f), floatArrayOf(body.right, body.top, -1f, 1f),
                    floatArrayOf(body.left, body.bottom, 1f, -1f), floatArrayOf(body.right, body.bottom, -1f, -1f),
                )) {
                    c.drawLine(x, y, x + arm * dx, y, p)
                    c.drawLine(x, y, x, y + arm * dy, p)
                }
                // Ticks along the top and bottom.
                val tick = stroke(withAlpha(accent, 0xAA), s * 0.03f)
                var x = body.left + arm * 1.4f
                while (x < body.right - arm * 1.4f) {
                    c.drawLine(x, body.top, x, body.top + s * 0.12f, tick)
                    c.drawLine(x, body.bottom, x, body.bottom - s * 0.12f, tick)
                    x += s * 0.3f
                }
            }

            TextFrame.HUD_RING -> {
                val cx = w / 2f
                val cy = h / 2f
                val r = w / 2f - s * 0.25f
                c.drawCircle(cx, cy, r * 0.86f, fill(main))
                c.drawCircle(cx, cy, r * 0.86f, stroke(accent, s * 0.07f))
                // An outer ring in pieces, and aiming marks.
                val outer = RectF(cx - r, cy - r, cx + r, cy + r)
                for (k in 0 until 4) c.drawArc(outer, k * 90f + 12f, 66f, false, stroke(withAlpha(accent, 0xBB), s * 0.05f))
                val mark = stroke(accent, s * 0.07f)
                c.drawLine(cx, cy - r - s * 0.2f, cx, cy - r + s * 0.3f, mark)
                c.drawLine(cx, cy + r + s * 0.2f, cx, cy + r - s * 0.3f, mark)
                c.drawLine(cx - r - s * 0.2f, cy, cx - r + s * 0.3f, cy, mark)
                c.drawLine(cx + r + s * 0.2f, cy, cx + r - s * 0.3f, cy, mark)
            }

            TextFrame.LEVEL_UP -> {
                val top = s * 0.6f
                val body = RectF(s * 0.5f, top, w - s * 0.5f, h - s * 0.05f)
                val point = s * 0.45f
                val banner = Path().apply {
                    moveTo(body.left, body.top); lineTo(body.right, body.top)
                    lineTo(body.right + point, body.centerY()); lineTo(body.right, body.bottom)
                    lineTo(body.left, body.bottom); lineTo(body.left - point, body.centerY()); close()
                }
                c.drawPath(banner, shadowed(main, s).apply {
                    shader = LinearGradient(0f, body.top, 0f, body.bottom, intArrayOf(lighter(main), main, darker(main, 0.25f)), null, Shader.TileMode.CLAMP)
                })
                c.drawPath(banner, stroke(accent, s * 0.06f))
                // Three stars on top, the middle one biggest.
                val cx = w / 2f
                for ((dx, size) in listOf(-0.75f to 0.3f, 0f to 0.42f, 0.75f to 0.3f)) {
                    val y = top - s * 0.05f - (if (dx == 0f) s * 0.12f else 0f)
                    val path = star(cx + dx * s, y, size * s, size * s * 0.45f)
                    c.drawPath(path, fill(lighter(main)))
                    c.drawPath(path, stroke(accent, s * 0.04f))
                }
            }

            TextFrame.DANGER -> {
                val band = s * 0.45f
                val body = RectF(0f, band, w, h - band)
                c.drawRect(body, fill(withAlpha(0xFF000000.toInt(), 0xAA)))
                for (y0 in listOf(0f, h - band)) {
                    c.save()
                    c.clipRect(0f, y0, w, y0 + band)
                    c.drawRect(0f, y0, w, y0 + band, fill(main))
                    val stripe = fill(accent)
                    var x = -band
                    while (x < w + band) {
                        c.drawPath(Path().apply {
                            moveTo(x, y0 + band); lineTo(x + band * 0.6f, y0 + band)
                            lineTo(x + band * 1.6f, y0); lineTo(x + band, y0); close()
                        }, stripe)
                        x += band * 1.3f
                    }
                    c.restore()
                }
            }

            TextFrame.BURST -> {
                val cx = w / 2f
                val cy = h / 2f
                val spikes = 14
                val path = Path()
                for (i in 0 until spikes * 2) {
                    val a = i * PI / spikes - PI / 2
                    val k = if (i % 2 == 0) 1f else 0.72f
                    val x = cx + (cos(a) * (w / 2f - s * 0.08f) * k).toFloat()
                    val y = cy + (sin(a) * (h / 2f - s * 0.08f) * k).toFloat()
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                path.close()
                c.drawPath(path, shadowed(main, s))
                c.drawPath(path, stroke(accent, s * 0.09f))
            }

            TextFrame.PROGRESS -> {
                val bar = RectF(s * 0.4f, h - s * 0.75f, w - s * 0.4f, h - s * 0.35f)
                val r = bar.height() / 2f
                c.drawRoundRect(bar, r, r, fill(main))
                c.drawRoundRect(RectF(bar.left, bar.top, bar.left + bar.width() * 0.72f, bar.bottom), r, r, fill(accent))
                c.drawRoundRect(bar, r, r, stroke(withAlpha(0xFFFFFFFF.toInt(), 0x88), s * 0.03f))
            }

            TextFrame.HEX -> {
                val point = s * 0.7f
                val hex = Path().apply {
                    moveTo(point, s * 0.08f); lineTo(w - point, s * 0.08f); lineTo(w - s * 0.05f, h / 2f)
                    lineTo(w - point, h - s * 0.08f); lineTo(point, h - s * 0.08f); lineTo(s * 0.05f, h / 2f); close()
                }
                c.drawPath(hex, shadowed(main, s))
                c.drawPath(hex, stroke(accent, s * 0.07f))
                val inner = Path().apply {
                    val k = s * 0.16f
                    moveTo(point + k * 0.5f, s * 0.08f + k); lineTo(w - point - k * 0.5f, s * 0.08f + k); lineTo(w - s * 0.05f - k, h / 2f)
                    lineTo(w - point - k * 0.5f, h - s * 0.08f - k); lineTo(point + k * 0.5f, h - s * 0.08f - k); lineTo(s * 0.05f + k, h / 2f); close()
                }
                c.drawPath(inner, stroke(withAlpha(accent, 0x77), s * 0.025f))
            }
        }
    }

    private fun lighter(colour: Int): Int {
        val r = (colour shr 16 and 0xFF).let { it + (255 - it) / 2 }
        val g = (colour shr 8 and 0xFF).let { it + (255 - it) / 2 }
        val b = (colour and 0xFF).let { it + (255 - it) / 2 }
        return (colour and 0xFF000000.toInt()) or (r shl 16) or (g shl 8) or b
    }

    // ------------------------------------------------------------------ in front of the words

    @Suppress("UNUSED_PARAMETER")
    private fun front(c: Canvas, frame: TextFrame, w: Float, h: Float, words: RectF, s: Float, main: Int, accent: Int) {
        if (frame == TextFrame.CHAT_OUT) {
            // "Seen" ticks in the corner.
            val p = label(s * 0.34f, accent).apply { textAlign = Paint.Align.RIGHT }
            drawLabel(c, "✓✓", w - s * 0.3f, h - s * 0.65f, p)
        }
    }
}
