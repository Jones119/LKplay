// SPDX-License-Identifier: AGPL-3.0-only
package com.shilapi.xcertplay.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

/**
 * The two hand-drawn surfaces of the DiPlay page.
 *
 * Both are plain `View`s on purpose: the app has no vector-drawable pipeline on API 19 (`VectorDrawable`
 * is API 21), and shipping a bitmap per icon would mean a density bucket per icon. A dozen `drawArc` /
 * `drawCircle` calls cost nothing on the i.MX6Q as long as they are not redrawn every frame, which is why
 * both views only invalidate when a colour or a value actually changes.
 */

enum class Glyph { PHONE, WIFI, USB, HOME, SLIDERS, PULSE, CLOSE }

/** One 24-grid pictogram drawn with strokes, sized by the view and coloured by [color]. */
class GlyphView @JvmOverloads constructor(
    context: Context,
    private val glyph: Glyph,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var color: Int = Color.WHITE
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val rect = RectF()
    private val pulse = android.graphics.Path()

    override fun onDraw(canvas: Canvas) {
        val s = min(width, height).toFloat()
        if (s <= 0f) return
        val cx = width / 2f
        val cy = height / 2f
        stroke.color = color
        fill.color = color
        stroke.strokeWidth = s * 0.075f
        when (glyph) {
            Glyph.PHONE -> {
                val w = s * 0.46f
                val h = s * 0.80f
                rect.set(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
                canvas.drawRoundRect(rect, s * 0.10f, s * 0.10f, stroke)
                canvas.drawLine(cx - w * 0.13f, cy - h * 0.34f, cx + w * 0.13f, cy - h * 0.34f, stroke)
            }

            Glyph.WIFI -> {
                val r = s * 0.44f
                for (i in 0 until 3) {
                    val f = WIFI_SCALE[i]
                    rect.set(cx - r * f, cy + s * 0.06f - r * f, cx + r * f, cy + s * 0.06f + r * f)
                    canvas.drawArc(rect, 215f, 110f, false, stroke)
                }
                canvas.drawCircle(cx, cy + s * 0.29f, s * 0.045f, fill)
            }

            Glyph.USB -> {
                canvas.drawLine(cx, cy + s * 0.28f, cx, cy - s * 0.28f, stroke)
                canvas.drawLine(cx, cy - s * 0.46f, cx - s * 0.13f, cy - s * 0.24f, stroke)
                canvas.drawLine(cx, cy - s * 0.46f, cx + s * 0.13f, cy - s * 0.24f, stroke)
                canvas.drawLine(cx, cy - s * 0.06f, cx + s * 0.27f, cy - s * 0.24f, stroke)
                rect.set(cx + s * 0.23f, cy - s * 0.33f, cx + s * 0.37f, cy - s * 0.19f)
                canvas.drawRect(rect, fill)
                canvas.drawLine(cx, cy + s * 0.12f, cx - s * 0.26f, cy + s * 0.30f, stroke)
                canvas.drawCircle(cx - s * 0.30f, cy + s * 0.34f, s * 0.10f, stroke)
                canvas.drawCircle(cx, cy + s * 0.33f, s * 0.10f, fill)
            }

            Glyph.HOME -> {
                val w = s * 0.40f
                val h = s * 0.30f
                canvas.drawLine(cx - w, cy + s * 0.02f, cx, cy - h - s * 0.04f, stroke)
                canvas.drawLine(cx, cy - h - s * 0.04f, cx + w, cy + s * 0.02f, stroke)
                rect.set(cx - w * 0.78f, cy + s * 0.02f, cx + w * 0.78f, cy + s * 0.38f)
                canvas.drawRoundRect(rect, s * 0.04f, s * 0.04f, stroke)
            }

            Glyph.SLIDERS -> {
                for (i in 0 until 3) {
                    val y = cy - s * 0.26f + i * s * 0.26f
                    canvas.drawLine(cx - s * 0.34f, y, cx + s * 0.34f, y, stroke)
                    canvas.drawCircle(cx - s * 0.34f + KNOB[i] * s * 0.68f, y, s * 0.075f, fill)
                }
            }

            Glyph.PULSE -> {
                pulse.reset()
                pulse.moveTo(cx - s * 0.40f, cy)
                pulse.lineTo(cx - s * 0.18f, cy)
                pulse.lineTo(cx - s * 0.08f, cy - s * 0.30f)
                pulse.lineTo(cx + s * 0.05f, cy + s * 0.30f)
                pulse.lineTo(cx + s * 0.15f, cy)
                pulse.lineTo(cx + s * 0.40f, cy)
                canvas.drawPath(pulse, stroke)
            }

            Glyph.CLOSE -> {
                val d = s * 0.26f
                canvas.drawLine(cx - d, cy - d, cx + d, cy + d, stroke)
                canvas.drawLine(cx + d, cy - d, cx - d, cy + d, stroke)
            }
        }
    }

    private companion object {
        /** Ring radii for the Wi-Fi arcs and knob positions for the sliders, kept off the draw path. */
        val WIFI_SCALE = floatArrayOf(1f, 0.66f, 0.33f)
        val KNOB = floatArrayOf(0.32f, 0.70f, 0.48f)
    }
}

/**
 * The connection gauge: five arc segments, one per link step.
 *
 * Static apart from [filled], so `onDraw` runs only when the value changes. The centre content (icon,
 * state, phone name) is ordinary text views stacked on top by the caller.
 */
class GaugeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var filled: Int = 0
        set(value) {
            val clamped = value.coerceIn(0, SEGMENTS)
            if (field != clamped) {
                field = clamped
                invalidate()
            }
        }

    var accent: Int = Color.WHITE
        set(value) { if (field != value) { field = value; invalidate() } }
    var ring: Int = Color.DKGRAY
        set(value) { if (field != value) { field = value; invalidate() } }
    var bezel: Int = Color.DKGRAY
        set(value) { if (field != value) { field = value; invalidate() } }

    /**
     * A highlight arc that circles the ring while a connection is being brought up.
     *
     * Only runs while connecting, and at ~30 fps: the home page has to stay cheap on this CPU, so the
     * ticker is stopped the moment [setSpinning] turns off or the view leaves the window.
     */
    private var spinning = false
    private var spinAngle = 0f
    private val spinTick = object : Runnable {
        override fun run() {
            if (!spinning) return
            spinAngle = (spinAngle + SPIN_STEP) % 360f
            invalidate()
            postDelayed(this, SPIN_PERIOD_MS)
        }
    }

    fun setSpinning(on: Boolean) {
        if (spinning == on) return
        spinning = on
        removeCallbacks(spinTick)
        if (on) postDelayed(spinTick, 0L)
        else {
            spinAngle = 0f
            invalidate()
        }
    }

    override fun onDetachedFromWindow() {
        spinning = false
        removeCallbacks(spinTick)
        super.onDetachedFromWindow()
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.BUTT
    }
    private val rect = RectF()

    override fun onDraw(canvas: Canvas) {
        val density = resources.displayMetrics.density
        val cx = width / 2f
        val cy = height / 2f
        val r = min(width, height) / 2f - 16f * density
        if (r <= 0f) return

        paint.color = bezel
        paint.strokeWidth = density
        canvas.drawCircle(cx, cy, r + 14f * density, paint)

        rect.set(cx - r, cy - r, cx + r, cy + r)
        for (i in 0 until SEGMENTS) {
            val live = i < filled
            // The idle ring is the card border at 55%: visible on white without competing with the live arcs.
            paint.color = if (live) accent else ring
            paint.alpha = if (live) 255 else 140
            paint.strokeWidth = (if (live) 6f else 4f) * density
            canvas.drawArc(rect, START + i * (SEG + GAP), SEG, false, paint)
        }
        if (spinning) {
            // One accent arc chasing the ring. Drawn over the segments so the direction is unmistakable.
            paint.color = accent
            paint.alpha = 255
            paint.strokeWidth = 6f * density
            canvas.drawArc(rect, START + spinAngle, SPIN_SWEEP, false, paint)
        }
        paint.alpha = 255
    }

    private companion object {
        const val SEGMENTS = 5

        /** Degrees, laid out like a gauge: the opening sits at the bottom. */
        const val START = 132f
        const val TOTAL = 276f
        const val GAP = 9f
        const val SEG = (TOTAL - GAP * 4) / SEGMENTS

        /** ~270°/s while connecting: fast enough to read as motion, slow enough to stay cheap. */
        const val SPIN_PERIOD_MS = 33L
        const val SPIN_STEP = 9f
        const val SPIN_SWEEP = 70f
    }
}
