package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View

/**
 * The drawing on the right of the waiting screen: an iPhone, its cable and the head unit's USB port.
 *
 * Drawn rather than shipped as a photograph, because Lynk & Co's identity is type, blocks and lines - the
 * wordmark is pure black, the digital surfaces are warm near-black - and a photograph would both fight the
 * token set and carry someone else's rights (docs/_digest/60). One stroke weight, flat fills, no shading.
 *
 * Everything is laid out in a fixed 430 x 400 space and scaled to whatever the view is given, so the same
 * composition holds on the car's 1280x650 panel and on a tablet in review.
 */
class DiPlayConnectVisual @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val hairline = Color.rgb(0x2A, 0x29, 0x26)
    private val gridLine = Color.rgb(0x1A, 0x19, 0x17)
    private val white = Color.WHITE
    private val raised = Color.rgb(0x1A, 0x19, 0x17)
    private val panel = Color.rgb(0x0C, 0x0C, 0x0B)
    private val screen = Color.rgb(0x10, 0x10, 0x10)
    private val muted = Color.rgb(0x3A, 0x39, 0x36)

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        color = white
    }
    private val caption = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        color = Color.rgb(0x5C, 0x5B, 0x58)
    }
    private val cable = Path()
    private val rect = RectF()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val scale = minOf(width / DESIGN_WIDTH, height / DESIGN_HEIGHT)
        if (scale <= 0f) return
        canvas.save()
        // Anchored bottom-left, the way the composition was drawn.
        canvas.translate((width - DESIGN_WIDTH * scale) / 2f, height - DESIGN_HEIGHT * scale)
        canvas.scale(scale, scale)

        drawGrid(canvas)
        drawHeadline(canvas)
        drawLink(canvas)
        canvas.restore()
    }

    private fun drawGrid(canvas: Canvas) {
        stroke.color = gridLine
        stroke.strokeWidth = 1f
        var x = 0f
        while (x <= DESIGN_WIDTH) {
            canvas.drawLine(x, 0f, x, DESIGN_HEIGHT, stroke)
            x += GRID_STEP
        }
        var y = 0f
        while (y <= DESIGN_HEIGHT) {
            canvas.drawLine(0f, y, DESIGN_WIDTH, y, stroke)
            y += GRID_STEP
        }
    }

    private fun drawHeadline(canvas: Canvas) {
        text.textSize = 104f
        canvas.drawText("01", 36f, 118f, text)
        caption.textSize = 13f
        canvas.drawText("WIRED · USB", 40f, 148f, caption)
        fill.color = white
        canvas.drawRect(DESIGN_WIDTH - 58f, 44f, DESIGN_WIDTH - 44f, 58f, fill)
    }

    private fun drawLink(canvas: Canvas) {
        // The bus the phone lands on, with its port ticks.
        stroke.color = hairline
        stroke.strokeWidth = 1f
        canvas.drawLine(0f, 300f, DESIGN_WIDTH, 300f, stroke)
        for (tick in floatArrayOf(96f, 152f, 208f)) {
            canvas.drawLine(tick, 288f, tick, 312f, stroke)
        }

        // Head-unit side connector: outline plus the white tongue.
        rect.set(150f, 252f, 266f, 300f)
        fill.color = raised
        canvas.drawRect(rect, fill)
        stroke.color = white
        stroke.strokeWidth = 1.5f
        canvas.drawRect(rect, stroke)
        rect.set(176f, 268f, 240f, 284f)
        fill.color = white
        canvas.drawRect(rect, fill)

        // Signal ticks leaving the connector.
        stroke.color = hairline
        stroke.strokeWidth = 1f
        canvas.drawLine(232f, 276f, 214f, 276f, stroke)
        canvas.drawLine(232f, 288f, 206f, 288f, stroke)
        canvas.drawLine(232f, 300f, 198f, 300f, stroke)

        // Cable: the one organic line in the composition.
        cable.reset()
        cable.moveTo(208f, 252f)
        cable.cubicTo(208f, 196f, 292f, 214f, 292f, 156f)
        stroke.color = white
        stroke.strokeWidth = 1.5f
        canvas.drawPath(cable, stroke)

        // The iPhone.
        rect.set(252f, 36f, 372f, 248f)
        fill.color = panel
        canvas.drawRect(rect, fill)
        stroke.color = white
        stroke.strokeWidth = 1.5f
        canvas.drawRect(rect, stroke)
        rect.set(266f, 56f, 358f, 206f)
        fill.color = screen
        canvas.drawRect(rect, fill)
        stroke.color = hairline
        stroke.strokeWidth = 1f
        canvas.drawRect(rect, stroke)
        stroke.strokeWidth = 2f
        canvas.drawLine(296f, 44f, 328f, 44f, stroke)

        // What the screen will show once DiPlay takes over: the mark, two lines, an inverted action.
        fill.color = white
        canvas.drawRect(284f, 88f, 296f, 100f, fill)
        stroke.color = muted
        stroke.strokeWidth = 2f
        canvas.drawLine(284f, 122f, 342f, 122f, stroke)
        canvas.drawLine(284f, 136f, 326f, 136f, stroke)
        fill.color = white
        canvas.drawRect(284f, 164f, 340f, 184f, fill)
    }

    private companion object {
        const val DESIGN_WIDTH = 430f
        const val DESIGN_HEIGHT = 400f
        const val GRID_STEP = 38f
    }
}
