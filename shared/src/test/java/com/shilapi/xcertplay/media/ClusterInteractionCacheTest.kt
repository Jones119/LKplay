package com.shilapi.xcertplay.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * The re-resolution rule for a vendor object that stops working (docs/_digest/45 G4).
 *
 * `DimInteraction.create()` binds asynchronously and the media interaction object can be a shell whose
 * internal DI is null -- the vendor's own jar contains the diagnostic string
 * `getMediaInteraction() mMediaDI is null` -- in which case every call is silently discarded. A
 * restarted service leaves a dead binder that behaves the same way. Both look exactly like success from
 * here, so the only usable signal is a failed call, and the only usable response is to stop trusting
 * what was cached. These tests pin that down with counters instead of a car.
 */
class ClusterInteractionCacheTest {
    @Test
    fun theFacadeAndTheMediaHandleAreResolvedOnce() {
        val cache = ClusterInteractionCache()
        var facadeResolutions = 0
        var mediaResolutions = 0

        val facade = cache.dim { facadeResolutions++; "facade" }
        val media = cache.media { mediaResolutions++; "media" }

        assertSame("facade", facade)
        assertSame("media", media)
        assertSame(facade, cache.dim { facadeResolutions++; "other" })
        assertSame(media, cache.media { mediaResolutions++; "other" })
        assertEquals(1, facadeResolutions)
        assertEquals(1, mediaResolutions)
    }

    @Test
    fun aResolutionThatReturnedNothingIsNotRemembered() {
        val cache = ClusterInteractionCache()
        var resolutions = 0

        // The interaction objects are null until the vendor's service has bound.
        assertNull(cache.media { resolutions++; null })
        assertNull(cache.media { resolutions++; null })

        assertEquals(2, resolutions)
    }

    @Test
    fun aFailedCallDropsTheHandleAndItsFacadeSoTheNextUpdateResolvesAgain() {
        val cache = ClusterInteractionCache()
        var facadeResolutions = 0
        var mediaResolutions = 0
        val facade = { cache.dim { facadeResolutions++; Handle("facade-$facadeResolutions") } }
        val media = { cache.media { mediaResolutions++; Handle("media-$mediaResolutions") } }

        val firstFacade = facade()
        val firstMedia = media()
        assertSame(firstFacade, facade())
        assertSame(firstMedia, media())

        cache.invalidateMedia()

        // The facade is dropped too: the shell object was produced *by* the facade, so re-resolving
        // only the handle would hand the same dead object back.
        val secondFacade = facade()
        val secondMedia = media()
        assertNotSame(firstFacade, secondFacade)
        assertNotSame(firstMedia, secondMedia)
        assertEquals("facade-2", (secondFacade as Handle).name)
        assertEquals("media-2", (secondMedia as Handle).name)
        assertEquals(2, facadeResolutions)
        assertEquals(2, mediaResolutions)
    }

    /** A stand-in for a vendor interaction object: identity matters, contents never do. */
    private class Handle(val name: String)
}
