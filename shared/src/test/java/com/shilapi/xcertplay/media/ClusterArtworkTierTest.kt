package com.shilapi.xcertplay.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tier precedence with the loopback HTTP server in it, and the `debug.diplay.artwork_uri` bench
 * override that has to beat all three tiers (docs/_digest/45 §2.4).
 *
 * Both rules are pure functions on purpose: "which string reaches the vendor" is the one decision the
 * on-car experiment depends on, and it must be assertable without a socket, a car or a vendor service.
 * The HTTP tier's real behaviour is asserted against a real socket in `ClusterArtworkHttpServerTest`,
 * and the store's own handover -- which is what actually writes the cover and starts the server -- in
 * `ClusterArtworkStoreHttpTierTest`.
 */
class ClusterArtworkTierTest {
    @Test
    fun theLoopbackServerWinsEveryCheaperTierWhenItBound() {
        assertEquals(
            ClusterArtworkTier.HTTP,
            clusterArtworkTier(httpStarted = true, fileUsable = true, providerRegistered = true, granted = 2),
        )
        // The file tier is what the car got before this tier existed, so it stays the first fallback.
        assertEquals(
            ClusterArtworkTier.FILE,
            clusterArtworkTier(httpStarted = false, fileUsable = true, providerRegistered = true, granted = 2),
        )
        assertEquals(
            ClusterArtworkTier.CONTENT,
            clusterArtworkTier(httpStarted = false, fileUsable = false, providerRegistered = true, granted = 1),
        )
        // Either half of the provider tier missing and no file: nothing may be handed over, because a
        // Uri the reader can only fail on is worse than no cover.
        assertNull(clusterArtworkTier(httpStarted = false, fileUsable = false, providerRegistered = false, granted = 2))
        assertNull(clusterArtworkTier(httpStarted = false, fileUsable = false, providerRegistered = true, granted = 0))
    }

    @Test
    fun theCheaperTierRuleIsUnchangedWhenNoServerExists() {
        // The three-argument form is the same rule with the HTTP tier unavailable; the on-car runs of
        // 2026-10-09/10-10 ran in exactly that state, so its answers must not move.
        assertEquals(ClusterArtworkTier.FILE, clusterArtworkTier(fileUsable = true, providerRegistered = true, granted = 2))
        assertEquals(ClusterArtworkTier.CONTENT, clusterArtworkTier(fileUsable = false, providerRegistered = true, granted = 1))
        assertNull(clusterArtworkTier(fileUsable = false, providerRegistered = true, granted = 0))
    }

    @Test
    fun anAbsoluteHttpUrlPropertyBecomesTheDeliveredUri() {
        val remote = "http://y.gtimg.cn/music/photo_new/T002R300x300M000000FUEPg2e4lIX_3.jpg"

        assertEquals(remote, parseArtworkUriOverride(remote))
        // Tolerated as typed: a shell quote or a trailing space is not a reason to lose the experiment.
        assertEquals(remote, parseArtworkUriOverride("  $remote  "))
        assertEquals("https://example.invalid/cover.jpg", parseArtworkUriOverride("https://example.invalid/cover.jpg"))
    }

    @Test
    fun anUnsetOrUnusablePropertyIsNotAnOverride() {
        val computed = "http://127.0.0.1:41234/covers/0f0e0d0c-0b0a-0908-0706-050403020100/a.jpg"

        // Unset is the state after every ignition, because `setprop` does not survive one.
        assertNull(parseArtworkUriOverride(null))
        assertNull("an empty property is unset, not a Uri", parseArtworkUriOverride(""))
        assertNull("a blank property is unset too", parseArtworkUriOverride("   "))
        // Every way a bench typo can miss. None of them may cost the cover the tiers produced.
        val unusable = listOf(
            "a bare string" to "not a uri at all",
            "a relative path" to "/covers/a/b.jpg",
            "a missing host" to "http://",
            "a file Uri" to "file:///sdcard/DiPlay/cluster-artwork/x.jpg",
            "a content Uri" to "content://com.shihab.diplay.hudtest.artwork/covers/x/y.jpg",
            "another scheme" to "ftp://example.invalid/cover.jpg",
        )
        for ((label, property) in unusable) {
            assertNull(label, parseArtworkUriOverride(property))
            assertEquals("$label must leave the computed Uri in charge", computed, clusterArtworkHandover(property, computed))
        }
    }

    @Test
    fun theOverrideIsHandedOverInsteadOfTheComputedUriAndAnUnusableOneIsNot() {
        val remote = "http://y.gtimg.cn/music/photo_new/T002R300x300M000000FUEPg2e4lIX_3.jpg"
        val computed = "http://127.0.0.1:41234/covers/0f0e0d0c-0b0a-0908-0706-050403020100/a.jpg"

        // The override beats all three tiers, which is the whole point: only a URL that never passed
        // through this app can separate "the reader fetches http" from "our URL is wrong".
        assertEquals(remote, clusterArtworkHandover(remote, computed))
        assertEquals(computed, clusterArtworkHandover(null, computed))
        assertEquals(computed, clusterArtworkHandover("garbage", computed))
        // Nothing computed and no override: the slot is cleared rather than filled with a broken Uri.
        assertNull(clusterArtworkHandover(null, null))
        assertNull(clusterArtworkHandover("garbage", null))
        assertEquals(remote, clusterArtworkHandover(remote, null))
    }

    @Test
    fun theUriLogIsWrittenOncePerChangeAndAgainWhenTheSourceChanges() {
        val gate = ClusterArtworkUriChangeGate()
        val url = "http://127.0.0.1:41234/covers/0f0e0d0c-0b0a-0908-0706-050403020100/a.jpg"

        assertTrue("the first announcement of a Uri is logged", gate.shouldLog(url, CLUSTER_ARTWORK_SOURCE_TIER))
        assertTrue(
            "the same Uri must not be logged twice: the push runs about twice a second",
            !gate.shouldLog(url, CLUSTER_ARTWORK_SOURCE_TIER),
        )
        // The source is part of the key, because "the override stopped applying" is worth seeing even
        // when the Uri it forced equals the computed one.
        assertTrue(gate.shouldLog(url, CLUSTER_ARTWORK_SOURCE_OVERRIDE))
        assertTrue(gate.shouldLog(null, CLUSTER_ARTWORK_SOURCE_TIER))
        assertTrue(!gate.shouldLog(null, CLUSTER_ARTWORK_SOURCE_TIER))
    }

    @Test
    fun theOverridePropertyNameIsTheDocumentedOne() {
        // The on-car recipe is a literal `setprop`, so the name is part of the interface.
        assertEquals("debug.diplay.artwork_uri", CLUSTER_ARTWORK_URI_OVERRIDE)
        assertEquals("override", CLUSTER_ARTWORK_SOURCE_OVERRIDE)
        assertEquals("tier", CLUSTER_ARTWORK_SOURCE_TIER)
    }
}
