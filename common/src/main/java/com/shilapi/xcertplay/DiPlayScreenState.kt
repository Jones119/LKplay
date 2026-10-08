package com.shilapi.xcertplay

/**
 * Which DiPlay screen currently has the foreground.
 *
 * The centre-screen map card must appear when the driver leaves DiPlay for the car's own UI and must
 * never appear over a DiPlay screen. Both activities sit in the same process and only the main thread
 * touches this, so a plain flag is enough — and it is set from the CarPlay host's own lifecycle,
 * which is the only reliable answer to "is a DiPlay screen visible right now".
 */
internal object DiPlayScreenState {
    /** True between [CarPlayHostActivity.onStart] and its [CarPlayHostActivity.onStop]. */
    @Volatile
    var carPlayHostVisible = false
}
