package com.shilapi.xcertplay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.roundToInt

/**
 * The 2 dp progress line under the connecting screen's stage headline.
 *
 * A sweeping segment rather than a percentage: the bring-up has no measurable total, and a bar that never
 * reaches its end reads as "still working" where a spinner would read as a separate object. It is a line
 * because the whole system is lines - 1 dp hairlines and rules instead of shadows and chrome.
 */
class DiPlayProgressBar @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val trackPaint = Paint().apply { color = Color.rgb(0x2A, 0x29, 0x26) }
    private val fillPaint = Paint().apply { color = Color.WHITE }
    private var phase = 0f
    private var animator: ValueAnimator? = null

    /**
     * The sweep and its track follow the screen's day/night palette: a white sweep on the day side's warm
     * off-white was invisible, which is how this looked on a real screen before the colours were wired in.
     */
    fun setBarColors(fill: Int, track: Int) {
        fillPaint.color = fill
        trackPaint.color = track
        invalidate()
    }

    private val thickness: Int
        get() = (2 * resources.displayMetrics.density).roundToInt().coerceAtLeast(2)

    init {
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = SWEEP_MILLIS
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                phase = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), thickness)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0) return
        val height = height.toFloat()
        canvas.drawRect(0f, 0f, width.toFloat(), height, trackPaint)
        val segment = width * SEGMENT_FRACTION
        val left = (width + segment) * phase - segment
        canvas.drawRect(
            maxOf(0f, left),
            0f,
            minOf(width.toFloat(), left + segment),
            height,
            fillPaint,
        )
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }

    private companion object {
        const val SWEEP_MILLIS = 1400L
        const val SEGMENT_FRACTION = 0.28f
    }
}
