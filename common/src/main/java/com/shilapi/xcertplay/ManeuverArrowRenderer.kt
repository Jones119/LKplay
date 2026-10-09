package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.core.content.ContextCompat

/**
 * Draws DiPlay's own maneuver icon as a Bitmap: desktop-widget-side rendering, not cluster code.
 *
 * It is **no longer on the instrument-cluster path and must not be put back on it**. The cluster does
 * not take an image of an arrow: the vendor's `updateTurnByTurnArrow(Bitmap)` is an empty method, and
 * the image route the head unit's own HiCar bridge uses (`updateDriveDirectionImage`) ends in its own
 * HUD, which this car does not have. The cluster is sent the vendor's integer `ARROW_ICON_NAVI_*` id
 * instead, mapped in `com.shilapi.xcertplay.media.clusterArrowId` (docs/_digest/46 §2/§3).
 *
 * The shared mapping behind both sides stays in one place, [NavigationWidgetUpdater.arrow], which the
 * home-screen widget draws as a drawable directly.
 */
object ManeuverArrowRenderer {
    private const val SIZE_PX = 128

    fun render(context: Context, type: Int, drivingSide: Int): Bitmap? = runCatching {
        val drawable = ContextCompat.getDrawable(context, NavigationWidgetUpdater.arrow(type, drivingSide))
            ?: return null
        val bitmap = Bitmap.createBitmap(SIZE_PX, SIZE_PX, Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, SIZE_PX, SIZE_PX)
        drawable.draw(Canvas(bitmap))
        bitmap
    }.getOrNull()
}
