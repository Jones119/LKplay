package com.shilapi.xcertplay.hud

import com.shilapi.xcertplay.iap2.body.Iap2BodyReader
import com.shilapi.xcertplay.iap2.wire.Iap2Frame

/** The CarPlay song; [line] preserves title-only text, [source] selects a note's music icon. */
internal data class ClusterSong(val text: String, val playing: Boolean, val line: String = text, val source: Int? = null)

/**
 * The CarPlay song, from iAP2 NowPlayingUpdate (0x5001): title (1) and artist (12) in
 * MediaItemAttributes, playback status in PlaybackAttributes. Updates carry only what changed;
 * omitted fields retain their previous values, while an explicitly cleared title forgets the item.
 */
internal class ClusterSongState {
    private var title: String? = null
    private var artist: String? = null
    private var playing = false
    private var last: ClusterSong? = null

    /** Updates the cached card; null can mean unchanged or cleared, so consumers compare [current]. */
    fun accept(frame: Iap2Frame): ClusterSong? {
        if (frame.messageId != NOW_PLAYING_UPDATE) return null
        val body = runCatching { Iap2BodyReader.of(frame) }.getOrNull() ?: return null
        runCatching { body.optionalGroup(ITEM) }.getOrNull()?.let { item ->
            val nextTitle = runCatching { item.optionalString(TITLE) }.getOrNull()
            if (nextTitle != null) {
                title = nextTitle
                if (nextTitle.isBlank()) artist = null
            }
            if (nextTitle?.isBlank() != true) {
                runCatching { item.optionalString(ARTIST) }.getOrNull()?.let { artist = it }
            }
        }
        runCatching { body.optionalGroup(PLAYBACK)?.optionalU8(STATUS) }.getOrNull()?.let { status ->
            playing = status == STATUS_PLAYING || status == STATUS_SEEK_FORWARD || status == STATUS_SEEK_BACKWARD
        }
        val next = text(title, artist)?.let { ClusterSong(it, playing, title!!.trim()) }
        if (next == last) return null
        last = next
        return next
    }

    /** The card for the song known so far, if any. */
    fun current(): ClusterSong? = last

    /** The session ended: forget the song. */
    fun clear() {
        title = null
        artist = null
        playing = false
        last = null
    }

    companion object {
        const val NOW_PLAYING_UPDATE = 0x5001
        private const val ITEM = 0
        private const val TITLE = 1
        private const val ARTIST = 12
        private const val PLAYBACK = 1
        private const val STATUS = 0
        private const val STATUS_PLAYING = 1
        private const val STATUS_SEEK_FORWARD = 3
        private const val STATUS_SEEK_BACKWARD = 4

        /** "Title — Artist", shortened to what a dashboard card takes; null without a title. */
        fun text(title: String?, artist: String?): String? {
            val name = title?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val full = artist?.trim()?.takeIf { it.isNotEmpty() }?.let { "$name — $it" } ?: name
            // UTF-16LE 每个 code unit 固定 2 字节，所以 255 字节就是 127 个 code unit。原来的
            // “substring + toByteArray”循环在长标题上每减一个字符就重算整串，是 O(n²)；
            // 直接算上限再回退一个孤立低代理，结果完全等价。
            if (full.length <= MAX_TEXT_BYTES / 2) return full
            var end = MAX_TEXT_BYTES / 2
            if (Character.isLowSurrogate(full[end])) end--
            return full.substring(0, end)
        }

        /** The dashboard takes at most 255 bytes of UTF-16LE. */
        const val MAX_TEXT_BYTES = 255
    }
}
