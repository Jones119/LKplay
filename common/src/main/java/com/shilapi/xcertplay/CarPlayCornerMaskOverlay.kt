package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import android.view.ViewGroup
import kotlin.math.ceil
import kotlin.math.min

/**
 * The CarPlay screen's four corners painted black, so a full-bleed picture ends where the car's
 * physical bezel ends (docs/_digest/42 §C12, EasyPlay v71 `LegacyCarPlayCornerMask`).
 *
 * The head unit's panel is rectangular while the visible screen opening has rounded corners, so
 * CarPlay's picture is clipped by the car itself in those four spots and the cut looks like a
 * rendering fault. The reference does not blur or shade the screen: it lays four small children over
 * the corners, each filling an `edge x edge` square, and each draws one anti-aliased black rounded
 * triangle. Only the corners are touched; in particular there is no `clipToOutline`,
 * `ViewOutlineProvider` or `Canvas.clipPath`, all of which are API 21+ or would force the whole
 * screen through a layer on this API 19 device.
 *
 * The mask is attached between the CarPlay picture and the touch layer, and every view in it is
 * non-clickable and non-focusable, so it can never take the settings gesture or any other touch.
 * That guarantee is the reason the flag only toggles [View.getVisibility] and never the view tree.
 */
class CarPlayCornerMaskOverlay(context: Context) : ViewGroup(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    private val corners: List<CornerView> = List(CORNER_COUNT) { position -> CornerView(context, position, paint) }

    init {
        setWillNotDraw(true)
        isClickable = false
        isFocusable = false
        descendantFocusability = FOCUS_BLOCK_DESCENDANTS
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        for (corner in corners) addView(corner)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = getDefaultSize(suggestedMinimumWidth, widthMeasureSpec)
        val height = getDefaultSize(suggestedMinimumHeight, heightMeasureSpec)
        setMeasuredDimension(width, height)
        val radius = CarPlayCornerGeometry.radiusFor(width, height)
        val edgeSpec = MeasureSpec.makeMeasureSpec(CarPlayCornerGeometry.maskEdge(width, height), MeasureSpec.EXACTLY)
        for (corner in corners) {
            corner.radius = radius
            corner.measure(edgeSpec, edgeSpec)
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val width = right - left
        val height = bottom - top
        val rects = CarPlayCornerGeometry.cornerRects(width, height, CarPlayCornerGeometry.maskEdge(width, height))
        for (index in corners.indices) {
            val rect = rects[index]
            corners[index].layout(rect.left, rect.top, rect.right, rect.bottom)
        }
    }

    /** One corner square: a black anti-aliased quarter-round wedge, mirrored into its corner. */
    private class CornerView(context: Context, private val position: Int, private val paint: Paint) : View(context) {
        private val path = Path()
        private val arcBounds = RectF()
        private val transform = Matrix()

        var radius = 0f
            set(value) {
                if (field == value) return
                field = value
                rebuild()
                invalidate()
            }

        init {
            isClickable = false
            isFocusable = false
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            rebuild()
        }

        /**
         * The wedge is built once as the top-left corner and then mirrored, which keeps all four
         * corners byte-identical instead of four hand-written arc constructions.
         */
        private fun rebuild() {
            path.reset()
            val r = radius
            if (r <= 0f) return
            path.moveTo(0f, 0f)
            path.lineTo(r, 0f)
            arcBounds.set(0f, 0f, r * 2f, r * 2f)
            // -90 is (r, 0) on these bounds, the same point, so the curve starts where the line ends.
            path.arcTo(arcBounds, -90f, -90f, false)
            path.close()
            val mirrorX = position == POSITION_TOP_END || position == POSITION_BOTTOM_END
            val mirrorY = position == POSITION_BOTTOM_START || position == POSITION_BOTTOM_END
            transform.setScale(if (mirrorX) -1f else 1f, if (mirrorY) -1f else 1f)
            transform.postTranslate(if (mirrorX) width.toFloat() else 0f, if (mirrorY) height.toFloat() else 0f)
            path.transform(transform)
        }

        override fun onDraw(canvas: Canvas) {
            canvas.drawPath(path, paint)
        }
    }

    private companion object {
        const val CORNER_COUNT = 4
        const val POSITION_TOP_START = 0
        const val POSITION_TOP_END = 1
        const val POSITION_BOTTOM_END = 2
        const val POSITION_BOTTOM_START = 3
    }
}

/**
 * The mask geometry, kept out of [CarPlayCornerMaskOverlay] and free of any [View] so odd panel
 * sizes can be tested on the JVM instead of on the car.
 */
object CarPlayCornerGeometry {
    /**
     * EasyPlay v71's empirical circle radius, `min(w, h) / 15` on the 1920x1080 panel. It is one
     * named number on purpose: our car's bezel may want a different one, and nothing else in this
     * file knows the value.
     */
    const val RADIUS_DIVISOR = 15

    /** The radius EasyPlay v71 uses for a panel of this size. */
    fun radius(width: Int, height: Int): Float = min(width, height).coerceAtLeast(0) / RADIUS_DIVISOR.toFloat()

    /**
     * The radius the view actually uses. It is capped relative to the shorter side so the arc that
     * is drawn inside each square can never reach the far edge of that square, however the divisor is
     * retuned; [maskEdge] caps the square itself on top of this.
     */
    fun radiusFor(width: Int, height: Int): Float {
        if (width <= 0 || height <= 0) return 0f
        return min(radius(width, height), min(width, height) / 4f)
    }

    /**
     * The side of each corner square. The reference measures it as `ceil(radius)`, so the arc is
     * never cropped, and [RADIUS_DIVISOR] keeps that well under half the screen on its own. The
     * clamp is the belt-and-braces guard: it holds even if someone retunes the radius, so the mask
     * can never grow past the middle of the screen and swallow the picture.
     */
    fun maskEdge(width: Int, height: Int): Int {
        val requested = ceil(radiusFor(width, height)).toInt().coerceAtLeast(0)
        return requested.coerceIn(0, min(width, height).coerceAtLeast(0) / 2)
    }

    /**
     * The four corner squares in the reference's order: top-left, top-right, bottom-right,
     * bottom-left. A zero-size panel or an edge of zero yields four empty rects rather than four
     * overlapping ones, so a degenerate measure pass draws nothing.
     */
    fun cornerRects(width: Int, height: Int, edge: Int): List<CornerRect> {
        val size = min(edge.coerceAtLeast(0), min(width, height).coerceAtLeast(0) / 2)
        if (size == 0) return List(4) { CornerRect(0, 0, 0, 0) }
        return listOf(
            CornerRect(0, 0, size, size),
            CornerRect(width - size, 0, width, size),
            CornerRect(width - size, height - size, width, height),
            CornerRect(0, height - size, size, height),
        )
    }
}

/** An immutable corner square, so the geometry can be asserted without a device. */
data class CornerRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}
