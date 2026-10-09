package com.shilapi.xcertplay.media

/** A ROM can reject a legacy stream by throwing or returning an uninitialized track. */
internal object LegacyAudioFallback {
    /** A fallback route that produced an initialized track. */
    data class Choice<T>(val route: AudioTrackRoute, val track: T, val attempted: List<AudioTrackRoute>)

    /**
     * Ordered fallback routes for one preferred stream.
     *
     * Each rejected route is consumed exactly once, so the renderer can name every stream it
     * tried: [Choice.attempted] always ends on the route that produced the returned track.
     */
    class Attempts(routes: List<AudioTrackRoute>) {
        private val remaining = routes.toMutableList()

        fun <T> next(
            build: (AudioTrackRoute) -> T,
            isInitialized: (T) -> Boolean,
            release: (T) -> Unit,
        ): Choice<T> {
            val attempted = mutableListOf<AudioTrackRoute>()
            while (remaining.isNotEmpty()) {
                val route = remaining.removeAt(0)
                attempted += route
                val candidate = try {
                    build(route)
                } catch (_: RuntimeException) {
                    continue
                }
                if (isInitialized(candidate)) return Choice(route, candidate, attempted)
                release(candidate)
            }
            throw IllegalStateException("no AudioTrack route accepted for this stream")
        }
    }

    fun <T> build(
        createLegacy: () -> T,
        isInitialized: (T) -> Boolean,
        release: (T) -> Unit,
        createFallback: () -> T,
    ): T {
        val candidate = try { createLegacy() } catch (_: RuntimeException) { null }
        if (candidate != null) {
            if (isInitialized(candidate)) return candidate
            release(candidate)
        }
        return createFallback()
    }
}
