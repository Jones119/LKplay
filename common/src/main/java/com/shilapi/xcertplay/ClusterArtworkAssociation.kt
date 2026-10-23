package com.shilapi.xcertplay

/**
 * Decides which album cover belongs to what is playing right now.
 *
 * The cluster used to be told about a cover only when the transfer id that finished decoding was
 * *still* the current one, so a cover that arrived a moment before or after its metadata (or under an
 * id the iPhone never announces again) was cached and then dropped forever. The car showed the first
 * track's art and nothing else (docs/_digest/48 §2). This is the port of the working reference's
 * `LegacyVehicleArtwork.associate()` state machine (docs/_digest/45 §2.5), which answers the same
 * question by *identity* -- `(title, artist, album)`, trimmed -- instead of by transfer id:
 *
 * - a retained cover survives pure position/album-art-less updates of the same track, so a track that
 *   is announced in pieces keeps the cover it already had;
 * - it is dropped when the identity changes, or when there is no title to attach it to -- never kept
 *   "just in case" and shown against the wrong song. A paused frame keeps it: pauses are transient and the
 *   phone sends them mid-playback, and clearing there made the cover flash and vanish (2026-10-23);
 * - when an update carries no new artwork, the retained cover comes back out, so an update that only
 *   moved the position re-associates the same image rather than clearing it;
 * - metadata arriving in pieces is tracked with the pending-title / inherited-artist / inherited-album
 *   fields, so an identity that only *completes* the previous one (artist or album filled in later)
 *   keeps the cover instead of being treated as a new track.
 *
 * Generic over the cover type, and free of Android types, so every rule above is unit-tested on the
 * JVM (this repository's pattern, as in `NowPlayingArtworkQueue<Image>`). The identity comparison is
 * reference equality: the caller hands over the same `Bitmap` instance for a cover it already
 * associated, and a decoded image is a new instance by construction.
 *
 * [placeholder] is the caller's fallback drawing, and it is never a cover: offering it is the same as
 * offering nothing (docs/_digest/45 G6). The caller must not offer it either -- this is the guard that
 * makes "the placeholder is submitted as album art" impossible rather than merely unlikely.
 */
internal class ClusterArtworkAssociation<Cover : Any>(private val placeholder: Cover? = null) {
    /**
     * Who a cover belongs to: the three fields the cluster is told about, blanks normalised to null.
     *
     * Trimming happens here rather than at the caller because the comparison decides whether a cover
     * survives a track change; `"Song"` and `"Song "` are the same track.
     */
    data class Identity(val title: String?, val artist: String?, val album: String?) {
        /** True when [previous] has no field this identity would contradict, i.e. it is the same song. */
        fun fillsMissingFieldsOf(previous: Identity): Boolean =
            (previous.title == null || title == previous.title) &&
                (previous.artist == null || artist == previous.artist) &&
                (previous.album == null || album == previous.album)
    }

    /** A new title over the previous owner's identity, kept so a later update can be recognised. */
    private data class PendingTitle(val title: String, val previous: Identity)

    private var source: Cover? = null
    private var owner: Identity? = null
    private var pendingTitle: PendingTitle? = null
    private var inheritedArtist: String? = null
    private var inheritedAlbum: String? = null

    /** The cover that survived the last update, and the identity it was retained for. */
    private var retainedArtwork: Cover? = null
    private var retainedFor: Identity? = null

    /** Forgets everything; the next session must not inherit this one's cover. */
    fun reset() {
        source = null
        owner = null
        pendingTitle = null
        inheritedArtist = null
        inheritedAlbum = null
        clearRetained()
    }

    /** The identity the retained cover belongs to, or null when nothing is retained. */
    fun retainedIdentity(): Identity? = retainedFor

    /**
     * The cover to show for this update, or null when the cluster should draw its own default.
     *
     * [artwork] is the cover to offer -- the image that was just decoded, or the one cached for the
     * transfer this update names -- and null when this update brought no new picture. The returned
     * value is what the caller publishes; null is a decision, not a failure.
     */
    fun associate(
        title: String?,
        artist: String?,
        album: String?,
        playing: Boolean,
        artwork: Cover?,
    ): Cover? {
        val offered = artwork?.takeIf { it !== placeholder }
        val identity = Identity(nonBlank(title), nonBlank(artist), nonBlank(album))
        // A cover is only ever kept for a named track. Pause deliberately no longer invalidates it: the
        // iPhone sends a paused frame in the middle of ordinary playback (and every time the car radio takes
        // focus for a moment), and clearing there is what made a cover flash and disappear within a second
        // (measured on the car 2026-10-23). A real stop clears the title, which still drops it.
        val canRetain = identity.title != null
        if (canRetain) {
            if (retainedFor != identity) clearRetained()
        } else {
            clearRetained()
        }
        if (offered == null) {
            source = null
            owner = null
            inheritedArtist = null
            inheritedAlbum = null
            if (pendingTitle?.title != identity.title) pendingTitle = null
            return retainedArtwork
        }
        if (source === offered) {
            // The same picture again: only the identity it belongs to can have changed, and only in
            // the direction of filling in what the previous update was missing.
            val previousOwner = owner
            if (previousOwner != null) {
                val inheritedCorrection = identity.title == previousOwner.title &&
                    canCompleteField(identity.artist, previousOwner.artist, inheritedArtist) &&
                    canCompleteField(identity.album, previousOwner.album, inheritedAlbum)
                if (identity.fillsMissingFieldsOf(previousOwner) || inheritedCorrection) {
                    if (identity.artist != previousOwner.artist) inheritedArtist = null
                    if (identity.album != previousOwner.album) inheritedAlbum = null
                    owner = identity
                }
            }
        } else {
            // A new picture. Whatever the previous owner's artist/album were, this update may still be
            // carrying them: that is what "inherited" means, and it is what lets a later correction be
            // recognised as the same track rather than a new one.
            val transition = pendingTitle?.takeIf { it.title == identity.title }
            inheritedArtist = identity.artist?.takeIf { it == transition?.previous?.artist }
            inheritedAlbum = identity.album?.takeIf { it == transition?.previous?.album }
            pendingTitle = null
            source = offered
            owner = identity
        }
        if (identity.title != null && identity == owner) {
            pendingTitle = null
            if (!canRetain) return null
            retainedArtwork = offered
            retainedFor = identity
            return offered
        }
        val previousOwner = owner
        if (identity.title != null && previousOwner?.title != null && identity.title != previousOwner.title) {
            pendingTitle = PendingTitle(identity.title, previousOwner)
        }
        return null
    }

    private fun clearRetained() {
        retainedArtwork = null
        retainedFor = null
    }

    /** True when [next] fits where [previous] had a gap, or when the gap was filled by inheritance. */
    private fun canCompleteField(next: String?, previous: String?, inherited: String?): Boolean =
        next == previous || previous == null || !(next == null || inherited == null || previous != inherited)

    private fun nonBlank(value: String?): String? = value?.trim()?.ifEmpty { null }
}
