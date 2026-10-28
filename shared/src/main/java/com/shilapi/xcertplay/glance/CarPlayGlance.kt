package com.shilapi.xcertplay.glance

import android.util.Log
import com.shilapi.xcertplay.hud.ClusterCall
import com.shilapi.xcertplay.hud.ClusterCallState
import com.shilapi.xcertplay.hud.ClusterSongState
import com.shilapi.xcertplay.hud.RouteGuidanceState
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import com.shilapi.xcertplay.compat.WorkerThreads

/** The thread the instrument-cluster push runs on; it is not the iAP2 control thread. */
internal const val CLUSTER_PUSH_THREAD = "diplay-cluster-push"

/**
 * What CarPlay is doing, at a glance, for widgets and other screens outside CarPlay: the next
 * maneuver from iAP2 route guidance (0x5201/0x5202) and the song from NowPlayingUpdate (0x5001).
 * Fed by every CarPlay session.
 */
object CarPlayGlance {
    /** One state of the glance; [maneuverType] is Apple's RouteGuidanceManeuverType, null without a route. */
    data class Snapshot(
        val connected: Boolean = false,
        val maneuverType: Int? = null,
        val drivingSide: Int = 0,
        val distanceMeters: Int = 0,
        val road: String = "",
        /**
         * The road after the turn (0x5202 type 4), as sent; [road] is that with the current road as its
         * fallback. The cluster takes them one step at a time instead (see `clusterRoad`), so both are
         * kept rather than only the merged value.
         */
        val afterRoad: String = "",
        /** The road the route update named as current (0x5201 type 3), the fallback chain's last step. */
        val currentRoad: String = "",
        /**
         * The route-guidance state byte (0x5201 type 1). The cluster only draws the states the working
         * reference whitelists (1, 5, 6); other states mean there is nothing worth showing yet.
         */
        val routeState: Int? = null,
        val arrivalEpochSeconds: Long? = null,
        val remainingSeconds: Long? = null,
        val remainingMeters: Long? = null,
    /** True while the iPhone guides the driver to the route's start (see RouteGuidanceState). */
    val proceedToRoute: Boolean = false,
        val song: String? = null,
        val playing: Boolean = false,
    )

    private val route = RouteGuidanceState()
    private val song = ClusterSongState()

    /**
     * The iPhone's call, from iAP2 CallStateUpdate (0x4155); its own frame family, so it is kept out of
     * [Snapshot]: the glance snapshot feeds a widget and the guidance card, while a call is a separate
     * card on the cluster with its own submit path ([callSubmit]).
     */
    private val call = ClusterCallState()

    /**
     * A voice call the iPhone never announced, inferred from its audio stream by
     * `com.shilapi.xcertplay.media.VoiceCallWatch`; null when there is none.
     *
     * It is kept beside [call] rather than inside it because the two disagree on purpose: the iAP2 state
     * names the caller and disappears when the phone says the call is over, while this one only knows
     * that a two-way audio stream is up. [publishCallLocked] hands over whichever one the cluster should
     * show, and the iPhone's own state always wins -- it is the only one that can say who is calling.
     */
    private var voiceCall: ClusterCall? = null

    /** Whatever [publishCallLocked] last handed to the cluster, so a repeated card costs no push. */
    private var lastCall: ClusterCall? = null

    /** True once [lastCall] -- including its null -- has actually been submitted. */
    private var callSubmitted = false

    private var connected = false
    private var last = Snapshot()

    /** Called with each new snapshot, on the thread that changed it. */
    @Volatile var listener: ((Snapshot) -> Unit)? = null

    /**
     * The single thread every cluster push runs on, created on first use.
     *
     * One snapshot becomes up to six *synchronous* binder transactions into the vendor's DIM service,
     * so running them inline would let a busy or wedged service stall the caller -- the iAP2 control
     * thread, which also has CarPlay frames to handle. A dedicated daemon thread serialises the pushes
     * and keeps that stall off the frame path, the way the working reference implementation does. It is
     * a daemon so a finished session (or a unit test) is never held open by a push thread.
     */
    private val clusterWorker: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        WorkerThreads.create(runnable, CLUSTER_PUSH_THREAD).apply { isDaemon = true }
    }

    /**
     * What one published snapshot does to the instrument cluster, on [clusterWorker].
     *
     * Injectable so a unit test can watch the hand-off itself -- which thread runs it, and whether the
     * caller's monitor is still held -- without a head unit. The default routes to
     * `VendorClusterBridge.pushGuidance`, which is inert unless the cluster push is switched on.
     */
    @Volatile
    internal var clusterSubmit: (Snapshot) -> Unit = { snapshot ->
        runCatching { com.shilapi.xcertplay.media.VendorClusterBridge.pushGuidance(snapshot) }
    }

    /**
     * What one call change does to the instrument cluster, on [clusterWorker].
     *
     * A separate hand-off from [clusterSubmit] because a call is not part of [Snapshot]: the vendor's
     * phone card is driven by its own push (call status, caller, then the cluster draws its own
     * answer/end buttons), and it must keep working while no route is being guided. Null means "no call",
     * which submits the vendor's idle status so a card left over from a finished call is cleared.
     */
    @Volatile
    internal var callSubmit: (ClusterCall?) -> Unit = { call ->
        runCatching { com.shilapi.xcertplay.media.VendorClusterBridge.pushCall(call) }
    }

    /** Refresh time-dependent guidance even when no new metadata frame has arrived. */
    fun snapshot(): Snapshot {
        val (changed, current) = synchronized(this) { publishLocked() to last }
        changed?.let { listener?.invoke(it) }
        return current
    }

    fun onFrame(frame: Iap2Frame) {
        val changed = synchronized(this) {
            when (frame.messageId) {
                RouteGuidanceState.ROUTE_GUIDANCE_UPDATE, RouteGuidanceState.ROUTE_GUIDANCE_MANEUVER_UPDATE ->
                    runCatching { route.accept(frame.messageId, frame.payload) }
                ClusterSongState.NOW_PLAYING_UPDATE -> runCatching { song.accept(frame) }
                ClusterCallState.CALL_STATE_UPDATE -> {
                    // Enqueued under the monitor, executed on the worker: the vendor push is a binder
                    // transaction and this is the iAP2 control thread (docs/_digest/46 G9).
                    if (runCatching { call.accept(frame) }.getOrDefault(false)) publishCallLocked()
                    return
                }
                else -> return
            }
            publishLocked()
        }
        changed?.let { listener?.invoke(it) }
    }

    fun setConnected(next: Boolean) {
        val changed = synchronized(this) {
            if (connected == next) return
            connected = next
            if (!next) {
                route.clear()
                song.clear()
                // A call cannot outlive the session: submit the idle status so the cluster's phone card
                // is cleared rather than left showing a call nobody can answer any more.
                val hadCall = lastCall != null
                call.clear()
                voiceCall = null
                lastCall = null
                callSubmitted = false
                if (hadCall) submitCall(null)
            }
            publishLocked()
        }
        changed?.let { listener?.invoke(it) }
    }

    /**
     * A voice call became audible -- or stopped being -- according to
     * `com.shilapi.xcertplay.media.VoiceCallWatch`, which infers it from the CarPlay audio stream
     * because the iPhone never reports a third-party VOIP call over iAP2.
     *
     * Called from the session's audio threads. [confirmedAtMillis] is when the call was confirmed (the
     * two-way stream first existed), not when this call was made; it becomes the card's call timer, which
     * the vendor ticks itself.
     *
     * A stream that starts while the iPhone is *already* reporting a call is that call's audio, not a
     * second call: the iPhone keeps the stream up for a moment after the call ends, and synthesizing a
     * card for it would rename the caller to [VOICE_CALL_NAME] for that moment, right in front of the
     * driver.
     */
    fun onVoiceCall(active: Boolean, confirmedAtMillis: Long) {
        synchronized(this) {
            if (active) {
                if (voiceCall != null) return
                if (call.current() != null) {
                    Log.i(TAG, "voice call stream belongs to the call state; no second card")
                    return
                }
                voiceCall = ClusterCall(ClusterCall.Phase.ACTIVE, VOICE_CALL_NAME, "", confirmedAtMillis)
                Log.i(TAG, "voice call card on: $VOICE_CALL_NAME")
            } else {
                if (voiceCall == null) return
                voiceCall = null
                Log.i(TAG, "voice call card off")
            }
            publishCallLocked()
        }
    }

    /** True while a VOIP call is being shown; the cluster key handler logs it with the key it relays. */
    fun voiceCallActive(): Boolean = synchronized(this) { voiceCall != null }

    /**
     * Hands the cluster the card it should be showing: the iPhone's own call state when there is one,
     * otherwise the inferred voice call.
     *
     * The comparison is what keeps the two sources from fighting. iAP2 repeats its call state on its own
     * schedule and the vendor push costs a binder transaction each time, so an unchanged card is not
     * pushed again -- the same rule the guidance card follows.
     */
    private fun publishCallLocked() {
        val next = call.current() ?: voiceCall
        if (callSubmitted && next == lastCall) return
        lastCall = next
        callSubmitted = true
        submitCall(next)
    }

    private fun publishLocked(): Snapshot? {
        val maneuver = route.currentApple()
        val current = song.current()
        val next = Snapshot(
            connected = connected,
            maneuverType = maneuver?.type,
            drivingSide = maneuver?.drivingSide ?: 0,
            distanceMeters = maneuver?.distanceMeters ?: 0,
            road = maneuver?.road.orEmpty(),
            afterRoad = maneuver?.afterRoad.orEmpty(),
            currentRoad = maneuver?.currentRoad.orEmpty(),
            routeState = maneuver?.routeState,
            arrivalEpochSeconds = maneuver?.arrivalEpochSeconds,
            remainingSeconds = maneuver?.remainingSeconds,
            remainingMeters = maneuver?.remainingMeters,
            proceedToRoute = maneuver?.proceedToRoute == true,
            song = current?.text,
            playing = current?.playing ?: false,
        )
        if (next == last) return null
        last = next
        // Mirror guidance onto the instrument cluster (inert unless the cluster switch is on). Only the
        // hand-off happens here: `submitToCluster` enqueues, so no binder transaction runs while this
        // object's monitor is held or on the caller's thread (docs/_digest/46 G9).
        submitToCluster(next)
        return next
    }

    /**
     * Hands [snapshot] to [clusterWorker] without waiting for it.
     *
     * Called with this object's monitor held, which is exactly why it only enqueues: the enqueue is a
     * queue append, while everything the vendor service does happens afterwards, on the worker thread.
     * A failure there is the bridge's to report; it must not travel back into frame handling.
     */
    private fun submitToCluster(snapshot: Snapshot) {
        val submit = clusterSubmit
        clusterWorker.execute { runCatching { submit(snapshot) } }
    }

    /**
     * Hands one call change to [clusterWorker] without waiting for it; the same rule as
     * [submitToCluster], and for the same reason: it is called with this object's monitor held.
     */
    private fun submitCall(call: ClusterCall?) {
        val submit = callSubmit
        clusterWorker.execute { runCatching { submit(call) } }
    }

    private const val TAG = "DiPlay-Cluster"

    /**
     * What the cluster calls a caller it cannot name: the iPhone tells us a third-party VOIP call exists
     * only through its audio, so the app and the person on the other end are both unknown.
     *
     * The card's own state label ("Ongoing Call") and its hang-up button belong to the vendor service and
     * are not ours to choose (docs/_digest/86 §2), which is why this is the name and not a replacement
     * for them.
     */
    internal const val VOICE_CALL_NAME = "语音通话"
}
