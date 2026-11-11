package com.shilapi.xcertplay

/**
 * Which view carries the decoded CarPlay picture.
 *
 * SurfaceView is the default since 2026-11-11: SurfaceFlinger composites the decoder buffers, so the
 * UI thread stops drawing a full-screen textured quad every frame (measured 76% -> 12% app CPU on a
 * static screen). The cost is that picture adjustments (colour matrix via `setLayerPaint`) and the
 * corner mask do not apply on this path, and its output queue can back up under the 250 ms age rule.
 *
 * TextureView stays one settings switch away: it applies the colour matrix and the corner mask, and
 * `dropped=0` while dragging, at the cost of the per-frame UI-thread draw.
 *
 * Precedence: `debug.diplay.video_surface` (lab override) > the settings switch > SurfaceView.
 */
object VideoSurfaceMode {

    const val PROPERTY = "debug.diplay.video_surface"

    /** Pure decision so the precedence is unit-testable without a device. */
    fun choose(preferenceEnabled: Boolean, property: String?): Boolean =
        when (property?.trim()?.lowercase()) {
            "1", "surface", "true" -> true
            "0", "texture", "false" -> false
            else -> preferenceEnabled
        }

    fun useSurfaceView(context: android.content.Context): Boolean =
        choose(AirPlayPersistence.loadSurfaceViewExperimental(context), systemProperty())

    /** API 19 has no public property API; the same reflection the other debug switches use. */
    private fun systemProperty(): String? = runCatching {
        Class.forName("android.os.SystemProperties")
            .getMethod("get", String::class.java)
            .invoke(null, PROPERTY) as? String
    }.getOrNull()
}
