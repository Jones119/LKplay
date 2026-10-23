package com.shilapi.xcertplay

/**
 * Which view carries the decoded CarPlay picture.
 *
 * TextureView is the shipped default: the UI thread draws a full-screen textured quad every frame
 * (that is the biggest single-thread cost on API 19, where HWUI draws there), but it has been the
 * stable path on this head unit for weeks: `dropped=0` while dragging and no black screen after the
 * surface is recreated.
 *
 * SurfaceView is the experiment: measured 76% -> 12% app CPU on a static screen, but it lost picture
 * adjustments and the corner mask, needed explicit buffer sizing (a display-size negotiation bug there
 * squashed the picture), and drops frames under the 250 ms age rule because its output queue backs up.
 * It stays one settings switch away so a future attempt can pick it up without patching the app.
 *
 * Precedence: `debug.diplay.video_surface` (lab override) > the settings switch > TextureView.
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
