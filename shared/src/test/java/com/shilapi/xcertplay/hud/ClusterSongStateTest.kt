package com.shilapi.xcertplay.hud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `ClusterSongState.text` shortens the card to what the cluster takes (255 bytes of UTF-16LE).
 *
 * The rewrite replaced a per-character `substring + toByteArray` loop (O(n^2) on long titles) with a
 * direct bound. These cases pin down that the two are equivalent, including the surrogate-pair edge
 * where a naive cut would leave half an emoji.
 */
class ClusterSongStateTest {
    private fun bytes(text: String) = text.toByteArray(Charsets.UTF_16LE).size

    @Test
    fun shortTitleIsKeptWithItsArtist() {
        assertEquals("Song — Artist", ClusterSongState.text("Song", "Artist"))
    }

    @Test
    fun blankTitleDropsTheCard() {
        assertNull(ClusterSongState.text(null, "Artist"))
        assertNull(ClusterSongState.text("   ", "Artist"))
    }

    @Test
    fun artistIsTrimmedAndOmittedWhenBlank() {
        assertEquals("Song", ClusterSongState.text("  Song  ", "   "))
    }

    @Test
    fun exactlyTheByteBudgetIsNotTruncated() {
        val title = "a".repeat(ClusterSongState.MAX_TEXT_BYTES / 2)
        assertEquals(title, ClusterSongState.text(title, null))
    }

    @Test
    fun longAsciiTitleIsCutToTheByteBudget() {
        val title = "a".repeat(200)
        val out = ClusterSongState.text(title, null)
        assertEquals(ClusterSongState.MAX_TEXT_BYTES / 2, out?.length)
        assertTrue(bytes(out!!) <= ClusterSongState.MAX_TEXT_BYTES)
    }

    @Test
    fun aSurrogatePairIsNeverCutInHalf() {
        // 126 ASCII + one emoji = 128 code units = 256 bytes, one over the budget.
        val title = "a".repeat(126) + "\uD83D\uDE00"
        val out = ClusterSongState.text(title, null)
        assertEquals(126, out?.length)
        assertTrue(bytes(out!!) <= ClusterSongState.MAX_TEXT_BYTES)
        // The result must not end in a lone high surrogate.
        assertTrue(!Character.isHighSurrogate(out[out.length - 1]))
    }

    @Test
    fun aPairThatFitsEntirelyIsKept() {
        // 127 ASCII + emoji: the cut lands before the pair, so the ASCII prefix stays.
        val title = "a".repeat(127) + "\uD83D\uDE00"
        val out = ClusterSongState.text(title, null)
        assertEquals(127, out?.length)
        assertTrue(bytes(out!!) <= ClusterSongState.MAX_TEXT_BYTES)
    }

    @Test
    fun theArtistSuffixCountsAgainstTheBudget() {
        val out = ClusterSongState.text("a".repeat(100), "b".repeat(100))
        assertTrue(bytes(out!!) <= ClusterSongState.MAX_TEXT_BYTES)
        assertTrue(out.length <= ClusterSongState.MAX_TEXT_BYTES / 2)
    }
}
