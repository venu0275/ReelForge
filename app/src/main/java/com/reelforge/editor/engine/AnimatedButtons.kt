package com.reelforge.editor.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Animated channel buttons, drawn by the app frame by frame (no GIFs, no downloads, no licence).
 * The same drawing is used for the preview and the exported video, so they always match.
 *
 *  - SUBSCRIBE: pops in, a hand taps it, it turns grey as "SUBSCRIBED ✓", then the hand taps a
 *    bell that rings.
 *  - LIKE: an outlined button pops in, the 👍 bounces and the button fills with colour.
 *  - FOLLOW, SHARE, COMMENT: slide in, pulse, and slide out at the end.
 *  - BELL: a bell pops in and rings, a red "1" badge appears and "NOTIFICATIONS ON" slides out.
 *
 * [ButtonSounds] has matching sounds, for the "with sound" versions.
 */
enum class AnimatedButton(
    override val title: String,
    val label: String,
    val colour: Int,
    val textColour: Int = 0xFFFFFFFF.toInt(),
    /** How long it is on the video when added (it can be changed like any layer). */
    override val defaultMs: Long,
) : MovingArt {
    SUBSCRIBE("Subscribe + bell", "SUBSCRIBE", 0xFFE53935.toInt(), defaultMs = 4_000),
    LIKE("Like", "LIKE", 0xFF1565C0.toInt(), defaultMs = 3_000),
    FOLLOW("Follow", "FOLLOW", 0xFF1E88E5.toInt(), defaultMs = 3_500),
    SHARE("Share", "↗ SHARE", 0xFF2E7D32.toInt(), defaultMs = 3_500),
    COMMENT("Comment", "💬 COMMENT", 0xFFEF6C00.toInt(), defaultMs = 3_500),
    BELL("Notification bell", "NOTIFICATIONS ON", 0xFFFFB300.toInt(), 0xFF212121.toInt(), defaultMs = 3_500);

    override fun aspect(frameAspect: Float): Float = AnimatedButtons.aspect(this)

    override fun draw(canvas: Canvas, tMs: Long, durationMs: Long, w: Float, h: Float) =
        AnimatedButtons.draw(canvas, this, tMs, durationMs, w, h)

    companion object {
        fun of(name: String?): AnimatedButton? = entries.firstOrNull { it.name == name }
    }
}

object AnimatedButtons {
    private const val SUBSCRIBED = "SUBSCRIBED ✓"
    private const val HAND = "👆"
    private const val BELL = "🔔"
    private const val THUMB = "👍"

    // ---------------------------------------------------------------- timing helpers

    private fun clamp01(v: Float) = v.coerceIn(0f, 1f)
    private fun span(t: Float, from: Float, to: Float) = clamp01((t - from) / (to - from))
    private fun easeOut(p: Float) = 1f - (1f - p) * (1f - p) * (1f - p)
    private fun easeInOut(p: Float) = if (p < 0.5f) 4f * p * p * p else 1f - (-2f * p + 2f).let { it * it * it } / 2f

    /** 0 → a little over 1 → 1: the "pop" of a button appearing. */
    private fun popIn(p: Float): Float {
        if (p <= 0f) return 0f
        if (p >= 1f) return 1f
        val c1 = 1.70158f
        val c3 = c1 + 1f
        val q = p - 1f
        return 1f + c3 * q * q * q + c1 * q * q
    }

    // ---------------------------------------------------------------- size

    private fun textPaint(h: Float, colour: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colour
        textSize = h * 0.42f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }

    /** The button's own width for a button [h] high. */
    private fun buttonWidth(b: AnimatedButton, h: Float): Float {
        val p = textPaint(h, 0)
        val text = when (b) {
            AnimatedButton.SUBSCRIBE -> max(p.measureText(b.label), p.measureText(SUBSCRIBED))
            AnimatedButton.LIKE -> p.measureText(b.label) + h * 0.75f // room for the thumb
            else -> p.measureText(b.label)
        }
        return text + h * 0.9f
    }

    /**
     * Width / height of the whole picture: the button plus room around it for the hand, the
     * bell, the bounce and the slide.
     */
    fun aspect(b: AnimatedButton): Float {
        val h = 100f
        val w = buttonWidth(b, h)
        return when (b) {
            AnimatedButton.SUBSCRIBE -> (w + h * 1.5f) / (h * 2.1f)
            AnimatedButton.LIKE -> (w + h * 0.8f) / (h * 2.1f)
            AnimatedButton.BELL -> (w + h * 1.75f) / (h * 2.1f)
            else -> (w * 1.25f) / (h * 1.5f)
        }
    }

    // ---------------------------------------------------------------- drawing

    /**
     * Draws [b] as it looks [tMs] after it appears, on a [w] x [h] canvas (transparent around
     * the button). [durationMs] is how long it is shown, for the slide out at the end.
     */
    fun draw(canvas: Canvas, b: AnimatedButton, tMs: Long, durationMs: Long, w: Float, h: Float) {
        when (b) {
            AnimatedButton.SUBSCRIBE -> subscribe(canvas, b, tMs.toFloat(), w, h)
            AnimatedButton.LIKE -> like(canvas, b, tMs.toFloat(), w, h)
            AnimatedButton.BELL -> bell(canvas, b, tMs.toFloat(), durationMs.toFloat(), w, h)
            else -> slide(canvas, b, tMs.toFloat(), durationMs.toFloat(), w, h)
        }
    }

    private fun roundButton(c: Canvas, cx: Float, cy: Float, bw: Float, bh: Float, fill: Int) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fill }
        val r = bh * 0.28f
        c.drawRoundRect(RectF(cx - bw / 2, cy - bh / 2, cx + bw / 2, cy + bh / 2), r, r, paint)
    }

    private fun label(c: Canvas, text: String, cx: Float, cy: Float, bh: Float, colour: Int, alpha: Int = 255) {
        val p = textPaint(bh, colour).apply { this.alpha = alpha }
        val baseline = cy - (p.descent() + p.ascent()) / 2f
        c.drawText(text, cx, baseline, p)
    }

    /** An emoji centred on (x, y), [size] high, turned by [degrees] around [pivotY] (0 top .. 1 bottom). */
    private fun emoji(c: Canvas, e: String, x: Float, y: Float, size: Float, degrees: Float = 0f, pivotY: Float = 0.5f, alpha: Int = 255) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            textAlign = Paint.Align.CENTER
            this.alpha = alpha
        }
        val top = y - size / 2f
        c.save()
        c.rotate(degrees, x, top + size * pivotY)
        val baseline = y - (p.descent() + p.ascent()) / 2f
        c.drawText(e, x, baseline, p)
        c.restore()
    }

    private fun subscribe(c: Canvas, b: AnimatedButton, t: Float, w: Float, h: Float) {
        val bh = h / 2.1f
        val bw = buttonWidth(b, bh)
        val cx = bh * 0.15f + bw / 2f
        val cy = h * 0.40f
        val bellX = cx + bw / 2f + bh * 0.75f

        // Pop in, then a small press when the hand taps.
        val press = 1f - 0.06f * sin(PI.toFloat() * span(t, 1_050f, 1_250f))
        val s = popIn(span(t, 0f, 380f)) * press
        val subscribed = t >= 1_200f
        c.save()
        c.scale(s, s, cx, cy)
        roundButton(c, cx, cy, bw, bh, if (subscribed) 0xFF616161.toInt() else b.colour)
        label(c, if (subscribed) SUBSCRIBED else b.label, cx, cy, bh, b.textColour)
        c.restore()

        // Ripple where the button was tapped.
        val ripple = span(t, 1_150f, 1_600f)
        if (ripple in 0.001f..0.999f) {
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFFFFFFFF.toInt()
                alpha = (110 * (1f - ripple)).toInt()
            }
            c.drawCircle(cx + bw * 0.12f, cy, bh * 0.25f + bh * 0.9f * ripple, p)
        }

        // The bell pops in after subscribing and rings when tapped.
        if (t >= 1_250f) {
            val bellPop = popIn(span(t, 1_250f, 1_550f))
            val ring = if (t >= 1_850f) {
                val since = (t - 1_850f) / 1000f
                (24.0 * sin(since * 2 * PI * 4.5) * exp(-since * 1.8)).toFloat()
            } else 0f
            c.save()
            c.scale(bellPop, bellPop, bellX, cy)
            emoji(c, BELL, bellX, cy, bh * 0.95f, ring, pivotY = 0.12f)
            c.restore()
        }

        // The hand: comes in, taps Subscribe, moves to the bell, taps it, goes.
        if (t in 300f..2_500f) {
            val start = Pair(cx + bw * 0.55f, cy + bh * 1.5f)
            val onButton = Pair(cx + bw * 0.12f, cy + bh * 0.55f)
            val onBell = Pair(bellX + bh * 0.05f, cy + bh * 0.6f)
            val (hx, hy) = when {
                t < 1_000f -> lerp(start, onButton, easeInOut(span(t, 300f, 1_000f)))
                t < 1_350f -> onButton
                t < 1_750f -> lerp(onButton, onBell, easeInOut(span(t, 1_350f, 1_750f)))
                else -> onBell
            }
            val tap = 1f - 0.18f * (sin(PI.toFloat() * span(t, 1_050f, 1_250f)) + sin(PI.toFloat() * span(t, 1_750f, 1_900f)))
            val fade = (255 * (1f - span(t, 2_100f, 2_500f)) * span(t, 300f, 450f)).toInt()
            c.save()
            c.scale(tap, tap, hx, hy)
            // The finger tip sits at (hx, hy): the emoji is drawn below it.
            emoji(c, HAND, hx, hy + bh * 0.48f, bh * 1.0f, -12f, alpha = fade)
            c.restore()
        }
    }

    private fun like(c: Canvas, b: AnimatedButton, t: Float, w: Float, h: Float) {
        val bh = h / 2.1f
        val bw = buttonWidth(b, bh)
        val cx = w / 2f
        val cy = h * 0.5f
        val s = popIn(span(t, 0f, 380f))
        val left = cx - bw / 2
        val r = bh * 0.28f
        val fill = easeOut(span(t, 650f, 1_000f))

        c.save()
        c.scale(s, s, cx, cy)
        // Outline first, then the colour sweeping in from the left.
        val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
        val rect = RectF(left, cy - bh / 2, left + bw, cy + bh / 2)
        c.drawRoundRect(rect, r, r, white)
        val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = b.colour
            style = Paint.Style.STROKE
            strokeWidth = bh * 0.07f
        }
        c.drawRoundRect(RectF(rect.left + bh * 0.035f, rect.top + bh * 0.035f, rect.right - bh * 0.035f, rect.bottom - bh * 0.035f), r, r, border)
        val textX = left + bh * 0.75f + (bw - bh * 0.75f) / 2f
        label(c, b.label, textX, cy, bh, b.colour)
        if (fill > 0f) {
            c.save()
            c.clipRect(left, cy - bh / 2, left + bw * fill, cy + bh / 2)
            roundButton(c, cx, cy, bw, bh, b.colour)
            label(c, b.label, textX, cy, bh, b.textColour)
            c.restore()
        }
        c.restore()

        // The thumb bounces up, turns, and sparks fly.
        val thumbX = left + bh * 0.55f
        val bounce = sin(PI.toFloat() * span(t, 450f, 1_000f))
        val thumbScale = s * (1f + 0.55f * bounce)
        val thumbY = cy - bh * 0.25f * bounce
        c.save()
        c.scale(thumbScale, thumbScale, thumbX, thumbY)
        emoji(c, THUMB, thumbX, thumbY, bh * 0.78f, -18f * bounce)
        c.restore()
        val spark = span(t, 850f, 1_400f)
        if (spark in 0.001f..0.999f) {
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFFFFD54F.toInt()
                alpha = (255 * (1f - spark)).toInt()
            }
            for (i in 0 until 8) {
                val a = i * PI / 4
                val d = bh * (0.45f + 0.6f * easeOut(spark))
                c.drawCircle(thumbX + (cos(a) * d).toFloat(), thumbY + (sin(a) * d).toFloat(), bh * 0.06f, p)
            }
        }
    }

    private fun bell(c: Canvas, b: AnimatedButton, t: Float, duration: Float, w: Float, h: Float) {
        val bh = h / 2.1f
        val bw = buttonWidth(b, bh)
        val bellX = bh * 0.95f
        val cy = h * 0.5f
        val fadeOut = 1f - span(t, duration - 400f, duration)
        c.saveLayerAlpha(0f, 0f, w, h, (255 * fadeOut).toInt())

        // The label slides out from behind the bell.
        val reveal = easeOut(span(t, 900f, 1_300f))
        if (reveal > 0f) {
            val left = bellX + bh * 0.2f
            c.save()
            c.clipRect(left, 0f, left + (bw + bh * 0.4f) * reveal, h)
            val cx = left + bh * 0.4f + bw / 2f
            roundButton(c, cx, cy, bw, bh * 0.82f, b.colour)
            label(c, b.label, cx, cy, bh * 0.82f, b.textColour)
            c.restore()
        }

        // Sound waves while it rings.
        val waves = span(t, 500f, 1_700f)
        if (waves in 0.001f..0.999f) {
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFFFFFFFF.toInt()
                style = Paint.Style.STROKE
                strokeWidth = bh * 0.06f
                strokeCap = Paint.Cap.ROUND
            }
            for (k in 0 until 2) {
                val phase = ((t - 500f) / 400f + k * 0.5f) % 1f
                p.alpha = (200 * (1f - phase) * (1f - waves)).toInt()
                val r = bh * (0.55f + 0.35f * phase)
                c.drawArc(RectF(bellX - r, cy - r, bellX + r, cy + r), 150f, 60f, false, p)
                c.drawArc(RectF(bellX - r, cy - r, bellX + r, cy + r), -30f, 60f, false, p)
            }
        }

        // The bell: pops in, swings and slowly stops.
        val s = popIn(span(t, 0f, 380f))
        val ring = if (t >= 500f) {
            val since = (t - 500f) / 1000f
            (26.0 * sin(since * 2 * PI * 4.5) * exp(-since * 1.6)).toFloat()
        } else 0f
        c.save()
        c.scale(s, s, bellX, cy)
        emoji(c, BELL, bellX, cy, bh * 1.05f, ring, pivotY = 0.12f)
        c.restore()

        // A red badge with "1".
        val badge = popIn(span(t, 560f, 860f))
        if (badge > 0f) {
            val bx = bellX + bh * 0.36f
            val by = cy - bh * 0.36f
            val r = bh * 0.2f * badge
            c.drawCircle(bx, by, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE53935.toInt() })
            val p = textPaint(bh * 0.55f * badge, 0xFFFFFFFF.toInt())
            c.drawText("1", bx, by - (p.descent() + p.ascent()) / 2f, p)
        }
        c.restore()
    }

    private fun slide(c: Canvas, b: AnimatedButton, t: Float, duration: Float, w: Float, h: Float) {
        val bh = h / 1.5f
        val bw = buttonWidth(b, bh)
        val inP = easeOut(span(t, 0f, 450f))
        val outP = span(t, duration - 450f, duration).let { it * it }
        val travel = w * 0.35f
        val dx = -travel * (1f - inP) + travel * outP
        val alpha = (255 * inP * (1f - outP)).toInt()
        val pulse = if (t > 450f) 1f + 0.06f * sin(2f * PI.toFloat() * (t - 450f) / 800f) else 1f
        val cx = w / 2f + dx
        val cy = h / 2f
        c.save()
        c.scale(pulse, pulse, cx, cy)
        c.saveLayerAlpha(0f, 0f, w, h, alpha)
        roundButton(c, cx, cy, bw, bh, b.colour)
        label(c, b.label, cx, cy, bh, b.textColour)
        c.restore()
        c.restore()
    }

    private fun lerp(a: Pair<Float, Float>, b: Pair<Float, Float>, p: Float) =
        Pair(a.first + (b.first - a.first) * p, a.second + (b.second - a.second) * p)

    // ---------------------------------------------------------------- pictures

    /** The picture of [b] at [tMs], [height] pixels high (a new bitmap). */
    fun render(b: AnimatedButton, tMs: Long, durationMs: Long, height: Int): Bitmap {
        val hgt = height.coerceAtLeast(8)
        val wid = (hgt * aspect(b)).toInt().coerceAtLeast(8)
        val bitmap = Bitmap.createBitmap(wid, hgt, Bitmap.Config.ARGB_8888)
        draw(Canvas(bitmap), b, tMs, durationMs, wid.toFloat(), hgt.toFloat())
        return bitmap
    }

    /** The still used for the layer's thumbnail and size: the button in its finished state. */
    fun still(b: AnimatedButton, height: Int = 240): Bitmap =
        render(
            b,
            when (b) {
                AnimatedButton.SUBSCRIBE, AnimatedButton.LIKE -> 3_000
                AnimatedButton.BELL -> 2_500
                else -> 1_000
            },
            10_000, height,
        )
}
