package com.shilapi.xcertplay.media

import android.content.Context
import android.util.Log
import com.shilapi.xcertplay.glance.CarPlayGlance
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/** The adb override for the cluster push; [VendorClusterBridge.enabled] documents its precedence. */
private const val CLUSTER_SWITCH = "debug.diplay.cluster"

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
     */
    private fun call(target: Any, method: String, vararg args: Any?) {
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
            return
        }
        val result = runCatching { match.method.invoke(target, *args) }
            .onFailure { Log.w(TAG, "cluster ${match.signature}(${args.joinToString()}) failed", it) }
        com.shilapi.xcertplay.diag.DiagLog.trace(TAG) {
            "cluster ${match.signature} args=[${args.joinToString()}] -> ${result.getOrNull()}"
        }
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
internal fun clusterSwitchProperty(): String? = runCatching {
    val get = Class.forName("android.os.SystemProperties")
        .getMethod("get", String::class.java, String::class.java)
    get.invoke(null, CLUSTER_SWITCH, "") as? String
}.getOrNull()

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
