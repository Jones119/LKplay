package com.shilapi.xcertplay.media

import android.content.Context
import android.util.Log
import com.shilapi.xcertplay.glance.CarPlayGlance
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * Pushes what CarPlay is playing, and where it is guiding, into the head unit's instrument cluster.
 *
 * The cluster is driven by the vendor's DIM API, which is on the boot classpath already
 * (`/system/framework/ecarx.adapterapi.jar`, the same jar behind our Bluetooth work):
 *
 *   DimInteraction.create(Context) -> getMediaInteraction() : IMediaInteraction
 *                                  -> getNaviInteraction()  : INaviInteraction
 *
 * The exact surface was read out of the pulled jar (docs/_digest/37):
 *   updatePlaybackInfo(IPlaybackInfo) / updateCurrentSourceType(int) / updateCurrentProgress(long)
 *   notifyTurnByTurnStarted/Stopped() / updateTurnByTurnArrow(int) / updateNextGuidancePointName(String)
 *   / updateDistanceToNextGuidancePoint(int) / updateETA(int)
 *
 * `IPlaybackInfo` has no concrete implementation in the jar, but it is a plain Java interface, so a
 * dynamic proxy supplies it. Everything is reflective and defensive: a failure logs and disables the
 * push rather than touching CarPlay, and the whole feature sits behind
 * `adb shell setprop debug.diplay.cluster 1` (default off) until it has been seen working on the car.
 */
object VendorClusterBridge {
    private const val TAG = "DiPlay-Cluster"
    private const val SWITCH = "debug.diplay.cluster"
    private const val DIM = "com.ecarx.xui.adaptapi.diminteraction.DimInteraction"

    private const val SOURCE_TYPE_ONLINE = 6
    private const val PLAYBACK_STATUS_PAUSED = 0
    private const val PLAYBACK_STATUS_PLAYING = 1

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var dim: Any? = null

    @Volatile
    private var media: Any? = null

    @Volatile
    private var navi: Any? = null

    @Volatile
    private var guidanceStarted = false

    /** The artwork the cluster should show, as a file the head unit's own process can read. */
    @Volatile
    private var artworkUri: android.net.Uri? = null

    @Volatile
    private var lastMedia: CarPlayNowPlaying? = null

    /** Called once from the app side; the push stays inert until the switch is on. */
    fun attach(context: Context) {
        appContext = context.applicationContext
    }

    val enabled: Boolean get() = appContext != null && debugSwitch(SWITCH, false)

    fun pushMedia(now: CarPlayNowPlaying) {
        lastMedia = now
        if (!enabled) return
        val context = appContext ?: return
        val target = mediaInteraction(context) ?: return
        val status = if (now.playing) PLAYBACK_STATUS_PLAYING else PLAYBACK_STATUS_PAUSED
        val info = playbackInfo(context, now, status) ?: return
        call(target, "updatePlaybackInfo", info)
        call(target, "updateCurrentSourceType", SOURCE_TYPE_ONLINE)
        now.elapsedMillis?.let { call(target, "updateCurrentProgress", it) }
    }

    /**
     * The album art arrives from the iPhone as a Bitmap, and the cluster takes it as a Uri
     * (`IPlaybackInfo.getArtwork`). The head unit's own process has to be able to read it, so it is
     * written into this app's external files directory — world readable on this Android version, and
     * already how DiagnosticExportStore hands files to other apps. Each update gets a fresh file name
     * so a consumer caching by Uri cannot keep showing the previous cover.
     */
    fun setArtwork(bitmap: android.graphics.Bitmap?) {
        val context = appContext ?: return
        if (bitmap == null) {
            artworkUri = null
        } else {
            val written = runCatching {
                val directory = context.getExternalFilesDir(null) ?: return
                val file = java.io.File(directory, "cluster-artwork-${System.currentTimeMillis()}.png")
                java.io.FileOutputStream(file).use { out -> bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out) }
                // The reader is another process: the default 0660 leaves it unreadable for anyone not
                // in this app's group, which is what the car's own service is.
                runCatching { file.setReadable(true, false) }
                directory.listFiles()?.forEach { candidate ->
                    if (candidate.name.startsWith("cluster-artwork-") && candidate != file) runCatching { candidate.delete() }
                }
                android.net.Uri.fromFile(file)
            }.getOrElse {
                Log.w(TAG, "cluster artwork write failed", it)
                null
            }
            artworkUri = written
        }
        Log.i(TAG, "cluster artwork uri=$artworkUri")
        // Re-send so the cluster picks the new cover up with the current track.
        if (enabled) lastMedia?.let { pushMedia(it) }
    }

    /** Supplied by the app side: renders Apple's maneuver type into the cluster's arrow image. */
    @Volatile
    var arrowRenderer: ((type: Int, drivingSide: Int) -> android.graphics.Bitmap?)? = null

    fun pushGuidance(snapshot: CarPlayGlance.Snapshot) {
        if (!enabled) return
        val context = appContext ?: return
        val type = snapshot.maneuverType
        if (type == null) {
            stopGuidance()
            return
        }
        val target = naviInteraction(context) ?: return
        if (!guidanceStarted) {
            guidanceStarted = true
            call(target, "notifyTurnByTurnStarted")
        }
        // The cluster draws the arrow from an image, the way the head unit's own HiCar bridge does
        // (`updateDriveDirectionImage`, docs/_digest/37). The vendor's integer arrow ids are its own
        // unverified set, so the app-supplied bitmap is preferred and the integer stays as a fallback.
        val arrow = runCatching { arrowRenderer?.invoke(type, snapshot.drivingSide) }.getOrNull()
        if (arrow != null) {
            call(target, "updateTurnByTurnArrow", arrow)
        } else {
            call(target, "updateTurnByTurnArrow", type)
        }
        if (snapshot.road.isNotEmpty()) call(target, "updateNextGuidancePointName", snapshot.road)
        if (snapshot.distanceMeters > 0) {
            call(target, "updateDistanceToNextGuidancePoint", snapshot.distanceMeters)
        }
        snapshot.remainingMeters?.let { call(target, "updateDistanceToDestination", it.toInt()) }
        // The cluster's own ETA field reads as an arrival time, not a countdown: a remaining-seconds
        // value rendered as "<1min" on the car, so the absolute arrival is preferred (docs/_digest/37).
        val eta = snapshot.arrivalEpochSeconds ?: snapshot.remainingSeconds
        eta?.let { call(target, "updateETA", it.toInt()) }
    }

    fun stopGuidance() {
        if (!enabled || !guidanceStarted) return
        guidanceStarted = false
        val context = appContext ?: return
        val target = naviInteraction(context) ?: return
        call(target, "notifyTurnByTurnStopped")
    }

    /**
     * The vendor's interaction objects bind asynchronously — our on-car probe got a null
     * `getNaviInteraction()` straight after `create()` — so a miss is retried on the next update
     * instead of being remembered.
     */
    private fun mediaInteraction(context: Context): Any? {
        media?.let { return it }
        val owner = dimInstance(context) ?: return null
        val value = read(owner, "getMediaInteraction") ?: return null
        media = value
        Log.i(TAG, "cluster media interaction=${value.javaClass.name}")
        return value
    }

    private fun naviInteraction(context: Context): Any? {
        navi?.let { return it }
        val owner = dimInstance(context) ?: return null
        val value = read(owner, "getNaviInteraction") ?: return null
        navi = value
        Log.i(TAG, "cluster navi interaction=${value.javaClass.name}")
        return value
    }

    private fun dimInstance(context: Context): Any? {
        dim?.let { return it }
        val type = runCatching { Class.forName(DIM) }.getOrNull() ?: run {
            Log.i(TAG, "cluster dim api not on the classpath ($DIM)")
            return null
        }
        val value = runCatching {
            type.getMethod("create", Context::class.java).invoke(null, context)
        }.getOrNull() ?: return null
        dim = value
        return value
    }

    /** A dynamic proxy for the vendor's plain `IPlaybackInfo` interface. */
    private fun playbackInfo(context: Context, now: CarPlayNowPlaying, status: Int): Any? {
        val type = runCatching {
            Class.forName("com.ecarx.xui.adaptapi.diminteraction.IMediaInteraction\$IPlaybackInfo")
        }.getOrNull() ?: return null
        return runCatching {
            Proxy.newProxyInstance(type.classLoader, arrayOf(type), PlaybackInfoHandler(now, status))
        }.getOrElse {
            Log.w(TAG, "cluster playback info proxy failed", it)
            null
        }
    }

    private class PlaybackInfoHandler(
        private val now: CarPlayNowPlaying,
        private val status: Int,
    ) : InvocationHandler {
        override fun invoke(proxy: Any?, method: Method, args: Array<out Any?>?): Any? = when (method.name) {
            "getTitle" -> now.title
            "getArtist" -> now.artist
            "getAlbum" -> now.album
            "getDuration" -> now.durationMillis ?: 0L
            "getPlaybackStatus" -> status
            "getSourceType" -> SOURCE_TYPE_ONLINE
            "getArtwork" -> artworkUri
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

    private fun read(target: Any, method: String): Any? =
        runCatching { target.javaClass.getMethod(method).invoke(target) }
            .onFailure { Log.w(TAG, "cluster $method failed", it) }
            .getOrNull()

    private fun call(target: Any, method: String, vararg args: Any?) {
        val result = runCatching {
            val types = args.map { it?.javaClass }
            val found = target.javaClass.methods.firstOrNull { candidate ->
                candidate.name == method && candidate.parameterCount == args.size &&
                    candidate.parameterTypes.withIndex().all { (index, parameter) ->
                        types[index] == null || parameter.isAssignableFrom(types[index])
                    }
            } ?: return@runCatching null
            found.invoke(target, *args)
        }.onFailure { Log.w(TAG, "cluster $method(${args.joinToString()}) failed", it) }
        com.shilapi.xcertplay.diag.DiagLog.trace(TAG) {
            "cluster $method(${args.joinToString()}) -> ${result.getOrNull()}"
        }
    }
}
