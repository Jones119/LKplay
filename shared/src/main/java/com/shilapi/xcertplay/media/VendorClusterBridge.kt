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
 * The exact surface was read out of the pulled jar (docs/_digest/37, corrected by docs/_digest/46,
 * which read the car's own binaries): the navigation setters that work are
 *   notifyTurnByTurnStopped() / updateTurnByTurnArrow(int) / updateNextGuidancePointName(String)
 *   / updateDistanceToNextGuidancePoint(int) / updateDistanceToDestination(int) / updateETA(int)
 * plus the media setters `updatePlaybackInfo(IPlaybackInfo)` / `updateCurrentSourceType(int)` /
 * `updateCurrentProgress(long)`.
 *
 * Three of those signatures carry the semantics the cluster actually renders (docs/_digest/46 §1):
 * the arrow is an `ARROW_ICON_NAVI_*` integer id, both distances are integer metres, and the ETA is the
 * remaining time in integer seconds. The two image-shaped members of the surface are decoys:
 * `updateTurnByTurnArrow(Bitmap)` is an empty method in the vendor's implementation, and the image
 * route the head unit's own HiCar bridge uses ends in its own HUD, not in this API -- the head unit
 * has no HUD at all. `notifyTurnByTurnStarted()` is worse than useless: it submits state 0, which the
 * service rejects with `RuntimeException("Navigation state is not correct.")`.
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

    /**
     * True once a complete card of ours reached the cluster, so a later stop clears *our* card only.
     *
     * There is no "guidance started" call any more: it submitted `turnState=0`, which the service
     * rejects, and the card appears with the first legal `turnState=1` submission anyway (docs/_digest/46
     * G4). Ownership simply follows the same event.
     */
    @Volatile
    private var cardOnCluster = false

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

    /**
     * Submits this guidance frame to the cluster, or clears our card when the frame cannot fill one.
     *
     * Only a complete card is sent ([clusterGuidanceCard]): the vendor's template engine marks a missing
     * text slot invisible and the cluster then paints its *own* placeholder for it -- the `0 m` and
     * `<1min` seen on the car (docs/_digest/46 §1.3(c) and G5). Refusing the card makes the display
     * honest: nothing, rather than numbers nobody measured. The bitmap route is gone with the arrow
     * renderer that fed it; every value here is one the vendor documents.
     */
    fun pushGuidance(snapshot: CarPlayGlance.Snapshot) {
        if (!enabled) return
        val context = appContext ?: return
        val card = clusterGuidanceCard(snapshot)
        if (card == null) {
            stopGuidance()
            return
        }
        val target = naviInteraction(context) ?: return
        submitClusterCard(guidanceSink(target), card)
        cardOnCluster = true
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

// ---------------------------------------------------------------------------------------------
// The navigation card: which vendor arrow id, which text, which units, and when to send nothing.
// Extracted from [VendorClusterBridge] so the mapping and the completeness gate are unit-testable on
// the JVM -- they are the two rules that decide what the driver actually sees (docs/_digest/46 §3, G5).
// ---------------------------------------------------------------------------------------------

/** `Turn.ARROW_ICON_NAVI_STRAIGHT`. */
private const val ARROW_STRAIGHT = 1

/** `Turn.ARROW_ICON_NAVI_TURN_BACK_L` / `..._R`, chosen by driving side. */
private const val ARROW_TURN_BACK_LEFT = 2
private const val ARROW_TURN_BACK_RIGHT = 3

/** `Turn.ARROW_ICON_NAVI_TURN_R` / `..._L`. */
private const val ARROW_TURN_RIGHT = 6
private const val ARROW_TURN_LEFT = 7

/** `Turn.ARROW_ICON_NAVI_KEEP_R` / `..._L`. */
private const val ARROW_KEEP_RIGHT = 10
private const val ARROW_KEEP_LEFT = 11

/** `Turn.ARROW_ICON_NAVI_EXIT_R`. */
private const val ARROW_EXIT_RIGHT = 12

/** `Turn.ARROW_ICON_NAVI_ROUND`. */
private const val ARROW_ROUND = 15

/** `Turn.ARROW_ICON_NAVI_DEST`. */
private const val ARROW_DESTINATION = 17

/** `Turn.ARROW_ICON_NAVI_FERRY`. */
private const val ARROW_FERRY = 18

/** `Turn.ARROW_ICON_NAVI_EXIT_L`, which is also `..._DEST_1`: the vendor ships the duplicate value 19. */
private const val ARROW_EXIT_LEFT = 19

/** `Turn.ARROW_ICON_NAVI_THE_ROUND_EXIT`, used for both a roundabout and leaving one. */
private const val ARROW_ROUND_EXIT = 29

/**
 * The value the vendor reads as "hide this icon slot" (`ItemsBuilder.commonIcon(-1)`, docs/_digest/46 §1.3(d)).
 *
 * `0` means the opposite -- a *visible* slot drawn with icon 0 -- so it must never stand in for "no arrow".
 */
private const val ARROW_HIDDEN = -1

/** The text the reference implementation shows while the iPhone guides to the route's start. */
private const val ROAD_TO_ROUTE_START = "前往路线"

/** The route states the cluster may draw, copied from the working reference (docs/_digest/46 §3). */
private val DISPLAYABLE_ROUTE_STATES = setOf(1, 5, 6)

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
 * Apple's maneuver type as one of the vendor's 45 `ARROW_ICON_NAVI_*` ids, or null when there is none.
 *
 * The table is the working reference implementation's (`LegacyLynkNavigationBridge.arrowFor`,
 * docs/_digest/46 §3), which is the only evidence that a given Apple type maps to a given icon.
 * Null is a *decision*, not a gap: an unknown type clears the card, because the alternative -- a
 * straight-ahead arrow -- tells the driver to keep going when the iPhone said something we do not
 * understand. The three u-turn types are the only ones whose icon depends on [drivingSide], and any
 * side other than left-hand (0) or right-hand (1) traffic is unmapped for the same reason.
 *
 * [proceedToRoute] short-circuits all of it with [ARROW_HIDDEN]: being sent to the route's start is
 * not a turn, so the slot is hidden rather than filled with a guess.
 */
internal fun clusterArrowId(type: Int?, drivingSide: Int, proceedToRoute: Boolean): Int? {
    if (proceedToRoute) return ARROW_HIDDEN
    val maneuver = type ?: return null
    return when (maneuver) {
        1, 20, 47, 49 -> ARROW_TURN_LEFT
        2, 21, 48, 50 -> ARROW_TURN_RIGHT
        3, 5 -> ARROW_STRAIGHT
        4, 18, 26 -> when (drivingSide) {
            0 -> ARROW_TURN_BACK_LEFT
            1 -> ARROW_TURN_BACK_RIGHT
            else -> null
        }
        6, 19 -> ARROW_ROUND
        7 -> ARROW_ROUND_EXIT
        in 28..46 -> ARROW_ROUND_EXIT
        10, 12, 24, 25, 27 -> ARROW_DESTINATION
        13, 52 -> ARROW_KEEP_LEFT
        14, 53 -> ARROW_KEEP_RIGHT
        15, 16, 17 -> ARROW_FERRY
        22 -> ARROW_EXIT_LEFT
        23 -> ARROW_EXIT_RIGHT
        // 8, 9, 11, everything above 53 and whatever Apple adds later: no icon, so no card.
        else -> null
    }
}

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
 */
internal fun clusterGuidanceCard(snapshot: CarPlayGlance.Snapshot): ClusterGuidanceCard? {
    val state = snapshot.routeState ?: return null
    if (state !in DISPLAYABLE_ROUTE_STATES) return null
    val arrow = clusterArrowId(snapshot.maneuverType, snapshot.drivingSide, snapshot.proceedToRoute) ?: return null
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
