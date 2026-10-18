package com.shilapi.xcertplay.glance

import com.shilapi.xcertplay.hud.ClusterCall
import com.shilapi.xcertplay.hud.ClusterCallState
import com.shilapi.xcertplay.hud.ClusterSongState
import com.shilapi.xcertplay.hud.RouteGuidanceState
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

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
        Thread(runnable, CLUSTER_PUSH_THREAD).apply { isDaemon = true }
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
                    if (runCatching { call.accept(frame) }.getOrDefault(false)) submitCall(call.current())
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
                val hadCall = call.current() != null
                call.clear()
                if (hadCall) submitCall(null)
            }
            publishLocked()
        }
        changed?.let { listener?.invoke(it) }
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
}
