package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.core.content.ContextCompat

/**
 * Renders a maneuver arrow as a Bitmap for the instrument cluster.
 *
 * The cluster takes the turn arrow as an image — the head unit's own HiCar bridge does the same
 * (`updateDriveDirectionImage`, docs/_digest/37) — and the vendor's integer arrow ids are an unverified
 * set, so an image built from DiPlay's own maneuver icons is the reliable route. The mapping itself
 * stays in one place: [NavigationWidgetUpdater.arrow].
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
