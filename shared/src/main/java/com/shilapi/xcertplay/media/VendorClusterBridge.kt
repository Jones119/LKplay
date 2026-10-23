package com.shilapi.xcertplay.media

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.shilapi.xcertplay.glance.CarPlayGlance
import com.shilapi.xcertplay.hud.ClusterCall
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import com.shilapi.xcertplay.compat.WorkerThreads

/** The adb override for the cluster push; [VendorClusterBridge.enabled] documents its precedence. */
private const val CLUSTER_SWITCH = "debug.diplay.cluster"

/** The adb override for the arrow id itself; [parseArrowOverride] documents its precedence. */
private const val CLUSTER_ARROW = "debug.diplay.arrow"

/**
 * Pushes what CarPlay is playing, and where it is guiding, into the head unit's instrument cluster.
 *
 * The cluster is driven by the vendor's DIM API, which is on the boot classpath already
 * (`/system/framework/ecarx.adapterapi.jar`, the same jar behind our Bluetooth work):
 *
 *   DimInteraction.create(Context) -> getMediaInteraction() : IMediaInteraction
 *                                  -> getNaviInteraction()  : INaviInteraction
 *
 * The exact surface was read out of the pulled jar (docs/_digest/37, corrected by docs/_digest/46,
 * which read the car's own binaries): the navigation setters that work are
 *   notifyTurnByTurnStopped() / updateTurnByTurnArrow(int) / updateNextGuidancePointName(String)
 *   / updateDistanceToNextGuidancePoint(int) / updateDistanceToDestination(int) / updateETA(int)
 * plus the media setters `updatePlaybackInfo(IPlaybackInfo)` / `updateCurrentSourceType(int)` /
 * `updateCurrentProgress(long)`.
 *
 * Three of those signatures carry the semantics the cluster actually renders (docs/_digest/46 §1):
 * the arrow is an integer icon id, both distances are integer metres, and the ETA is the
 * remaining time in integer seconds. The two image-shaped members of the surface are decoys:
 * `updateTurnByTurnArrow(Bitmap)` is an empty method in the vendor's implementation, and the image
 * route the head unit's own HiCar bridge uses ends in its own HUD, not in this API -- the head unit
 * has no HUD at all. `notifyTurnByTurnStarted()` is worse than useless: it submits state 0, which the
 * service rejects with `RuntimeException("Navigation state is not correct.")`.
 *
 * The arrow id itself is the factory navigation's family (101-143), not the `ARROW_ICON_NAVI_*` family
 * the reference implementation used: the cluster renders the two differently, and only the factory's
 * comes out white and centred (docs/_digest/46 §10, [clusterArrowId]).
 *
 * `IPlaybackInfo` has no concrete implementation in the jar, but it is a plain Java interface, so a
 * dynamic proxy supplies it. Everything is reflective and defensive: a failure logs and disables the
 * push rather than touching CarPlay.
 *
 * The push runs when the persisted `cluster_bridge_enabled` setting says so -- the app hands that value
 * to [attach], which is what makes the cluster fill in after an ignition without adb -- and
 * `adb shell setprop debug.diplay.cluster 0|1` overrides the setting both ways for a bench session
 * (see [enabled] for the precedence).
 */
object VendorClusterBridge {
    private const val TAG = "DiPlay-Cluster"
    private const val DIM = "com.ecarx.xui.adaptapi.diminteraction.DimInteraction"

    private const val SOURCE_TYPE_ONLINE = 6
    private const val PLAYBACK_STATUS_PAUSED = 0
    private const val PLAYBACK_STATUS_PLAYING = 1

    @Volatile
    private var appContext: Context? = null

    /** The vendor objects resolved so far; [ClusterInteractionCache] documents the invalidation rule. */
    private val interactions = ClusterInteractionCache()

    @Volatile
    private var navi: Any? = null

    /** Serialises the media push: it now runs from the iAP2 thread, the main thread and the timer. */
    private val pushLock = Any()

    /** The last payload the vendor was handed, so an unchanged one is not dispatched again (G9). */
    private val mediaPushGate = ClusterMediaPushGate()

    /**
     * The last call payload the vendor was handed, plus the floor on how often the card is re-sent.
     *
     * A call change is rare (ringing, connected, ended), so this is mostly the same de-duplication
     * [mediaPushGate] does; the interval only keeps a flapping status from filling the frame queue. An
     * update the floor coalesces is not lost -- it stays in [pendingCall] and the existing re-push tick
     * flushes it.
     */
    private val callPushGate = ClusterCallPushGate()

    /** The call payload still owed to the vendor, if the last attempt was coalesced or failed. */
    @Volatile
    private var pendingCall: ClusterCallUpdate? = null

    /** When the next while-playing re-push or post-failure retry is due (G7). */
    private val pushSchedule = ClusterPushSchedule()

    /** The while-playing re-push timer, created with the first push (G7). */
    @Volatile
    private var repush: ScheduledExecutorService? = null

    /**
     * The cover files this process handed over, in the tiers [ClusterArtworkStore] documents (G5).
     *
     * Process-scoped, not Activity-scoped: [detach] leaves it in place, because the cluster may still
     * fetch a cover this store handed over after the Activity that produced it is gone. Only [release]
     * drops it.
     */
    @Volatile
    private var artworkStore: ClusterArtworkStore? = null

    /**
     * Where the last cover Uri came from, so the bench log gets one line per *change*.
     *
     * Created with the first cover rather than in the object's initialiser, because the read is an
     * override that must be re-read per handover (see [clusterArtworkUriProperty]) while the change
     * detection is the only state worth keeping.
     */
    @Volatile
    private var artworkUriChanges: ClusterArtworkUriChangeGate? = null

    /**
     * True once a complete card of ours reached the cluster, so a later stop clears *our* card only.
     *
     * There is no "guidance started" call any more: it submitted `turnState=0`, which the service
     * rejects, and the card appears with the first legal `turnState=1` submission anyway (docs/_digest/46
     * G4). Ownership simply follows the same event, and it survives a hold: a card kept through an
     * unfillable frame is still ours to clear ([cardHold]).
     */
    @Volatile
    private var cardOnCluster = false

    /**
     * How long that card survives a guidance snapshot that cannot fill one (G5's missing grace).
     *
     * The rule, the window and the clock all live in [ClusterCardHoldGate]; this object owns one instance
     * of it, exactly as `pushSchedule` owns the media re-push's timing.
     */
    private val cardHold = ClusterCardHoldGate()

    /** The artwork the cluster should show, as a file the head unit's own process can read. */
    @Volatile
    private var artworkUri: android.net.Uri? = null


    /** Makes every handover a new file path; the cluster caches a picture by path. */
    @Volatile private var artworkSerial = 0

    /** Last position and when it was seen, to tell playback from a stalled status field. */
    @Volatile private var lastElapsedMillis: Long? = null

    /** True while the iPhone position keeps moving; a stalled status field still means playing. */

    /** True between the cover-first frame and the delayed title switch. */
    @Volatile private var positionAdvancing = false
    @Volatile private var lastElapsedAt = 0L

    /** The bitmap last written to the shared tree; a same-name repeat must not rewrite it. */
    @Volatile private var lastStoredBitmap: android.graphics.Bitmap? = null
    /** The track [artworkUri] was named for; a title-only change must not reuse its cover. */
    @Volatile private var artworkMedia: CarPlayNowPlaying? = null

    /** The track whose metadata DIM has actually received (artwork included). */
    @Volatile private var dispatchedTitle: String? = null

    /** updateCurrentSourceType is a source event; repeating it per frame also aborts DIM sends. */
    @Volatile private var sourceTypeSent = false

    @Volatile
    private var lastMedia: CarPlayNowPlaying? = null

    /**
     * The persisted setting handed over by [attach]; the adb property overrides it in [enabled].
     *
     * It arrives as an argument instead of being read here because the preference lives in `:common`,
     * which depends on this module and can therefore not be read from it.
     */
    @Volatile
    private var settingEnabled = false

    /** Method names already reported as unmatched, so a per-frame call cannot flood logcat. */
    private val missingOverloads = MissingOverloadReport()

    /** The last arrow announced, so the bench log gets one line per change and not one per frame. */
    private val arrowChanges = ClusterArrowChangeGate()

    /** Called once from the app side, with the persisted "push to the cluster" setting. */
    fun attach(context: Context, enabledFromSettings: Boolean) {
        appContext = context.applicationContext
        settingEnabled = enabledFromSettings
        // One line per start, so a car whose cluster stays empty can be told apart from one that was
        // never allowed to push: the adb override is not visible anywhere in the settings screen.
        Log.i(
            TAG,
            "cluster push setting=$enabledFromSettings " +
                "property=${clusterSwitchProperty() ?: "unset"} enabled=$enabled",
        )
    }

    /**
     * Whether the push runs at all.
     *
     * Precedence: `adb shell setprop debug.diplay.cluster 0|1` wins over the persisted setting in both
     * directions, so a bench session can turn the push off without walking into the car's settings and
     * on again without a reboot. The property is only an override: when it is unset -- the state after
     * every ignition, because `setprop` does not survive one -- the persisted setting decides, which is
     * what keeps the cluster filling in without adb. The property is re-read on every lookup so
     * `setprop` takes effect without restarting the app.
     */
    val enabled: Boolean
        get() = appContext != null && resolveClusterBridgeEnabled(settingEnabled, clusterSwitchProperty())

    /**
     * Submits what the iPhone is playing.
     *
     * Two things keep the cost of this down and the cluster correct (docs/_digest/45 G7/G9): an
     * unchanged payload never reaches the vendor's binder again, and a running timer repeats the last
     * payload every few seconds so a service that restarted -- or a cluster screen that was switched
     * away and back -- fills in again without waiting for the next track. The payload's identity is
     * exactly what the vendor can see, so an update that only moved `artworkTransferId` or `sourceApp`
     */
    fun pushMedia(now: CarPlayNowPlaying) {
        lastMedia = now
        val at = elapsedNow()
        val previous = lastElapsedMillis
        val previousAt = lastElapsedAt
        positionAdvancing = now.elapsedMillis != null &&
            previous != null &&
            now.elapsedMillis > previous &&
            at - previousAt < 2000L
        if (now.elapsedMillis != null) {
            lastElapsedMillis = now.elapsedMillis
            lastElapsedAt = at
        }
        startRepushLoop()
        synchronized(pushLock) { dispatchMedia(now, force = false) }
    }

    /**
     * Hands [now] to the vendor, or skips it as a duplicate unless [force].
     *
     * A failed call is a *state* problem, not a per-call one: the vendor can return an interaction
     * object whose internal DI is null (`getMediaInteraction() mMediaDI is null` exists in its own
     * jar, docs/_digest/45 §1.8), and a restarted service leaves a dead binder behind. Both look
     * identical from here -- silence -- so the cached objects are dropped and the same payload is
     * retried on the next update and by the shorter post-failure timer, instead of being remembered
     * as "sent" forever.
     */
    private fun dispatchMedia(now: CarPlayNowPlaying, force: Boolean) {
        val context = appContext ?: return
        // A cover is only offered for the track it was named for: between a track change and the new
        // artwork decode `artworkUri` still points at the previous track (car 2026-10-23).
        val cover = artworkUri.takeIf { sameTrack(artworkMedia, now) }
        // A new track waits for its artwork: an intermediate update with no cover makes DIM send a
        // "no cover" frame and abort the background frame that follows, which is what made the cover
        // appear only sometimes (car 2026-10-23). The text follows once the picture is there.
        if (cover == null && now.playing && now.title != dispatchedTitle) {
            Log.i(TAG, "cluster media waits for artwork title=${now.title}")
            return
        }
        val update = clusterMediaUpdate(now, cover)
        // The vendor re-sends its media frame for every changed payload, position included; at ~2 Hz
        // that kept the background (cover) screen from ever being sent, while the car own player
        // pushes metadata changes only and its cover frame does go out (`Receiver back_ack`,
        // `FrameSenderBackground`). Gate on metadata and send the position separately (E1 2026-10-23).
        val metadata = update.copy(elapsedMillis = null)
        if (!force && !mediaPushGate.shouldPush(metadata)) {
            val target = mediaInteraction(context) ?: return
            now.elapsedMillis?.let { call(target, "updateCurrentProgress", it) }
            return
        }
        val target = mediaInteraction(context) ?: return
        // The iPhone sometimes leaves the playback-status field at paused while audio and position
        // keep going; DIM refuses a cover frame for basePlayState=0 (car 2026-10-23).
        val playing = now.playing || positionAdvancing
        val status = if (playing) PLAYBACK_STATUS_PLAYING else PLAYBACK_STATUS_PAUSED
        val info = playbackInfo(context, now, status, cover) ?: return
        var sent = call(target, "updatePlaybackInfo", info)
        if (!sourceTypeSent) {
            sent = call(target, "updateCurrentSourceType", SOURCE_TYPE_ONLINE) && sent
            if (sent) sourceTypeSent = true
        }
        now.elapsedMillis?.let { sent = call(target, "updateCurrentProgress", it) && sent }
        if (sent) {
            mediaPushGate.notePushed(metadata)
            dispatchedTitle = now.title
            pushSchedule.notePushed(elapsedNow())
        } else {
            mediaPushGate.forget()
            interactions.invalidateMedia()
            pushSchedule.noteFailed(elapsedNow())
            Log.w(TAG, "cluster media push failed; cached vendor interaction dropped for re-resolution")
        }
    }

    /**
     * One tick of the while-playing re-push (G7).
     *
     * The reference refreshes its presentation every 10 s (`PRESENTATION_REFRESH_TIMEOUT_MS`); a
     * short-lived `NowPlayingUpdate` stream and a cluster that was switched away both end in the same
     * blank or stale cover, so the same snapshot is repeated on that cadence while the iPhone plays.
     * The tick runs at [CLUSTER_REPUSH_TICK_MILLIS] and [pushSchedule] decides whether anything is due
     * -- a failure shortens the wait to [CLUSTER_REPUSH_RETRY_MILLIS] -- which keeps the timing rule
     * unit-testable and the timer itself trivial.
     */
    internal fun repushTick() {
        if (!enabled) return
        flushPendingCall()
        val now = lastMedia ?: return
        if (!pushSchedule.isDue(elapsedNow(), now.playing)) return
        // Logged before the dispatch, and worded as "due": whether anything actually reached the vendor
        // is what `dispatchMedia` reports, and the two must not be conflated in a log that the on-car
        // check greps.
        Log.i(TAG, "cluster repush due title=${now.title} playing=${now.playing} artwork=$artworkUri")
        synchronized(pushLock) { dispatchMedia(now, force = true) }
    }

    private fun startRepushLoop() {
        if (repush != null) return
        synchronized(pushLock) {
            if (repush != null) return
            val service = Executors.newSingleThreadScheduledExecutor { runnable ->
                WorkerThreads.create(runnable, CLUSTER_REPUSH_THREAD).apply { isDaemon = true }
            }
            service.scheduleWithFixedDelay(
                { runCatching { repushTick() } },
                CLUSTER_REPUSH_TICK_MILLIS,
                CLUSTER_REPUSH_TICK_MILLIS,
                TimeUnit.MILLISECONDS,
            )
            repush = service
        }
    }

    /**
     * The album art arrives from the iPhone as a Bitmap, and the cluster takes it as a Uri
     * (`IPlaybackInfo.getArtwork`). [ClusterArtworkStore] owns *how* it is handed over -- JPEG at
     * quality 88, longest edge 256 px, encoded under 1 MiB, served as
     * `http://127.0.0.1:<port>/covers/<session>/<uuid>.jpg` by our own loopback server when a port can
     * be bound, otherwise a world-readable `file://` copy in the shared tree, otherwise the
     * `content://` provider Uri plus an explicit read grant -- and its KDoc says why each tier exists
     * (docs/_digest/45 G5 and §2.4).
     *
     * The order is the car's, not the reference's. On 2026-10-09 the `content://` handover succeeded
     * three times in a row (`grants=2 provider=true`, and the vendor's own service logged every new
     * Uri) while the cluster never painted one, and the 2026-10-10 `file://` copies under
     * `/sdcard/DiPlay/` were logged 36 + 6 times and drawn just as little, although `adb shell cat` read
     * all 31326 bytes of them. The car's own media application hands the cluster an `http://` URL for a
     * cover it does display, so the loopback server goes first now.
     *
     * [CLUSTER_ARTWORK_URI_OVERRIDE] wins over all three when it is set to an absolute `http`/`https`
     * URL, and an unset or unparseable value leaves the tier walk in charge -- which is what makes the
     * one decisive bench experiment possible without a rebuild: hand the vendor a known-good remote URL
     * and see whether the cluster paints it (see [artworkOverride]).
     *
     * A null cover is a real answer, not a failure: it clears the slot, so the head unit paints its
     * own default instead of our placeholder being submitted as if it were the album's art (G6).
     */
    /** Whether the published cover belongs to [now] and can be reused as-is. */
    fun artworkMatches(now: CarPlayNowPlaying): Boolean = artworkUri != null && sameTrack(artworkMedia, now)

    fun setArtwork(bitmap: android.graphics.Bitmap?) {
        val context = appContext ?: return
        // Read once per handover, so a `setprop` between the decision and the log cannot make the two
        // disagree, and never cached, so the very next track sees a property that was just set.
        val property = clusterArtworkUriProperty()
        if (bitmap == null) {
            // Nothing is shown any more, so the covers held for the cluster are revoked and deleted
            // rather than left readable in the cache for the rest of the drive. The override is not
            // kept either: it describes the *next* handover, not a cover the vendor may still fetch.
            // This is the one place other than [release] where the delivered bytes may go: a null cover
            // means the vendor is being told there is no artwork, so no URL of ours is in use.
            artworkUri = null
            artworkMedia = null
            artworkStore?.clear()
        } else {
            // The cover is named after the track, the way the car media centre names its own, so the
            // handover speaks the convention the cluster's working case uses (see
            // [carMediaArtworkFileName]); a track whose metadata never arrived keeps the unique name.
            val baseName = carMediaArtworkFileName(lastMedia?.title, lastMedia?.album, lastMedia?.artist)
            val existingName = artworkUri?.lastPathSegment
            if (baseName != null && baseName == existingName &&
                bitmap === lastStoredBitmap &&
                java.io.File(CLUSTER_ARTWORK_CAR_MEDIA_IMAGES, baseName).isFile
            ) {
                artworkMedia = lastMedia
                if (enabled) lastMedia?.let { pushMedia(it) }
                return
            }
            lastStoredBitmap = bitmap
            val computed = artworkStore(context).publish(bitmap, baseName)
            artworkUri = handoverUri(property, computed)
            artworkMedia = lastMedia
        }
        val accepted = parseArtworkUriOverride(property)
        val source = if (accepted != null) CLUSTER_ARTWORK_SOURCE_OVERRIDE else CLUSTER_ARTWORK_SOURCE_TIER
        // One line per change: `setArtwork` runs about twice a second while the iPhone updates, so an
        // unconditional line would bury the on-car grep.
        if (artworkChangeGate().shouldLog(artworkUri?.toString(), source)) {
            Log.i(TAG, "cluster artwork uri=$artworkUri source=$source")
        }
        // Re-send so the cluster picks the new cover up with the current track.
        if (enabled) lastMedia?.let { pushMedia(it) }
    }

    /**
     * The Uri for this handover: the usable bench override when one is set, the computed one otherwise.
     *
     * The rule itself is [clusterArtworkHandover]; what lives here is the logging a bench session needs
     * to read. A property that is present but not usable is named with its raw value, because "the typo
     * never applied" and "nothing was set" produce identical delivered Uris and only the raw value tells
     * them apart; the successful case is named by the `source=override` line.
     */
    private fun handoverUri(property: String?, computed: android.net.Uri?): android.net.Uri? {
        if (parseArtworkUriOverride(property) == null && !property.isNullOrEmpty()) {
            Log.w(TAG, "cluster artwork override ignored property=$property")
        }
        val delivered = clusterArtworkHandover(property, computed?.toString()) ?: return null
        return android.net.Uri.parse(delivered)
    }

    private fun artworkChangeGate(): ClusterArtworkUriChangeGate {
        artworkUriChanges?.let { return it }
        synchronized(pushLock) {
            artworkUriChanges?.let { return it }
            return ClusterArtworkUriChangeGate().also { artworkUriChanges = it }
        }
    }

    /**
     * The one store this process publishes through, created on the first cover.
     *
     * Created once and reused across Activity lifetimes (see [detach]): the store's session directory
     * and the covers the cluster may still fetch belong to the CarPlay session, not to the Activity that
     * happened to be on screen when the first cover arrived.
     */
    private fun artworkStore(context: Context): ClusterArtworkStore =
        artworkStore ?: synchronized(pushLock) {
            artworkStore ?: ClusterArtworkStore(context).also { artworkStore = it }
        }

    /**
     * Detaches the bridge from the host Activity, and deliberately leaves the artwork session running.
     *
     * The cover's reader fetches **lazily**: the cluster may ask for the URL minutes after the track it
     * belongs to, and the CarPlay session outlives any one `CarPlayHostActivity` (the user backgrounds
     * the app, or the activity is recreated by a rotation or a memory trim). Releasing the loopback port
     * here is therefore what turned a perfectly healthy `cluster artwork tier=http …` line into a URL
     * answering `connection refused` -- the URL is written when it is produced, and nothing about that
     * line says the server is still up. So this method drops only what is *Activity*-scoped: the change
     * gate, and the Uri of the activity that is going away. The store, its retained covers and the
     * process-wide server stay, so the next `setArtwork` -- and the re-push of the payload the vendor
     * already holds -- keeps naming a socket that is still bound.
     *
     * [release] is the other half: it ends the artwork session, and the host calls it when the session
     * really ends. Both are idempotent, and neither throws: a teardown that fails must never take the
     * caller's `onDestroy` with it.
     */
    fun detach() {
        artworkUri = null
        artworkUriChanges = null
    }

    /**
     * Ends the artwork session for good: the retained covers go and the loopback port is released.
     *
     * The counterpart of [attach], called from the host's own shutdown path -- the user exiting, the
     * controller closing, a wired/wireless restart -- and *not* from `onDestroy`, which only means one
     * Activity instance is gone while the session continues. Everything the artwork tiers own is
     * process-wide and must outlive those instances: the store (its session directory and the covers
     * the cluster may still fetch) and the loopback server (the port its URL names). Only here is it
     * known that no URL of this process will be fetched again, so only here is it right to delete the
     * bytes and close the socket. Idempotent, and the store is dropped so a later session builds a
     * fresh one on a fresh port instead of reusing a released server; the vendor is told nothing, so the
     * cluster keeps whatever it last painted -- the same behaviour a teardown had before the HTTP tier
     * existed.
     */
    fun release() {
        val store = synchronized(pushLock) {
            artworkStore.also { artworkStore = null }
        }
        artworkUri = null
        artworkMedia = null
        dispatchedTitle = null
        sourceTypeSent = false
        artworkUriChanges = null
        runCatching { store?.close() }
            .onFailure { Log.w(TAG, "cluster artwork store close failed", it) }
        // The port is the process's, not the store's, which is why it is released here and not in
        // `ClusterArtworkStore.close`: that method also runs for paths that only mean "this store is
        // done", and closing the shared socket there is the D1 defect itself.
        runCatching { releaseClusterArtworkServer() }
            .onFailure { Log.w(TAG, "cluster artwork server release failed", it) }
    }

    /**
     * Submits this guidance frame to the cluster, or clears our card when the frame cannot fill one.
     *
     * Only a complete card is sent ([clusterGuidanceCard]): the vendor's template engine marks a missing
     * text slot invisible and the cluster then paints its *own* placeholder for it -- the `0 m` and
     * `<1min` seen on the car (docs/_digest/46 §1.3(c) and G5). Refusing the card makes the display
     * honest: nothing, rather than numbers nobody measured. The bitmap route is gone with the arrow
     * renderer that fed it; every value here is one the vendor documents.
     *
     * A frame that cannot fill a card therefore no longer clears at once. The iPhone stops being able to
     * describe the card for a moment whenever it recalculates the route -- on the car the card was
     * cleared and re-submitted 1.6 s later (15:16:30.851 clear, 15:16:32.431 re-submit), which is the
     * blink the driver reported -- so the card already on the cluster is held for
     * [CLUSTER_CARD_HOLD_MILLIS] and cleared only once the frame stays unfillable that long
     * ([ClusterCardHoldGate]). A route that has really ended clears immediately instead
     * ([clusterGuidanceEnded]), so a card cannot linger past the end of a route.
     */
    fun pushGuidance(snapshot: CarPlayGlance.Snapshot) {
        if (!enabled) return
        val context = appContext ?: return
        // Read per frame rather than cached, so `adb shell setprop debug.diplay.arrow 102` reaches the
        // very next card of a running session -- the same rule the cluster switch follows. An unset,
        // empty or unparseable value leaves the mapping in charge.
        val forced = parseArrowOverride(clusterArrowProperty())
        val card = clusterGuidanceCard(snapshot, forced)
        if (card == null) {
            // Nothing displayable this frame. Only a card of ours is worth holding: with nothing of ours
            // on the cluster there is no window to keep and nothing to clear, which is also why
            // [stopGuidance] would refuse the clear.
            if (cardOnCluster) {
                val hold = cardHold.cardMissing(clusterGuidanceEnded(snapshot.routeState), elapsedNow())
                hold.logLine?.let { Log.i(TAG, it) }
                if (hold.action == ClusterCardAction.CLEAR) stopGuidance()
            }
            return
        }
        val target = naviInteraction(context) ?: return
        // One line per *change*: this runs per guidance frame, and a per-frame line would bury every
        // other cluster line during an on-car sweep.
        if (arrowChanges.shouldLog(card.arrowId, forced != null)) {
            val source = if (forced != null) CLUSTER_ARROW else "mapping"
            Log.i(TAG, "cluster arrow id=${card.arrowId} source=$source")
        }
        submitClusterCard(guidanceSink(target), card)
        cardOnCluster = true
        // A complete card is on the cluster again, so the next unfillable frame starts a fresh window.
        cardHold.cardSubmitted()
    }

    /** Clears the card this bridge submitted; it never touches a card somebody else owns. */
    fun stopGuidance() {
        if (!enabled || !cardOnCluster) return
        cardOnCluster = false
        val context = appContext ?: return
        val target = naviInteraction(context) ?: return
        clearClusterCard(guidanceSink(target))
    }

    /**
     * Shows the CarPlay call on the cluster's own phone card, or clears it when [call] is null.
     *
     * The card is not ours to draw: the vendor service builds it from the status we send -- caller,
     * avatar, and its own answer / decline buttons with its own labels -- so one binder call per change
     * is the whole job here. `callStatus` is the switch between the four cards it knows
     * (docs/_digest/86 §2.3), and the mapping lives in [clusterCallUpdate] so it is unit-tested.
     *
     * Only `callCount == 1` is ever sent. The service's two-party and three-party branches are chosen by
     * that field alone, and the three-party branch relabels the answer button to "accept and hold" --
     * which would be a lie for an ordinary call. Answering a waiting call from the cluster is a separate
     * feature ([ClusterCallState] already tracks the second call) and is not wired yet.
     *
     * Whatever the caller does here stays local: a failed push drops the cached vendor handle and is
     * retried by the re-push tick, exactly like the media path (G4/G7).
     *
     * Internal because [ClusterCall] is: the only caller is `CarPlayGlance`, in this module.
     */
    internal fun pushCall(call: ClusterCall?) {
        if (!enabled) return
        val context = appContext ?: return
        val update = clusterCallUpdate(call, elapsedNow())
        // The tick is also what flushes a coalesced or failed call payload, so it has to run even when
        // no media was ever pushed (this is the only other starter of that timer).
        startRepushLoop()
        synchronized(pushLock) {
            pendingCall = update
            dispatchCall(context, update)
        }
    }

    /**
     * Hands [update] to the vendor unless [callPushGate] says otherwise, remembering what is still owed.
     *
     * A push that the gate coalesces stays pending; a push that *fails* also stays pending, because the
     * vendor can hand back an interaction whose internal DI is null or a binder left dead by a service
     * restart, and both look exactly like success from here.
     */
    private fun dispatchCall(context: Context, update: ClusterCallUpdate): Boolean {
        val now = elapsedNow()
        if (!callPushGate.shouldPush(update, now)) return false
        val target = phoneInteraction(context) ?: return false
        val info = callInfo(update) ?: return false
        if (!call(target, "updateCallInfo", info)) {
            callPushGate.forget()
            interactions.invalidatePhone()
            Log.w(TAG, "cluster call push failed; cached vendor interaction dropped for re-resolution")
            return false
        }
        callPushGate.notePushed(update, now)
        pendingCall = null
        // One line per change: this is what the on-car check greps to tell "we sent it" from "the card
        // never appeared" -- the second is the vendor's template-negotiation window (docs/_digest/66 §7).
        Log.i(TAG, "cluster call pushed status=${update.status} name=${update.name} number=${update.number}")
        return true
    }

    /** Re-sends a call payload the gate coalesced or a failed push left owed; runs on the tick. */
    private fun flushPendingCall() {
        val update = pendingCall ?: return
        val context = appContext ?: return
        synchronized(pushLock) { dispatchCall(context, update) }
    }

    /**
     * The vendor object behind [ClusterGuidanceSink], one reflective setter per call.
     *
     * The indirection exists so the submission sequence -- which setters, in which order, and the
     * absence of any start call -- is unit-testable against a recording fake instead of the car.
     */
    internal fun guidanceSink(target: Any): ClusterGuidanceSink = object : ClusterGuidanceSink {
        override fun updateTurnByTurnArrow(arrowId: Int) {
            call(target, "updateTurnByTurnArrow", arrowId)
        }

        override fun updateNextGuidancePointName(road: String) {
            call(target, "updateNextGuidancePointName", road)
        }

        override fun updateDistanceToNextGuidancePoint(meters: Int) {
            call(target, "updateDistanceToNextGuidancePoint", meters)
        }

        override fun updateDistanceToDestination(meters: Int) {
            call(target, "updateDistanceToDestination", meters)
        }

        override fun updateETA(seconds: Int) {
            call(target, "updateETA", seconds)
        }

        override fun notifyTurnByTurnStopped() {
            call(target, "notifyTurnByTurnStopped")
        }
    }

    /**
     * The vendor's interaction objects bind asynchronously — our on-car probe got a null
     * `getNaviInteraction()` straight after `create()` — so a miss is retried on the next update
     * instead of being remembered.
     *
     * What *is* remembered is resolution: the log line appears only when the vendor object is actually
     * looked up again, so logcat shows every re-resolution (a failure drops the cached one, G4).
     */
    private fun mediaInteraction(context: Context): Any? {
        val owner = dimInstance(context) ?: return null
        return interactions.media {
            read(owner, "getMediaInteraction")?.also { value ->
                Log.i(TAG, "cluster media interaction=${value.javaClass.name}")
            }
        }
    }

    private fun naviInteraction(context: Context): Any? {
        navi?.let { return it }
        val owner = dimInstance(context) ?: return null
        val value = read(owner, "getNaviInteraction") ?: return null
        navi = value
        Log.i(TAG, "cluster navi interaction=${value.javaClass.name}")
        return value
    }

    /**
     * The vendor's phone-call interaction, the one facade getter that is not a null stub
     * (`getContactsInteraction()` and `getVrInteraction()` are, docs/_digest/86 §2).
     *
     * Resolved on first use and cached like the media handle, with the same asynchronous-bind caveat:
     * `create()` binds the service in the background and the getter answers null until it is up, so a
     * miss is retried on the next call rather than remembered.
     */
    private fun phoneInteraction(context: Context): Any? {
        val owner = dimInstance(context) ?: return null
        return interactions.phone {
            read(owner, "getPhoneCallInteraction")?.also { value ->
                Log.i(TAG, "cluster phone interaction=${value.javaClass.name}")
            }
        }
    }

    private fun dimInstance(context: Context): Any? = interactions.dim {
        val type = runCatching { Class.forName(DIM) }.getOrNull() ?: run {
            Log.i(TAG, "cluster dim api not on the classpath ($DIM)")
            return@dim null
        }
        runCatching { type.getMethod("create", Context::class.java).invoke(null, context) }.getOrNull()
    }

    /** A dynamic proxy for the vendor's plain `IPlaybackInfo` interface. */
    private fun playbackInfo(context: Context, now: CarPlayNowPlaying, status: Int, artwork: android.net.Uri?, sourceType: Int = SOURCE_TYPE_ONLINE): Any? {
        val type = runCatching {
            Class.forName("com.ecarx.xui.adaptapi.diminteraction.IMediaInteraction\$IPlaybackInfo")
        }.getOrNull() ?: return null
        return runCatching {
            Proxy.newProxyInstance(type.classLoader, arrayOf(type), PlaybackInfoHandler(now, status, artwork, sourceType))
        }.getOrElse {
            Log.w(TAG, "cluster playback info proxy failed", it)
            null
        }
    }


    private class PlaybackInfoHandler(
        private val now: CarPlayNowPlaying,
        private val status: Int,
        private val artwork: android.net.Uri?,
        private val sourceType: Int,
    ) : InvocationHandler {
        override fun invoke(proxy: Any?, method: Method, args: Array<out Any?>?): Any? = when (method.name) {
            "getTitle" -> now.title
            "getArtist" -> now.artist
            "getAlbum" -> now.album
            "getDuration" -> now.durationMillis ?: 0L
            "getPlaybackStatus" -> status
            "getSourceType" -> sourceType
            "getArtwork" -> artwork
            "getPlayingItemPositionInQueue" -> 0
            "getLoopMode" -> 0
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === args?.firstOrNull()
            "toString" -> "DiPlayPlaybackInfo(title=${now.title})"
            else -> defaultValue(method.returnType)
        }
    }

    private fun defaultValue(type: Class<*>): Any? = when (type) {
        java.lang.Boolean.TYPE -> false
        java.lang.Integer.TYPE -> 0
        java.lang.Long.TYPE -> 0L
        java.lang.Float.TYPE -> 0f
        java.lang.Double.TYPE -> 0.0
        else -> null
    }

    /**
     * A dynamic proxy for the vendor's plain `IPhoneCallInteraction$ICallInfo`.
     *
     * The interface has no implementation in the jar and no builder either, so the proxy is the whole
     * object (docs/_digest/86 §2). Of its ten methods the vendor's `PhoneDimInteraction` reads only
     * six -- name, number, avatar, status, call count and duration; `callId`, `activeCallId`,
     * `isHandFree` and `isMicOnVehicleMuted` are written into the parcel and never looked at.
     */
    private fun callInfo(update: ClusterCallUpdate): Any? {
        val type = runCatching {
            Class.forName("com.ecarx.xui.adaptapi.diminteraction.IPhoneCallInteraction\$ICallInfo")
        }.getOrNull() ?: return null
        return runCatching {
            Proxy.newProxyInstance(type.classLoader, arrayOf(type), CallInfoHandler(update))
        }.getOrElse {
            Log.w(TAG, "cluster call info proxy failed", it)
            null
        }
    }

    private class CallInfoHandler(private val update: ClusterCallUpdate) : InvocationHandler {
        override fun invoke(proxy: Any?, method: Method, args: Array<out Any?>?): Any? = when (method.name) {
            "getCallStatus" -> update.status
            "getCallCount" -> update.callCount
            "getContactName" -> update.name
            "getContactNumber" -> update.number
            "getCallDuration" -> update.durationSeconds
            // No avatar: the vendor then draws its own placeholder for slot 2. Its own avatar cache is
            // keyed by the number, so an avatar is only ever sent once per number anyway
            // (`sendAvatar`'s `mLastShowNumber` gate, docs/_digest/86 §2.1) -- sending one from here
            // would be wasted on the second call from the same person.
            "getAvatar" -> null
            "getCallId", "getActiveCallId" -> 0
            "isHandFree" -> true
            "isMicOnVehicleMuted" -> false
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === args?.firstOrNull()
            "toString" -> "DiPlayCallInfo(status=${update.status}, name=${update.name})"
            else -> defaultValue(method.returnType)
        }
    }

    private fun read(target: Any, method: String): Any? =
        runCatching { target.javaClass.getMethod(method).invoke(target) }
            .onFailure { Log.w(TAG, "cluster $method failed", it) }
            .getOrNull()

    /**
     * Invokes one vendor setter, ranked so primitive-typed setters are reachable at all.
     *
     * The previous "first assignable candidate" rule matched none of them: `int.class
     * .isAssignableFrom(Integer.class)` is false for every wrapper, so `updateCurrentSourceType(int)`,
     * `updateCurrentProgress(long)`, `updateDistanceToNextGuidancePoint(int)`,
     * `updateDistanceToDestination(int)`, `updateETA(int)` and the `updateTurnByTurnArrow(int)`
     * fallback were dropped without a log while `updateNextGuidancePointName(String)` went through --
     * the cluster got the road name and no arrow, distance or ETA (docs/_digest/38).
     *
     * An unmatched call is now reported by name, argument types and candidate parameter lists, once per
     * method name: these run per guidance frame, and a repeated warning would fill logcat on the car.
     * The trace line names the signature that was used, so it can no longer be confused with a
     * successful `void` call that returned null.
     *
     * The result is returned because "it did not reach the vendor" and "the vendor swallowed it" look
     * the same from here: only the media path acts on it (a miss *or* a throw means the cached
     * interaction object is dropped and the payload retried, G4).
     */
    private fun call(target: Any, method: String, vararg args: Any?): Boolean {
        val argumentTypes = args.map { it?.javaClass }
        val overloads = target.javaClass.methods.filter { it.name == method }
        val match = VendorMethodMatcher.select(overloads, argumentTypes)
        if (match == null) {
            if (missingOverloads.shouldReport(method)) {
                Log.w(TAG, VendorMethodMatcher.missReport(method, argumentTypes, overloads))
            }
            com.shilapi.xcertplay.diag.DiagLog.trace(TAG) {
                "cluster $method args=${VendorMethodMatcher.describeArguments(argumentTypes)} -> " +
                    "skipped: no overload matches"
            }
            return false
        }
        val result = runCatching { match.method.invoke(target, *args) }
            .onFailure { Log.w(TAG, "cluster ${match.signature}(${args.joinToString()}) failed", it) }
        com.shilapi.xcertplay.diag.DiagLog.trace(TAG) {
            "cluster ${match.signature} args=[${args.joinToString()}] -> ${result.getOrNull()}"
        }
        return result.isSuccess
    }
}

/** The thread the while-playing re-push runs on; it is not the iAP2 control thread (G7). */
internal const val CLUSTER_REPUSH_THREAD = "diplay-cluster-repush"

/**
 * How often the re-push is *considered*, not how often it pushes.
 *
 * [ClusterPushSchedule] decides whether anything is due, so this is only the resolution of that
 * decision: it has to be short enough that the shorter post-failure wait is honoured within a second
 * or two, and cheap enough to be irrelevant (a timestamp comparison). The reference implementation's
 * own refresh is a 10 s timeout (`PRESENTATION_REFRESH_TIMEOUT_MS`, docs/_digest/45 §2.3), which is
 * 20 times the ~2 Hz `NowPlayingUpdate` rate -- a keep-alive, not a stream.
 */
internal const val CLUSTER_REPUSH_TICK_MILLIS = 2_000L

/** How long a successful push keeps the cluster correct before it is repeated [G7]. */
internal const val CLUSTER_REPUSH_INTERVAL_MILLIS = 10_000L

/**
 * How long a *failed* push waits before it is retried.
 *
 * Shorter than [CLUSTER_REPUSH_INTERVAL_MILLIS] on purpose: a failure means either the vendor's DI is
 * a shell or the service restarted, and both are states that can clear themselves, so the retry is
 * worth more than the regular refresh. It is not zero either -- a wedged service must not be called in
 * a tight loop.
 */
internal const val CLUSTER_REPUSH_RETRY_MILLIS = 3_000L

/**
 * Whether a while-playing re-push is due, and whether the previous one failed.
 *
 * A pure state machine over an injected clock (`nowMillis` is an argument, never read from a clock
 * inside), so the interval, the retry and the "paused pushes nothing" rule are unit-tested without a
 * timer or a car. "Paused" is a refusal, not a delay: a paused cluster would be re-pushing a card the
 * driver is not listening to, and the reference's refresh is gated on `playing == true` too.
 */
internal class ClusterPushSchedule(
    private val intervalMillis: Long = CLUSTER_REPUSH_INTERVAL_MILLIS,
    private val retryMillis: Long = CLUSTER_REPUSH_RETRY_MILLIS,
) {
    private var lastAt = 0L
    private var failed = false

    /** The push at [nowMillis] reached the vendor. */
    fun notePushed(nowMillis: Long) {
        lastAt = nowMillis
        failed = false
    }

    /** The push at [nowMillis] did not reach the vendor; the next one waits [retryMillis]. */
    fun noteFailed(nowMillis: Long) {
        lastAt = nowMillis
        failed = true
    }

    /** True when something is playing and the last attempt is old enough to be repeated. */
    fun isDue(nowMillis: Long, playing: Boolean): Boolean {
        if (!playing) return false
        return nowMillis - lastAt >= if (failed) retryMillis else intervalMillis
    }
}

/** The device clock the re-push timestamps come from; monotonic, so a clock change cannot stall it. */
private fun elapsedNow(): Long = SystemClock.elapsedRealtime()

/**
 * What one media push actually puts in front of the vendor.
 *
 * Field for field what `IPlaybackInfo` exposes to the cluster and nothing else: `sourceApp` and
 * `artworkTransferId` stay out, because a change to them changes nothing the cluster can see and must
 * therefore not cost a reflective dispatch and a proxy allocation (docs/_digest/45 G9).
 */
internal data class ClusterMediaUpdate(
    val title: String?,
    val artist: String?,
    val album: String?,
    val durationMillis: Long?,
    val elapsedMillis: Long?,
    val playing: Boolean,
    val artworkUri: android.net.Uri?,
)

/** The payload of one push, as the vendor's getters will see it. */

/** Whether [previous] names the same track as [next]; elapsed time and play state do not count. */
private fun sameTrack(previous: CarPlayNowPlaying?, next: CarPlayNowPlaying): Boolean =
    previous != null &&
        previous.title == next.title &&
        previous.artist == next.artist &&
        previous.album == next.album

internal fun clusterMediaUpdate(now: CarPlayNowPlaying, artworkUri: android.net.Uri?): ClusterMediaUpdate =
    ClusterMediaUpdate(
        title = now.title,
        artist = now.artist,
        album = now.album,
        durationMillis = now.durationMillis,
        elapsedMillis = now.elapsedMillis,
        playing = now.playing,
        artworkUri = artworkUri,
    )

/**
 * Remembers the last payload the vendor was handed.
 *
 * The navigation path already refuses an unchanged frame (`CarPlayGlance.publishLocked`); this is the
 * same rule for media, where the frames arrive about twice a second. A push that *failed* must not be
 * remembered as sent, hence [forget]: the next frame has to try again.
 */
internal class ClusterMediaPushGate {
    private var last: ClusterMediaUpdate? = null

    /** True unless [update] is exactly what the vendor was already given. */
    fun shouldPush(update: ClusterMediaUpdate): Boolean = update != last

    /** Records [update] as handed over. */
    fun notePushed(update: ClusterMediaUpdate) {
        last = update
    }

    /** Drops the record, so the next frame is dispatched even if it is identical. */
    fun forget() {
        last = null
    }
}

/**
 * How often the cluster's phone card may be re-sent, in milliseconds.
 *
 * The card is built by the *vendor* service -- one small foreground frame carrying the caller and the
 * icon ids its own buttons need -- so a push costs one binder transaction plus that frame. The floor is
 * therefore not about our own cost: it keeps a flapping call status from filling the frame queue that
 * the navigation and media cards share. A coalesced change is kept in `VendorClusterBridge.pendingCall`
 * and flushed by the 2 s re-push tick, so nothing is dropped, only deferred.
 */
internal const val CLUSTER_CALL_MIN_INTERVAL_MILLIS = 200L

/** A caller name longer than this is cut: the card is one line, and the frame is paid for per byte. */
internal const val MAX_CALL_NAME_CHARS = 60

/** A remote id longer than this is cut; real numbers are far shorter, and the card shows one line. */
internal const val MAX_CALL_NUMBER_CHARS = 40

/** The vendor's `CallStatus` values that pick a card; the rest of its 0..15 are ignored by the service. */
internal const val CALL_STATUS_IDLE = 0
internal const val CALL_STATUS_DIALING = 6
internal const val CALL_STATUS_OFFHOOK = 11
internal const val CALL_STATUS_IN_COMING = 12

/**
 * What one call push actually puts in front of the vendor.
 *
 * Field for field what the vendor's phone card reads out of `PhoneInfo` and nothing else, so a change
 * that the cluster cannot see does not cost a reflective dispatch and a proxy allocation -- the same
 * rule [ClusterMediaUpdate] follows. [callCount] is always 1: the service's three-party branch is chosen
 * by that field alone and relabels the answer button, so it is only ever sent for a real waiting call.
 */
internal data class ClusterCallUpdate(
    val status: Int,
    val callCount: Int,
    val name: String,
    val number: String,
    val durationSeconds: Long,
)

/**
 * The payload for [call], as the vendor's getters will see it.
 *
 * Null -- no call, or a session that just ended -- becomes the vendor's idle status, which is the one
 * value that clears the card regardless of the call count (`PhoneDimInteraction` calls `endPhone()`
 * before it looks at `callCount`), so a card left over from a finished call cannot linger.
 *
 * The status mapping is the service's own vocabulary (docs/_digest/86 §2.3): `12` is an incoming call,
 * `11` an established one, `6` an outgoing one. The duration is what the service feeds its 600 ms call
 * timer from, hence it is computed from the call's own start rather than from "now".
 */
internal fun clusterCallUpdate(call: ClusterCall?, nowMillis: Long): ClusterCallUpdate = when (call?.phase) {
    null -> ClusterCallUpdate(CALL_STATUS_IDLE, 1, "", "", 0)
    ClusterCall.Phase.RINGING -> ClusterCallUpdate(CALL_STATUS_IN_COMING, 1, clusterCallName(call.name), number(call), 0)
    ClusterCall.Phase.ACTIVE -> ClusterCallUpdate(
        CALL_STATUS_OFFHOOK,
        1,
        clusterCallName(call.name),
        number(call),
        call.activeSinceMillis?.let { ((nowMillis - it) / 1000).coerceAtLeast(0) } ?: 0,
    )
    ClusterCall.Phase.DIALING -> ClusterCallUpdate(CALL_STATUS_DIALING, 1, clusterCallName(call.name), number(call), 0)
}

private fun number(call: ClusterCall): String = call.number.trim().take(MAX_CALL_NUMBER_CHARS)

/**
 * The caller text the card will show, trimmed and cut to [MAX_CALL_NAME_CHARS].
 *
 * An empty name is passed through as empty rather than replaced by the number: the vendor service owns
 * that fallback chain (name, then number, then its own "unknown number" string), and duplicating it
 * here would only be able to disagree with it.
 */
internal fun clusterCallName(value: String): String {
    val trimmed = value.trim()
    if (trimmed.length <= MAX_CALL_NAME_CHARS) return trimmed
    var end = MAX_CALL_NAME_CHARS
    // Never cut a surrogate pair in half: the cluster renders UTF-16 and would show a broken glyph.
    if (Character.isHighSurrogate(trimmed[end - 1])) end--
    return trimmed.substring(0, end)
}

/**
 * Remembers the last call payload the vendor was handed, and how long ago.
 *
 * The navigation path refuses an unchanged frame and the media gate refuses an unchanged payload; a call
 * is the same idea at a much lower rate, plus the floor in [CLUSTER_CALL_MIN_INTERVAL_MILLIS]. A push
 * that *failed* must not be remembered as sent, hence [forget].
 *
 * [shouldPush] takes the clock instead of reading it, so the rule is JVM-testable (the Android uptime
 * clock is not available in a plain unit test) -- the same shape [ClusterPushSchedule] uses.
 */
internal class ClusterCallPushGate(private val minIntervalMillis: Long = CLUSTER_CALL_MIN_INTERVAL_MILLIS) {
    private var last: ClusterCallUpdate? = null
    private var lastPushAt = 0L

    /** True when [update] is new and the previous push is at least [minIntervalMillis] old. */
    fun shouldPush(update: ClusterCallUpdate, nowMillis: Long): Boolean {
        if (update == last) return false
        if (last == null) return true
        return nowMillis - lastPushAt >= minIntervalMillis
    }

    /** Records [update] as handed over at [nowMillis]. */
    fun notePushed(update: ClusterCallUpdate, nowMillis: Long) {
        last = update
        lastPushAt = nowMillis
    }

    /** Drops the record, so the next change is dispatched even if it repeats the last payload. */
    fun forget() {
        last = null
    }
}

/**
 * The vendor objects resolved so far, and the one rule for giving them up.
 *
 * Caching matters: `DimInteraction.create()` binds a service asynchronously and the interaction
 * objects are per-process handles, so resolving once per push would be wasteful. Trusting the cache
 * forever is what does not work -- the vendor can hand back an object whose *internal* DI is null
 * (its own jar contains `getMediaInteraction() mMediaDI is null`, docs/_digest/45 §1.8) and then
 * silently discards every call, and a service restart leaves a dead binder that fails the same way.
 * Neither raises anything we could distinguish from success, so a failed media push calls
 * [invalidateMedia] and the next update resolves again.
 *
 * Only the media path is invalidated: the navigation card has its own cached handle and a separate,
 * just-fixed submission path that a media failure must not disturb.
 */
internal class ClusterInteractionCache {
    private var dim: Any? = null
    private var media: Any? = null
    private var phone: Any? = null

    /** The facade, resolved once. */
    @Synchronized
    fun dim(resolve: () -> Any?): Any? {
        dim?.let { return it }
        val value = resolve() ?: return null
        dim = value
        return value
    }

    /** The media interaction object, resolved once per facade. */
    @Synchronized
    fun media(resolve: () -> Any?): Any? {
        media?.let { return it }
        val value = resolve() ?: return null
        media = value
        return value
    }

    /** The phone-call interaction object, resolved once per facade. */
    @Synchronized
    fun phone(resolve: () -> Any?): Any? {
        phone?.let { return it }
        val value = resolve() ?: return null
        phone = value
        return value
    }

    /**
     * Forgets the media handle *and* the facade it came from.
     *
     * The facade is dropped too because the shell object is produced by the facade: a re-resolve that
     * kept the same facade would just hand the same shell back. The cached navigation handle is a
     * different object and keeps working.
     */
    @Synchronized
    fun invalidateMedia() {
        media = null
        dim = null
    }

    /**
     * Forgets the phone handle and the facade it came from, for the same reason as [invalidateMedia].
     *
     * The media and phone handles share the facade, so dropping it here costs the media path one
     * re-resolution as well -- which is the price of not keeping a facade that just proved dead.
     */
    @Synchronized
    fun invalidatePhone() {
        phone = null
        dim = null
    }
}

/**
 * Decides the cluster push's on/off state from the persisted setting and the adb override.
 *
 * A plain function so the precedence is unit-testable off the car: the property reading "1" or "0"
 * forces the state, and any other value -- unset, empty, or whatever a typo produced -- leaves the
 * persisted setting in charge. An override that could only switch the push on would be useless for the
 * comparison a bench session runs: what the cluster shows with the push off.
 */
internal fun resolveClusterBridgeEnabled(settingEnabled: Boolean, propertyValue: String?): Boolean =
    when (propertyValue) {
        "1" -> true
        "0" -> false
        else -> settingEnabled
    }

/**
 * Reads `debug.diplay.cluster` as a tri-state: "1" on, "0" off, null when nothing was set.
 *
 * Read reflectively because android.os.SystemProperties is not part of the public SDK. Callers read it
 * per lookup rather than caching it so `setprop` applies to a running session; the JVM unit tests have
 * no such class, and get null, which leaves the persisted setting in charge.
 */
internal fun clusterSwitchProperty(): String? = debugProperty(CLUSTER_SWITCH)

/**
 * Reads `debug.diplay.arrow`, the bench override for the arrow id.
 *
 * This is what makes the on-car sweep cheap: the cluster's own rendering of an id can be looked at by
 * driving with `adb shell setprop debug.diplay.arrow <id>`, without rebuilding the app, and without
 * waiting for a maneuver of the matching Apple type to come up on the route. Unset -- the state after
 * every ignition, because `setprop` does not persist -- leaves the mapping in charge (see
 * [parseArrowOverride] and [clusterArrowId]).
 */
internal fun clusterArrowProperty(): String? = debugProperty(CLUSTER_ARROW)

/**
 * Remembers which method names were already reported as having no matching overload.
 *
 * The vendor's setters are called once per media update and once per guidance frame; repeating the same
 * warning every second would bury the lines that matter, so each name is reported once per process.
 */
internal class MissingOverloadReport {
    private val reported = java.util.Collections.newSetFromMap(
        java.util.concurrent.ConcurrentHashMap<String, Boolean>(),
    )

    /** True the first time a method name is seen, false for every repeat. */
    fun shouldReport(method: String): Boolean = reported.add(method)
}

/**
 * Remembers the last arrow announcement, so a guidance frame logs one line per *change*.
 *
 * The push runs per frame -- about twice a second while the iPhone is updating -- so an unconditional
 * `cluster arrow id=...` line would drown the on-car check, which greps for exactly that line. The key
 * includes where the id came from, because "the override stopped applying" is worth seeing even when the
 * id it was forcing happens to equal the mapped one.
 */
internal class ClusterArrowChangeGate {
    private var last: String? = null

    /**
     * True the first time [arrowId] is announced from [fromOverride], false for every repeat.
     *
     * Synchronised because the push can be observed from a test thread, while the real pushes all run
     * on the single cluster worker thread.
     */
    @Synchronized
    fun shouldLog(arrowId: Int, fromOverride: Boolean): Boolean {
        val key = "$arrowId/${if (fromOverride) OVERRIDE else MAPPING}"
        if (key == last) return false
        last = key
        return true
    }

    private companion object {
        const val OVERRIDE = "override"
        const val MAPPING = "mapping"
    }
}

/**
 * Chooses which overload a reflective call hits, and says which one it chose.
 *
 * The candidates are ranked by how well each argument fits each parameter: the same class first, then
 * the primitive/wrapper pair, then a genuine reference-type fit such as `String` into `CharSequence` or
 * anything into `Object`. `Method.invoke` performs the unboxing itself, so matching is all that is
 * needed to reach the vendor's `int`/`long` setters. A `null` argument fits anything, as before.
 *
 * Extracted from [VendorClusterBridge] -- the same shape as [com.shilapi.xcertplay.airplay.AudioStreamWire]
 * -- so the rule is unit-testable against a fake target instead of a real vendor object.
 */
internal object VendorMethodMatcher {
    /** How well one argument class fits one parameter class; the ordinal is the rank, lowest wins. */
    enum class Fit {
        /** The same class, or a null argument, whose runtime type is not available to compare. */
        EXACT,

        /** `int`/`Integer` and the other seven pairs; invocation unboxes the wrapper. */
        PRIMITIVE_PAIR,

        /** A reference-type fit, e.g. `String` into `CharSequence` or anything into `Object`. */
        ASSIGNABLE,
    }

    /** The chosen overload, its printable signature, and the fit that selected it. */
    data class Match(val method: Method, val signature: String, val fits: List<Fit>)

    /**
     * The best-ranked overload for [argumentTypes], or null when none accepts them.
     *
     * Overloads that do not take exactly as many parameters as there are arguments never fit, so the
     * caller can hand over every method of that name. Ties keep the order the caller supplied.
     */
    fun select(overloads: List<Method>, argumentTypes: List<Class<*>?>): Match? {
        var best: Match? = null
        for (candidate in overloads) {
            val fits = fitsOf(candidate.parameterTypes, argumentTypes) ?: continue
            val match = Match(candidate, signatureOf(candidate), fits)
            val current = best
            if (current == null || outranks(match, current)) best = match
        }
        return best
    }

    /** `updateETA(int)`, the name plus its parameter type names, for traces and reports. */
    fun signatureOf(method: Method): String =
        "${method.name}(${method.parameterTypes.joinToString(", ") { it.simpleName }})"

    /** `[java.lang.Integer]`, the classes actually passed, for a miss report. */
    fun describeArguments(argumentTypes: List<Class<*>?>): String =
        argumentTypes.joinToString(", ", "[", "]") { it?.name ?: "null" }

    /**
     * One line naming the method, the argument types and every parameter list the target declares, so a
     * missing vendor setter is visible in logcat instead of looking like a `void` call that did nothing.
     */
    fun missReport(method: String, argumentTypes: List<Class<*>?>, overloads: List<Method>): String =
        "cluster $method has no matching overload: args=${describeArguments(argumentTypes)} " +
            "candidates=${overloads.joinToString(", ") { signatureOf(it) }.ifEmpty { "none" }}"

    private fun fitsOf(parameterTypes: Array<Class<*>>, argumentTypes: List<Class<*>?>): List<Fit>? {
        if (parameterTypes.size != argumentTypes.size) return null
        val fits = ArrayList<Fit>(parameterTypes.size)
        for (index in parameterTypes.indices) {
            fits += fit(parameterTypes[index], argumentTypes[index]) ?: return null
        }
        return fits
    }

    private fun fit(parameter: Class<*>, argument: Class<*>?): Fit? {
        if (argument == null) return Fit.EXACT
        if (parameter == argument) return Fit.EXACT
        // The wrapper pair, in both directions: arguments are always boxed on this path, while the
        // parameter may be either. Nothing else may cross the primitive/reference boundary -- a long
        // argument must never reach an int parameter, because invoke would narrow it silently.
        if (boxed(parameter) == argument || boxed(argument) == parameter) return Fit.PRIMITIVE_PAIR
        return if (parameter.isAssignableFrom(argument)) Fit.ASSIGNABLE else null
    }

    private fun boxed(type: Class<*>): Class<*>? = when (type) {
        java.lang.Integer.TYPE -> java.lang.Integer::class.java
        java.lang.Long.TYPE -> java.lang.Long::class.java
        java.lang.Boolean.TYPE -> java.lang.Boolean::class.java
        java.lang.Float.TYPE -> java.lang.Float::class.java
        java.lang.Double.TYPE -> java.lang.Double::class.java
        java.lang.Byte.TYPE -> java.lang.Byte::class.java
        java.lang.Short.TYPE -> java.lang.Short::class.java
        java.lang.Character.TYPE -> java.lang.Character::class.java
        else -> null
    }

    /**
     * Lexicographic by per-argument fit, so the first argument decides first; equal fit lists fall back
     * to the more specific parameter types, then to the caller's order.
     */
    private fun outranks(candidate: Match, current: Match): Boolean {
        for (index in candidate.fits.indices) {
            val difference = candidate.fits[index].ordinal - current.fits[index].ordinal
            if (difference != 0) return difference < 0
        }
        return moreSpecific(candidate.method.parameterTypes, current.method.parameterTypes)
    }

    /**
     * True when `left` narrows `right` somewhere, e.g. `String` over `Object`. Only consulted for equal
     * fit lists, where two reference parameters can both accept the same argument.
     */
    private fun moreSpecific(left: Array<Class<*>>, right: Array<Class<*>>): Boolean {
        for (index in left.indices) {
            if (left[index] == right[index]) continue
            return right[index].isAssignableFrom(left[index]) && !left[index].isAssignableFrom(right[index])
        }
        return false
    }
}

// ---------------------------------------------------------------------------------------------
// The navigation card: which vendor arrow id, which text, which units, and when to send nothing.
// Extracted from [VendorClusterBridge] so the mapping and the completeness gate are unit-testable on
// the JVM -- they are the two rules that decide what the driver actually sees (docs/_digest/46 §3, G5).
// ---------------------------------------------------------------------------------------------

/**
 * The arrow-id family the cluster paints white and centred: the car's own factory navigation's.
 *
 * These are *not* the `ARROW_ICON_NAVI_*` ids (1-45) the working reference implementation used. The
 * factory navigation app (`com.baidu.naviauto`, pulled from the car and decompiled) drives the very same
 * API object we do -- `DimInteraction.create(context).getNaviInteraction()`, then
 * `updateTurnByTurnArrow(arrowId)` -- and the only difference is the family it passes: its
 * `NaviDashboardManager` (`com.baidu.a.a`, static map at lines 30-87) uses 101-143. The cluster renders
 * the two families differently: EasyPlay's family came out **yellow and offset to the left**, the
 * factory's is **white and centred** (driver report, then confirmed in that bytecode; docs/_digest/46 §10).
 *
 * The names below are the factory's own icon names, translated one to one; the number is the wire id.
 * Only the ids this bridge can produce are declared -- [clusterArrowId] names the factory ids that no
 * Apple maneuver reaches, and the bench override is how those get tried.
 */

/** `turn_start`: the icon the factory sends when a route has just started. */
private const val ARROW_TURN_START = 101

/** `turn_left` (the factory also uses it for the two- and three-branch left turns). */
private const val ARROW_TURN_LEFT = 102

/** `turn_right` (likewise for the two- and three-branch right turns). */
private const val ARROW_TURN_RIGHT = 103

/** `turn_left_front` / `turn_left_side` / `turn_branch_left`: keeping or bearing left. */
private const val ARROW_BRANCH_LEFT = 104

/** `turn_right_front` / `turn_right_side` / `turn_branch_right`: keeping or bearing right. */
private const val ARROW_BRANCH_RIGHT = 105

/** `turn_left_back` / `turn_right_back`, chosen by driving side; `turn_back` is the generic u-turn. */
private const val ARROW_TURN_BACK_LEFT = 106
private const val ARROW_TURN_BACK_RIGHT = 107
private const val ARROW_TURN_BACK = 108

/** `turn_front` / `turn_left_side_main` / `turn_branch_center`: straight on. */
private const val ARROW_STRAIGHT = 109

/** `turn_ring`: entering or following the roundabout. */
private const val ARROW_ROUND = 111

/** `turn_ring_out` / `turn_ring_front`: leaving the roundabout. */
private const val ARROW_ROUND_EXIT = 112

/** `turn_dest`: arrival at the destination. */
private const val ARROW_DESTINATION = 115

/** `turn_ring_left*` / `turn_ring_right*`: the ring taken from the left / from the right. */
private const val ARROW_ROUND_LEFT = 141
private const val ARROW_ROUND_RIGHT = 142

/** `turn_ring_back`: the ring taken back the way the driver came. */
private const val ARROW_ROUND_BACK = 143

/** The text the reference implementation shows while the iPhone guides to the route's start. */
private const val ROAD_TO_ROUTE_START = "前往路线"

/** The route states the cluster may draw, copied from the working reference (docs/_digest/46 §3). */
private val DISPLAYABLE_ROUTE_STATES = setOf(1, 5, 6)

/**
 * The route states that mean guidance is over, so a card must not linger: a route state of `null`, Apple's
 * NoRouteSet (0) and the vendor's `STATE_REACHED` (2).
 *
 * `null` is the shape an ended route actually has in a snapshot, and it is the common one:
 * [com.shilapi.xcertplay.hud.RouteGuidanceState] treats NoRouteSet (0) and Arrived (2) as the two states
 * that end a route, clears itself on both, and a cleared route is published as `routeState == null` (see
 * `CarPlayGlance.Snapshot`). A wired disconnect does the same. The two numbers stay in the set anyway,
 * because they are the states themselves -- the vendor's own name for arrival is `STATE_REACHED`
 * (docs/_digest/46 §2/§4) -- and a snapshot that carries either directly must clear too.
 *
 * `STATE_END` (3) is deliberately *not* here, although doc 46 §2/§4 gives the vendor's `turnState` that
 * value: the state byte we read back is Apple's, not the vendor's, and on the car it is a re-route marker
 * rather than an end. Both recorded sessions walk 5 -> 3 -> 1 around every recalculation
 * (15:16:30.841 state 5, 15:16:31.941 state 3, 15:16:32.411 state 1, with the new maneuver list still
 * arriving in between), and 3 also opened the very first route of a session (15:15:19.811, before the
 * state 6 that submitted the first card). Clearing on 3 would re-create the blink this window exists to
 * remove, one frame early. The vendor's `STATE_*` numbering is what we *send*
 * (`notifyTurnByTurnStopped()` is its `turnState=3`); it is not what we receive.
 */
private val TERMINAL_ROUTE_STATES = setOf(0, 2)

/**
 * The largest remaining-time value the vendor's `secondToTime` renders sanely.
 *
 * It converts to minutes with `(second + 30) / 60` and then to hours and days, so a value at or above
 * this bound is not a duration at all -- an absolute arrival epoch lands there, which is exactly the
 * mistake that made the cluster show a nonsense time (docs/_digest/46 G3).
 */
private const val MAX_ETA_SECONDS = 2_147_483_618L

/** The largest distance the vendor's `revertDistance` still divides into a sane kilometre figure. */
private const val MAX_DISTANCE_METERS = Int.MAX_VALUE.toLong()

/** `ItemsBuilder.commonString` sends UTF-8 bytes; the reference keeps a road name within 120 of them. */
private const val MAX_ROAD_BYTES = 120

/**
 * One navigation card, complete enough that every slot the cluster draws carries a real measurement.
 *
 * The units are the vendor's, not ours: metres as the integers `revertDistance` parses, and remaining
 * seconds as the integer `secondToTime` counts down from. Nothing here is derived from a clock.
 */
internal data class ClusterGuidanceCard(
    val arrowId: Int,
    val road: String,
    val distanceToNextGuidancePointMeters: Int,
    val distanceToDestinationMeters: Int,
    val remainingSeconds: Int,
)

/**
 * The legal navigation calls one card consists of, plus the clear.
 *
 * Deliberately not the vendor interface itself: the unit tests cannot hand a fake to a reflective
 * object, and the *sequence* -- five setters and a stop, with no `notifyTurnByTurnStarted` -- is what
 * has to be pinned down. The service throws `RuntimeException("Navigation state is not correct.")` for
 * the state that method submits (docs/_digest/46 G4), and a card is not something that needs starting:
 * the first `turnState=1` setter is what makes it appear.
 */
internal interface ClusterGuidanceSink {
    fun updateTurnByTurnArrow(arrowId: Int)

    fun updateNextGuidancePointName(road: String)

    fun updateDistanceToNextGuidancePoint(meters: Int)

    fun updateDistanceToDestination(meters: Int)

    fun updateETA(seconds: Int)

    fun notifyTurnByTurnStopped()
}

/** Submits [card], in the order the vendor's own wrapper submits the fields behind them. */
internal fun submitClusterCard(sink: ClusterGuidanceSink, card: ClusterGuidanceCard) {
    sink.updateTurnByTurnArrow(card.arrowId)
    sink.updateNextGuidancePointName(card.road)
    sink.updateDistanceToNextGuidancePoint(card.distanceToNextGuidancePointMeters)
    sink.updateDistanceToDestination(card.distanceToDestinationMeters)
    sink.updateETA(card.remainingSeconds)
}

/** Clears the card (`turnState=3`), the only legal way to take one off the cluster again. */
internal fun clearClusterCard(sink: ClusterGuidanceSink) {
    sink.notifyTurnByTurnStopped()
}

/**
 * True when [routeState] says guidance is over rather than merely incomplete ([TERMINAL_ROUTE_STATES]).
 *
 * The distinction decides whether the card on the cluster goes now or waits out
 * [CLUSTER_CARD_HOLD_MILLIS]: an unbuildable frame during a re-route is a gap the driver should not see,
 * while an ended route is a card that must not be left behind.
 */
internal fun clusterGuidanceEnded(routeState: Int?): Boolean =
    routeState == null || routeState in TERMINAL_ROUTE_STATES

/**
 * How long a card we own survives a guidance snapshot that cannot fill one: the reference's own window.
 *
 * The reference hides a card whose maneuver list has been empty for `EMPTY_LIST_HIDE_NS = 3 s` and treats
 * a route as stale after `STALE_ROUTE_NS = 30 s` (docs/_digest/46 §3 and G5, which also records that G5
 * asks for exactly this grace: "参考用 3 s 宽限 + 30 s 过期"). We ported the completeness gate but not the
 * grace, so our card blinked on every recalculation: the iPhone could not fill a card for 1.6 s on the car
 * (cleared 15:16:30.851, re-submitted 15:16:32.431) because the route state had moved on to 5 while the
 * active maneuver was still the old "drive to the route" one, which this arrow family cannot draw.
 *
 * 3 s covers that 1.6 s gap with room for a slower recalculation, while staying an order of magnitude
 * below the 30 s stale window, so a snapshot that never becomes displayable again cannot leave a wrong
 * card on the cluster for long. It is the reference's number, not a new one: matching it keeps our card's
 * behaviour identical to the working implementation's for the same iPhone behaviour. A route that really
 * ended does not wait for it at all ([clusterGuidanceEnded]).
 *
 * What is held is the last card *already submitted* -- its arrow, road, distances and ETA as of then --
 * never a partially filled one, because a half-filled card makes the cluster paint its own placeholders
 * (G5). Nothing is re-submitted from here: the card the cluster keeps drawing is the one it already has.
 */
internal const val CLUSTER_CARD_HOLD_MILLIS = 3_000L

/** What the cluster should do with our card after one frame ([ClusterCardHoldGate]). */
internal enum class ClusterCardAction {
    /** The frame cannot fill a card, but the last one is young enough to keep: leave it on the cluster. */
    HOLD,

    /** Take our card off the cluster with `notifyTurnByTurnStopped()`. */
    CLEAR,
}

/** The verdict for one unfillable frame, and whether that frame is worth a log line. */
internal data class ClusterCardHold(
    val action: ClusterCardAction,
    /** The one line this frame logs, or null when it repeats the previous verdict and stays out of logcat. */
    val logLine: String?,
)

/**
 * Decides what an unfillable guidance frame does to the card already on the cluster (G5's missing grace).
 *
 * A pure state machine over an injected clock: `nowMillis` is an argument and the window is a constructor
 * parameter, so the hold, the expiry and the terminal clear are unit-tested without a timer, a vendor
 * object or a car -- the same shape as [ClusterPushSchedule] and [ClusterMediaPushGate]. The bridge owns
 * the two facts the rule needs (`clusterGuidanceEnded(snapshot.routeState)` and `elapsedNow()`), which is
 * what keeps this testable on the JVM.
 *
 * The window starts with the first unfillable frame and expires [windowMillis] later; a submitted card
 * restarts it ([cardSubmitted]), because the clock that matters is the age of the *current* card, not the
 * age of the first gap. Nothing else resets it -- in particular the media path never touches this object,
 * so a cover or a track change cannot extend or shorten a navigation hold.
 */
internal class ClusterCardHoldGate(private val windowMillis: Long = CLUSTER_CARD_HOLD_MILLIS) {
    /** When the current run of unfillable frames began, or null while no run is open. */
    private var incompleteSinceMillis: Long? = null

    /** The whole second of the hold already reported, so a held card logs once per second, not per frame. */
    private var reportedSecond = -1L

    /** A complete card reached the cluster: the window restarts with the next unfillable frame. */
    @Synchronized
    fun cardSubmitted() {
        incompleteSinceMillis = null
        reportedSecond = -1L
    }

    /**
     * The verdict for a frame that cannot fill a card at [nowMillis].
     *
     * [guidanceEnded] comes from [clusterGuidanceEnded] and wins over the window: a route that is over
     * clears immediately, however young the hold is. Otherwise the first frame opens the window and is
     * held, every frame inside it is held, and the frame that reaches [windowMillis] clears. The log line
     * follows the same rule as [ClusterArrowChangeGate]: the frame that opens a hold and each new whole
     * second of it report, so `logcat` shows a held card versus a cleared one instead of one line per
     * guidance frame.
     */
    @Synchronized
    fun cardMissing(guidanceEnded: Boolean, nowMillis: Long): ClusterCardHold {
        if (guidanceEnded) {
            incompleteSinceMillis = null
            reportedSecond = -1L
            return ClusterCardHold(ClusterCardAction.CLEAR, clusterCardLogLine(ClusterCardAction.CLEAR, null))
        }
        val since = incompleteSinceMillis
        if (since == null) {
            incompleteSinceMillis = nowMillis
            reportedSecond = 0L
            return ClusterCardHold(ClusterCardAction.HOLD, clusterCardLogLine(ClusterCardAction.HOLD, 0L))
        }
        val elapsed = nowMillis - since
        if (elapsed >= windowMillis) {
            incompleteSinceMillis = null
            reportedSecond = -1L
            return ClusterCardHold(ClusterCardAction.CLEAR, clusterCardLogLine(ClusterCardAction.CLEAR, elapsed))
        }
        val second = elapsed / 1_000L
        if (second == reportedSecond) return ClusterCardHold(ClusterCardAction.HOLD, null)
        reportedSecond = second
        return ClusterCardHold(ClusterCardAction.HOLD, clusterCardLogLine(ClusterCardAction.HOLD, elapsed))
    }
}

/**
 * The one log line a hold decision produces, or null when that frame has nothing to report.
 *
 * The verb is what an on-car `logcat | grep "cluster card"` sorts on: `held` says the incompleteness was
 * still young enough to keep the card (the blink that did *not* happen), `cleared` says the card went away
 * and why -- past [CLUSTER_CARD_HOLD_MILLIS] (`incomplete 3.4s`) or because the route itself is over
 * (`guidance ended`). The elapsed time is built without a `Locale`, so the car's log reads exactly like
 * the unit test's.
 */
internal fun clusterCardLogLine(action: ClusterCardAction, elapsedMillis: Long?): String? = when (action) {
    ClusterCardAction.HOLD ->
        "cluster card held (incomplete ${incompleteSeconds(elapsedMillis ?: 0L)})"
    ClusterCardAction.CLEAR -> when (elapsedMillis) {
        null -> "cluster card cleared (guidance ended)"
        else -> "cluster card cleared (incomplete ${incompleteSeconds(elapsedMillis)})"
    }
}

/** `1.2s`: the elapsed hold in tenths, as one `String` concatenation so the format cannot drift. */
private fun incompleteSeconds(millis: Long): String = "${millis / 1000}.${(millis % 1000) / 100}s"

/**
 * Apple's maneuver type as one of the factory family's icon ids, or null when the family has none.
 *
 * Only the id family changed here; the semantics are the ones we already implemented, which are the
 * working reference's (docs/_digest/46 §3) and read value for value as Apple's own
 * `RouteGuidanceManeuverType` (`CPManeuverType`): 1/20/47 are the left turns, 13/22/49/52 the
 * keep/slight/branch left group, 6 the roundabout, 7 and 28..46 leaving it, 10/12/24/25/27 arrival,
 * 15/16/17 the ferries, 4/18/26 the u-turns.
 *
 * Four decisions are worth spelling out, because the factory's vocabulary is richer than Apple's:
 *  - the u-turn types branch on [drivingSide] -- 106 in left-hand traffic, 107 in right-hand -- and fall
 *    back to the family's generic u-turn 108 for any other side. 108 asks for no direction, so it is the
 *    one value that cannot send the driver the wrong way round, which is why an unknown side no longer
 *    clears the card;
 *  - Apple's "u-turn at the roundabout" (19) is the ring taken back the way the driver came, i.e. the
 *    factory's `turn_ring_*back` group, and follows the same side branches: 141 / 142 / 143. The old
 *    table sent it to the plain roundabout glyph, which loses the fact that the driver comes back;
 *  - the family has **no ferry glyph**, so the ferry maneuvers (15/16/17) take the straight-on 109: a
 *    crossing continues ahead, and refusing the card would hide the road, distance and ETA that stay
 *    true for the whole crossing (a straight arrow can only be wrong about which way to turn, which is
 *    not what a ferry leg asks);
 *  - `turn_via_*` (110, a waypoint) and `turn_tollgate` (114) exist in the factory's vocabulary but have
 *    no Apple maneuver type at all, so nothing maps to them; `turn_along` (120, "continue along the
 *    road") is the closer glyph for Apple's follow-road (5) but was left out to keep the translation of
 *    the old table row for row. All three are reachable through the bench override below, which is
 *    exactly how they should be tried on the car.
 *
 * Null is a *decision*, not a gap: an unknown type clears the card, because the alternative -- a
 * straight-ahead arrow -- tells the driver to keep going when the iPhone said something we do not
 * understand.
 *
 * [proceedToRoute] is the factory's `turn_start` (101), no longer a hidden slot: 101 is exactly what the
 * factory navigation sends when a route starts, and the slot then carries the same start icon as the
 * car's own navigation. (`-1` still means "hide this icon slot" -- `commonIcon(-1)`, docs/_digest/46
 * §1.3(d) -- and the bench override can still send it.)
 */
internal fun clusterArrowId(type: Int?, drivingSide: Int, proceedToRoute: Boolean): Int? {
    if (proceedToRoute) return ARROW_TURN_START
    val maneuver = type ?: return null
    return when (maneuver) {
        1, 20, 47 -> ARROW_TURN_LEFT
        2, 21, 48 -> ARROW_TURN_RIGHT
        3, 5 -> ARROW_STRAIGHT
        4, 18, 26 -> when (drivingSide) {
            0 -> ARROW_TURN_BACK_LEFT
            1 -> ARROW_TURN_BACK_RIGHT
            else -> ARROW_TURN_BACK
        }
        6 -> ARROW_ROUND
        7, in 28..46 -> ARROW_ROUND_EXIT
        10, 12, 24, 25, 27 -> ARROW_DESTINATION
        13, 22, 49, 52 -> ARROW_BRANCH_LEFT
        14, 23, 50, 53 -> ARROW_BRANCH_RIGHT
        15, 16, 17 -> ARROW_STRAIGHT
        19 -> when (drivingSide) {
            0 -> ARROW_ROUND_LEFT
            1 -> ARROW_ROUND_RIGHT
            else -> ARROW_ROUND_BACK
        }
        // 8, 9, 11, 51, everything above 53 and whatever Apple adds later: no icon, so no card.
        else -> null
    }
}

/**
 * The arrow id `debug.diplay.arrow` forces, or null when it should not force anything.
 *
 * An unset, empty, whitespace-only or unparseable value is "use the mapping", so a typo during a bench
 * session changes nothing on the road. Any integer is accepted, deliberately out of range included:
 * the point of the override is to reach ids the mapping never produces (110, 114, 120) and `-1`, which
 * hides the icon slot. Read through [clusterArrowProperty], i.e. reflectively, never through
 * `android.os.SystemProperties`, which is hidden API.
 */
internal fun parseArrowOverride(propertyValue: String?): Int? = propertyValue?.trim()?.toIntOrNull()

/**
 * A road name the cluster can take: ISO control characters dropped, then cut to [MAX_ROAD_BYTES] UTF-8
 * bytes, then trimmed.
 *
 * The byte budget is the reference's rule and the vendor sends `value.getBytes()`, so a name over it
 * would be truncated by somebody further down -- possibly in the middle of a character. A character
 * that does not fit whole is therefore dropped instead of split, and the trim happens after the cut
 * because the cut can expose trailing whitespace.
 */
internal fun cleanClusterRoad(value: String): String {
    val result = StringBuilder()
    var bytes = 0
    var offset = 0
    while (offset < value.length) {
        val codePoint = value.codePointAt(offset)
        offset += Character.charCount(codePoint)
        if (Character.isISOControl(codePoint)) continue
        val text = String(Character.toChars(codePoint))
        val size = text.toByteArray(Charsets.UTF_8).size
        if (bytes + size > MAX_ROAD_BYTES) break
        result.append(text)
        bytes += size
    }
    return result.toString().trim()
}

/**
 * The road text for a card: `afterRoad` → `road` → `currentRoad`, cleaned at every step.
 *
 * The iPhone sends the road after the turn (0x5202 type 4) and the road the driver is on now
 * (0x5201 type 3) separately, and either can be empty or clean away to nothing; the reference walks
 * the chain rather than showing a blank slot. When it runs out while the iPhone is still routing the
 * driver to the start, the card names that instead, which is the only case where a road is invented.
 */
internal fun clusterRoad(snapshot: CarPlayGlance.Snapshot, proceedToRoute: Boolean): String {
    for (candidate in listOf(snapshot.afterRoad, snapshot.road, snapshot.currentRoad)) {
        val cleaned = cleanClusterRoad(candidate)
        if (cleaned.isNotEmpty()) return cleaned
    }
    return if (proceedToRoute) ROAD_TO_ROUTE_START else ""
}

/**
 * The card this frame justifies, or null when the honest answer is "no card".
 *
 * Every slot is checked, in the order the reference checks them (docs/_digest/46 §3, G5), and the first
 * one that cannot be filled abandons the whole card: a route state outside [DISPLAYABLE_ROUTE_STATES],
 * no maneuver, an unmapped maneuver type, a negative distance, a destination distance outside
 * `[0, 2^31)`, or a remaining time outside `[0, 2147483618)`. The cluster draws its own placeholder
 * (`0 m`, `<1min`) for a slot it was not given, so a half-filled card is worse than none: it looks like
 * a measurement. Missing values are therefore never rounded down to zero -- they refuse the card.
 *
 * [forcedArrowId] is the bench override of [parseArrowOverride] and wins over the mapping *including*
 * when the mapping has no answer: sweeping the cluster's vocabulary means driving ids that no Apple
 * maneuver produces, and that is precisely the case the mapping refuses. Nothing else about the gate
 * changes, so an override still needs a displayable route state, a distance and an ETA.
 */
internal fun clusterGuidanceCard(
    snapshot: CarPlayGlance.Snapshot,
    forcedArrowId: Int? = null,
): ClusterGuidanceCard? {
    val state = snapshot.routeState ?: return null
    if (state !in DISPLAYABLE_ROUTE_STATES) return null
    val mapped = clusterArrowId(snapshot.maneuverType, snapshot.drivingSide, snapshot.proceedToRoute)
    val arrow = forcedArrowId ?: mapped ?: return null
    if (snapshot.distanceMeters < 0) return null
    val remainingMeters = snapshot.remainingMeters?.takeIf { it in 0..MAX_DISTANCE_METERS } ?: return null
    val remainingSeconds = snapshot.remainingSeconds?.takeIf { it in 0 until MAX_ETA_SECONDS } ?: return null
    return ClusterGuidanceCard(
        arrowId = arrow,
        road = clusterRoad(snapshot, snapshot.proceedToRoute),
        distanceToNextGuidancePointMeters = snapshot.distanceMeters,
        distanceToDestinationMeters = remainingMeters.toInt(),
        // Remaining seconds, never `arrivalEpochSeconds`: the vendor renders this through `secondToTime`.
        remainingSeconds = remainingSeconds.toInt(),
    )
}
