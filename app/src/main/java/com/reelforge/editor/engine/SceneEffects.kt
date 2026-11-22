package com.reelforge.editor.engine

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Effects that cover the whole video: snow, Diwali lights, fireworks, confetti and more. They are
 * drawn by the app from a fixed recipe (no downloads, no one else's artwork), so every frame of
 * the preview and the export is the same picture.
 */
enum class SceneEffect(
    override val title: String,
    val blurb: String,
    /** "Festival", "Party", "Love" or "Nature": how the store groups them. */
    val group: String,
    override val defaultMs: Long = 5_000,
) : MovingArt {
    DIWALI("Diwali lights", "Glowing diyas and golden sparks", "Festival"),
    FIREWORKS("Fireworks", "Colourful bursts in the sky", "Festival"),
    HOLI("Holi colours", "Clouds of coloured powder", "Festival"),
    SNOWFALL("Snowfall", "Soft snow drifting down", "Festival"),
    CONFETTI("Confetti", "Party confetti falling", "Party"),
    SPARKLES("Sparkles", "Twinkling stars all over", "Party"),
    BOKEH("Bokeh lights", "Soft blurred lights drifting", "Party"),
    HEARTS("Hearts", "Hearts floating up", "Love"),
    PETALS("Rose petals", "Petals falling and turning", "Love"),
    RAIN("Rain", "Rain falling", "Nature"),
    BUBBLES("Bubbles", "Bubbles rising", "Nature");

    override val fullFrame: Boolean get() = true

    override fun aspect(frameAspect: Float): Float = frameAspect.coerceIn(0.2f, 5f)

    override fun draw(canvas: Canvas, tMs: Long, durationMs: Long, w: Float, h: Float) =
        SceneEffects.draw(canvas, this, tMs, durationMs, w, h)

    companion object {
        fun of(name: String?): SceneEffect? = entries.firstOrNull { it.name == name }
    }
}

object SceneEffects {

    /** A steady "random" number in 0..1 for particle [i], property [k]: the same every frame. */
    internal fun rnd(i: Int, k: Int): Float {
        val v = sin(i * 12.9898 + k * 78.233 + 0.5) * 43758.5453
        return (v - floor(v)).toFloat()
    }

    private fun frac(v: Float) = v - floor(v)

    /** 0..1: fades the whole effect in over the first 0.4 s and out over the last 0.4 s. */
    internal fun fade(tMs: Long, durationMs: Long): Float {
        val inP = (tMs / 400f).coerceIn(0f, 1f)
        val outP = if (durationMs <= 0) 1f else ((durationMs - tMs) / 400f).coerceIn(0f, 1f)
        return min(inP, outP)
    }

    fun draw(c: Canvas, e: SceneEffect, tMs: Long, durationMs: Long, w: Float, h: Float) {
        if (w < 2 || h < 2) return
        val t = tMs.coerceAtLeast(0) / 1000f
        val a = fade(tMs, durationMs)
        if (a <= 0f) return
        val m = min(w, h)
        when (e) {
            SceneEffect.SNOWFALL -> snow(c, t, a, w, h, m)
            SceneEffect.DIWALI -> diwali(c, t, a, w, h, m)
            SceneEffect.FIREWORKS -> fireworks(c, t, a, w, h, m)
            SceneEffect.CONFETTI -> confetti(c, t, a, w, h, m)
            SceneEffect.HEARTS -> hearts(c, t, a, w, h, m)
            SceneEffect.PETALS -> petals(c, t, a, w, h, m)
            SceneEffect.SPARKLES -> sparkles(c, t, a, w, h, m)
            SceneEffect.RAIN -> rain(c, t, a, w, h, m)
            SceneEffect.BUBBLES -> bubbles(c, t, a, w, h, m)
            SceneEffect.BOKEH -> bokeh(c, t, a, w, h, m)
            SceneEffect.HOLI -> holi(c, t, a, w, h, m)
        }
    }

    private fun paint(colour: Int, alpha: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colour
        this.alpha = (alpha.coerceIn(0f, 1f) * 255).toInt()
    }

    private fun withAlpha(colour: Int, alpha: Float): Int =
        (((alpha.coerceIn(0f, 1f) * ((colour ushr 24) and 0xFF)).toInt()) shl 24) or (colour and 0xFFFFFF)

    /** A soft round glow: full colour in the middle fading to nothing at [r]. */
    private fun glow(c: Canvas, x: Float, y: Float, r: Float, colour: Int, alpha: Float) {
        if (r <= 0.5f || alpha <= 0f) return
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                x, y, r, intArrayOf(withAlpha(colour, alpha), withAlpha(colour, alpha * 0.35f), 0),
                floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP,
            )
        }
        c.drawCircle(x, y, r, p)
    }

    /** Where a falling (or rising, with negative [speed]) particle is, wrapping round the screen. */
    private fun fall(i: Int, t: Float, speed: Float, margin: Float): Float =
        frac(rnd(i, 2) + t * speed) * (1f + 2 * margin) - margin

    // ---------------------------------------------------------------- the effects

    private fun snow(c: Canvas, t: Float, a: Float, w: Float, h: Float, m: Float) {
        val p = paint(0xFFFFFFFF.toInt(), a)
        for (i in 0 until 110) {
            val depth = 0.35f + 0.65f * rnd(i, 3)              // far flakes are small and slow
            val y = fall(i, t, 0.07f + 0.12f * depth, 0.04f) * h
            val x = (rnd(i, 1) + sin(t * (0.6f + rnd(i, 4)) + i) * 0.025f) * w
            p.alpha = (a * (0.45f + 0.55f * depth) * 255).toInt()
            c.drawCircle(x, y, m * (0.003f + 0.009f * depth), p)
        }
    }

    private fun diwali(c: Canvas, t: Float, a: Float, w: Float, h: Float, m: Float) {
        // A warm glow rising from the bottom.
        val warm = Paint().apply {
            shader = LinearGradient(
                0f, h, 0f, h * 0.55f, withAlpha(0xFFFF8F00.toInt(), 0.35f * a), 0, Shader.TileMode.CLAMP,
            )
        }
        c.drawRect(0f, h * 0.55f, w, h, warm)

        // A string of twinkling lights across the top.
        val bulbs = 14
        for (i in 0 until bulbs) {
            val x = w * (i + 0.5f) / bulbs
            val y = h * 0.05f + sin(i * PI.toFloat() / (bulbs - 1)) * h * 0.035f
            val on = 0.55f + 0.45f * sin(t * 4f + i * 1.7f)
            val colour = if (i % 3 == 0) 0xFFFFD54F.toInt() else if (i % 3 == 1) 0xFFFF7043.toInt() else 0xFFFFF176.toInt()
            glow(c, x, y, m * 0.04f, colour, a * on)
            c.drawCircle(x, y, m * 0.008f, paint(0xFFFFFDE7.toInt(), a * on))
        }

        // Golden sparks drifting up from the lamps.
        for (i in 0 until 60) {
            val life = frac(rnd(i, 2) + t * (0.18f + 0.15f * rnd(i, 5)))
            val x = (rnd(i, 1) + sin(t * 1.3f + i) * 0.02f) * w
            val y = h * (0.92f - life * 0.75f)
            val twinkle = 0.5f + 0.5f * sin(t * 9f + i * 2.3f)
            val alpha = a * (1f - life) * twinkle
            c.drawCircle(x, y, m * (0.003f + 0.004f * rnd(i, 3)), paint(0xFFFFE082.toInt(), alpha))
        }

        // Diyas (clay lamps) along the bottom, each with a flickering flame.
        val lamps = if (w > h) 7 else 5
        val bowl = Paint(Paint.ANTI_ALIAS_FLAG)
        val rim = paint(0xFFFFB300.toInt(), a)
        for (i in 0 until lamps) {
            val cx = w * (i + 0.5f) / lamps
            val base = h * 0.965f
            val bw = min(w / lamps * 0.62f, m * 0.16f)
            val bh = bw * 0.42f
            bowl.shader = LinearGradient(
                0f, base - bh, 0f, base, withAlpha(0xFFD84315.toInt(), a), withAlpha(0xFF6D2A0C.toInt(), a),
                Shader.TileMode.CLAMP,
            )
            c.drawArc(RectF(cx - bw / 2, base - bh * 2, cx + bw / 2, base), 0f, 180f, true, bowl)
            c.drawRect(cx - bw / 2, base - bh - bh * 0.08f, cx + bw / 2, base - bh + bh * 0.08f, rim)

            val flicker = 1f + 0.12f * sin(t * 13f + i * 2.1f) + 0.06f * sin(t * 29f + i)
            val fh = bw * 0.55f * flicker
            val fx = cx + sin(t * 7f + i) * bw * 0.03f
            val top = base - bh - fh
            glow(c, fx, top + fh * 0.6f, fh * 1.4f, 0xFFFFA000.toInt(), a * 0.8f)
            val flame = Path().apply {
                moveTo(fx, top)
                cubicTo(fx + fh * 0.38f, top + fh * 0.45f, fx + fh * 0.3f, base - bh, fx, base - bh)
                cubicTo(fx - fh * 0.3f, base - bh, fx - fh * 0.38f, top + fh * 0.45f, fx, top)
                close()
            }
            c.drawPath(flame, paint(0xFFFFB300.toInt(), a))
            c.save()
            c.scale(0.55f, 0.6f, fx, base - bh)
            c.drawPath(flame, paint(0xFFFFF59D.toInt(), a))
            c.restore()
        }
    }

    private val partyColours = intArrayOf(
        0xFFFF5252.toInt(), 0xFFFFD740.toInt(), 0xFF69F0AE.toInt(), 0xFF40C4FF.toInt(),
        0xFFE040FB.toInt(), 0xFFFF6E40.toInt(), 0xFFFFFFFF.toInt(),
    )

    private fun fireworks(c: Canvas, t: Float, a: Float, w: Float, h: Float, m: Float) {
        val period = 0.75f
        val life = 1.8f
        val now = floor(t / period).toInt()
        for (k in max(0, now - 3)..now) {
            val age = t - k * period
            if (age < 0f || age > life) continue
            val cx = w * (0.15f + 0.7f * rnd(k, 11))
            val cy = h * (0.12f + 0.4f * rnd(k, 12))
            val colour = partyColours[(rnd(k, 13) * partyColours.size).toInt() % partyColours.size]
            val p = life.let { age / it }
            val fadeOut = (1f - p).pow(1.5f)
            // A flash where it bursts.
            if (age < 0.25f) glow(c, cx, cy, m * 0.12f * (age / 0.25f + 0.3f), colour, a * (1f - age / 0.25f))
            val sparks = 40
            val reach = m * (0.16f + 0.1f * rnd(k, 14))
            val spread = 1f - (1f - min(1f, age / 0.9f)).pow(3)
            for (s in 0 until sparks) {
                val angle = (s + rnd(k * 50 + s, 15) * 0.6f) / sparks * 2f * PI.toFloat()
                val dist = reach * spread * (0.8f + 0.2f * rnd(k * 50 + s, 16))
                val x = cx + cos(angle) * dist
                val y = cy + sin(angle) * dist + m * 0.06f * p * p   // falls a little as it fades
                val tail = dist * 0.18f
                val sp = paint(colour, a * fadeOut).apply { strokeWidth = m * 0.005f; strokeCap = Paint.Cap.ROUND }
                c.drawLine(x - cos(angle) * tail, y - sin(angle) * tail, x, y, sp)
                if ((s + k) % 3 == 0) c.drawCircle(x, y, m * 0.004f, paint(0xFFFFFFFF.toInt(), a * fadeOut))
            }
        }
    }

    private fun confetti(c: Canvas, t: Float, a: Float, w: Float, h: Float, m: Float) {
        for (i in 0 until 90) {
            val y = fall(i, t, 0.16f + 0.14f * rnd(i, 3), 0.05f) * h
            val x = (rnd(i, 1) + sin(t * 1.7f + i) * 0.03f) * w
            val spin = t * (3f + 4f * rnd(i, 4)) + i
            val size = m * (0.012f + 0.01f * rnd(i, 5))
            val colour = partyColours[i % (partyColours.size - 1)]
            c.save()
            c.rotate((spin * 57f) % 360f, x, y)
            c.scale(1f, 0.25f + 0.75f * abs(cos(spin)), x, y)
            c.drawRect(x - size / 2, y - size * 0.3f, x + size / 2, y + size * 0.3f, paint(colour, a))
            c.restore()
        }
    }

    private fun heartPath(x: Float, y: Float, s: Float): Path = Path().apply {
        moveTo(x, y + s * 0.35f)
        cubicTo(x - s * 0.55f, y - s * 0.05f, x - s * 0.35f, y - s * 0.55f, x, y - s * 0.25f)
        cubicTo(x + s * 0.35f, y - s * 0.55f, x + s * 0.55f, y - s * 0.05f, x, y + s * 0.35f)
        close()
    }

    private fun hearts(c: Canvas, t: Float, a: Float, w: Float, h: Float, m: Float) {
        val colours = intArrayOf(0xFFFF1744.toInt(), 0xFFFF4081.toInt(), 0xFFFF80AB.toInt(), 0xFFF50057.toInt())
        for (i in 0 until 26) {
            val life = fall(i, t, 0.09f + 0.07f * rnd(i, 3), 0.06f)
            val y = h * (1f - life)
            val x = (rnd(i, 1) + sin(t * 1.4f + i * 1.3f) * 0.04f) * w
            val s = m * (0.03f + 0.04f * rnd(i, 4))
            val beat = 1f + 0.08f * sin(t * 6f + i)
            val alpha = a * (0.55f + 0.45f * rnd(i, 5)) * min(1f, (1f - life) * 4f + 0.2f)
            c.drawPath(heartPath(x, y, s * beat), paint(colours[i % colours.size], alpha))
        }
    }

    private fun petals(c: Canvas, t: Float, a: Float, w: Float, h: Float, m: Float) {
        val colours = intArrayOf(0xFFE91E63.toInt(), 0xFFF06292.toInt(), 0xFFC2185B.toInt(), 0xFFFF8A80.toInt())
        for (i in 0 until 40) {
            val y = fall(i, t, 0.08f + 0.08f * rnd(i, 3), 0.05f) * h
            val x = (rnd(i, 1) + sin(t * (0.8f + rnd(i, 6)) + i) * 0.06f) * w
            val spin = t * (1.5f + 2f * rnd(i, 4)) + i
            val s = m * (0.018f + 0.014f * rnd(i, 5))
            c.save()
            c.rotate((spin * 50f) % 360f, x, y)
            c.scale(1f, 0.35f + 0.65f * abs(sin(spin)), x, y)
            c.drawOval(RectF(x - s, y - s * 0.6f, x + s, y + s * 0.6f), paint(colours[i % colours.size], a * 0.9f))
            c.restore()
        }
    }

    private fun star(c: Canvas, x: Float, y: Float, r: Float, p: Paint) {
        val path = Path().apply {
            moveTo(x, y - r)
            quadTo(x, y, x + r, y)
            quadTo(x, y, x, y + r)
            quadTo(x, y, x - r, y)
            quadTo(x, y, x, y - r)
            close()
        }
        c.drawPath(path, p)
    }

    private fun sparkles(c: Canvas, t: Float, a: Float, w: Float, h: Float, m: Float) {
        for (i in 0 until 45) {
            val twinkle = sin(t * (2f + 3f * rnd(i, 4)) + i * 2.7f).coerceAtLeast(0f).pow(3)
            if (twinkle < 0.02f) continue
            val x = rnd(i, 1) * w
            val y = rnd(i, 2) * h
            val r = m * (0.015f + 0.03f * rnd(i, 3)) * (0.6f + 0.4f * twinkle)
            val colour = if (i % 4 == 0) 0xFFFFE082.toInt() else 0xFFFFFFFF.toInt()
            glow(c, x, y, r * 0.9f, colour, a * twinkle * 0.6f)
            star(c, x, y, r, paint(colour, a * twinkle))
        }
    }

    private fun rain(c: Canvas, t: Float, a: Float, w: Float, h: Float, m: Float) {
        c.drawRect(0f, 0f, w, h, paint(0xFF0D1B2A.toInt(), 0.18f * a))
        val p = paint(0xFFCFE8FF.toInt(), 0.55f * a).apply { strokeWidth = max(1f, m * 0.0025f); strokeCap = Paint.Cap.ROUND }
        val slant = 0.12f
        for (i in 0 until 140) {
            val depth = 0.4f + 0.6f * rnd(i, 3)
            val y = fall(i, t, 0.9f + 0.9f * depth, 0.1f) * h
            val x = frac(rnd(i, 1) - y / h * slant) * w
            val len = h * (0.03f + 0.04f * depth)
            p.alpha = (0.55f * a * depth * 255).toInt()
            c.drawLine(x, y, x - len * slant, y + len, p)
        }
    }

    private fun bubbles(c: Canvas, t: Float, a: Float, w: Float, h: Float, m: Float) {
        val ring = paint(0xFFE1F5FE.toInt(), 0.7f * a).apply { style = Paint.Style.STROKE; strokeWidth = max(1f, m * 0.003f) }
        val shine = paint(0xFFFFFFFF.toInt(), 0.8f * a)
        for (i in 0 until 30) {
            val life = fall(i, t, 0.06f + 0.07f * rnd(i, 3), 0.08f)
            val y = h * (1f - life)
            val x = (rnd(i, 1) + sin(t * 1.1f + i * 0.9f) * 0.035f) * w
            val r = m * (0.015f + 0.035f * rnd(i, 4))
            c.drawCircle(x, y, r, paint(0xFF81D4FA.toInt(), 0.12f * a))
            c.drawCircle(x, y, r, ring)
            c.drawCircle(x - r * 0.35f, y - r * 0.35f, r * 0.18f, shine)
        }
    }

    private fun bokeh(c: Canvas, t: Float, a: Float, w: Float, h: Float, m: Float) {
        val colours = intArrayOf(0xFFFFD180.toInt(), 0xFFFFAB91.toInt(), 0xFFFFF59D.toInt(), 0xFFF8BBD0.toInt(), 0xFFB3E5FC.toInt())
        for (i in 0 until 24) {
            val x = frac(rnd(i, 1) + t * 0.015f * (rnd(i, 3) - 0.5f)) * w
            val y = frac(rnd(i, 2) - t * 0.02f * (0.3f + rnd(i, 4))) * h
            val r = m * (0.05f + 0.09f * rnd(i, 5))
            val pulse = 0.7f + 0.3f * sin(t * (0.8f + rnd(i, 6)) + i)
            glow(c, x, y, r, colours[i % colours.size], a * 0.55f * pulse)
        }
    }

    private fun holi(c: Canvas, t: Float, a: Float, w: Float, h: Float, m: Float) {
        val colours = intArrayOf(
            0xFFFF1493.toInt(), 0xFFFFEA00.toInt(), 0xFF00E676.toInt(), 0xFF00B0FF.toInt(),
            0xFFFF6D00.toInt(), 0xFFD500F9.toInt(),
        )
        val period = 0.6f
        val life = 2.2f
        val now = floor(t / period).toInt()
        for (k in max(0, now - 4)..now) {
            val age = t - k * period
            if (age < 0f || age > life) continue
            val p = age / life
            val cx = w * (0.1f + 0.8f * rnd(k, 21))
            val cy = h * (0.15f + 0.7f * rnd(k, 22))
            val colour = colours[(rnd(k, 23) * colours.size).toInt() % colours.size]
            val grow = 1f - (1f - min(1f, age / 0.8f)).pow(3)
            val alpha = a * (1f - p) * 0.75f
            // A cloud: a few overlapping soft puffs.
            for (j in 0 until 5) {
                val ang = rnd(k * 10 + j, 24) * 2f * PI.toFloat()
                val off = m * 0.06f * grow * rnd(k * 10 + j, 25)
                glow(c, cx + cos(ang) * off, cy + sin(ang) * off, m * (0.1f + 0.08f * rnd(k * 10 + j, 26)) * grow + 1f, colour, alpha)
            }
            // Specks of powder flying out.
            for (s in 0 until 18) {
                val ang = rnd(k * 40 + s, 27) * 2f * PI.toFloat()
                val dist = m * 0.25f * grow * (0.4f + 0.6f * rnd(k * 40 + s, 28))
                c.drawCircle(cx + cos(ang) * dist, cy + sin(ang) * dist + m * 0.05f * p * p, m * 0.004f, paint(colour, alpha))
            }
        }
    }

    /** Roughly how far apart two pictures are (for tests): 0 = identical. */
    internal fun distance(a: IntArray, b: IntArray): Double {
        var sum = 0.0
        for (i in a.indices) {
            val x = a[i]; val y = b[i]
            val d = abs(((x ushr 24) and 0xFF) - ((y ushr 24) and 0xFF))
            sum += d * d
        }
        return sqrt(sum / a.size.coerceAtLeast(1))
    }
}
