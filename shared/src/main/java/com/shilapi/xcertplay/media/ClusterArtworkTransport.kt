package com.shilapi.xcertplay.media

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * How one album cover reaches the head unit, in the order this car was measured to accept.
 *
 * Two tiers remain, and the car -- not the reference implementation -- decided which one goes first:
 *
 * 1. **A world-readable `file://` copy under a directory the reading process may actually open.**
 *    The copy is *not* written into the app's own external files directory any more. The 2026-10-10
 *    on-car `adb` run showed why that tree can never work on this ROM, whatever string it is handed
 *    over as:
 *
 *    ```
 *    $ adb shell ls -l /sdcard/Android/data/com.shihab.diplay.hudtest/files/cluster-artwork/
 *    Permission denied                       # even with 1015(sdcard_rw),1028(sdcard_r) held by the shell
 *    $ adb shell ls -l /sdcard/Music/mediacenter/images/太阳之子-那天下雨了-周杰伦.jpg
 *    -rw-rw---- root sdcard_r  …             # the car's own media app, whose covers the cluster shows
 *    ```
 *
 *    `Android/data/<pkg>/` is unreadable for every other UID on the CS11, so a cover parked there is
 *    invisible to the cluster's reader even with a resolvable path string. The car's own media
 *    application hands the cluster a `file:///sdcard/...` path in a *shared* tree instead
 *    (`file:///sdcard/Music/mediacenter/images/…`, docs/_digest/48), which is what this tier copies:
 *    [CLUSTER_ARTWORK_SHARED_ROOT] under the external storage root, read back through the very string
 *    that is delivered. Before this ROM was measured, the same feature handed over a `file://` path in
 *    `getExternalFilesDir(null)` with `setReadable(true, false)` and the cluster showed the cover
 *    (docs/_digest/38 §3) -- the difference is which tree the reader could open, which is exactly what
 *    [ClusterArtworkBaseSelector] now probes instead of assuming.
 *
 *    A `content://` handover is still *below* this tier. On 2026-10-09 it succeeded three times in a
 *    row (`artwork decoded id=…` -> `cluster cover identity=… published=true` -> `cluster artwork
 *    encoded bytes=…` -> `cluster artwork grants=2 provider=true uri=content://…`, and the vendor's own
 *    service logged every new Uri as `albumCover2 = content://…`) while the instrument cluster never
 *    painted one of them. A reader that accepts our Uri and then never opens it is not something grants
 *    or a manifest can fix from here, so the file goes first.
 * 2. **`content://<package>.artwork/covers/<session>/<uuid>.jpg` plus an explicit
 *    `grantUriPermission` to each reader package**, kept for a car whose external tree is missing or
 *    unwritable. It stays fully wired (the provider is registered in `:common`'s manifest). This is
 *    the tier docs/_digest/45 §2.4 recorded from the reference app's `LegacyArtworkLoader` -- content
 *    provider plus grant first, then a loopback HTTP server -- and our own car disagreed with that
 *    order, which is why the two are swapped here. The reference's HTTP tier is still deliberately not
 *    built: it costs a port, a lifetime and a single-pass socket, and it only matters once both cheap
 *    tiers have failed (docs/_digest/45 §7.4).
 *
 * Encoding is not a tier. Whichever path serves the bytes, the head unit's reader was built around
 * JPEG at quality 88, a longest edge of at most 256 px and an encoded size under 1 MiB, so a cover is
 * scaled, encoded and size-checked identically here: we used to hand over a full-size PNG, which is
 * both far larger and a format that reader was never proven to take.
 *
 * Everything that can be decided without a device lives in this file as a pure function, so the
 * scale, the byte budget, the Uri shape, the grant list, the tier precedence, the path mapping, the
 * candidate order and how a candidate is accepted or rejected are unit-tested; only the compression,
 * the real read-back and the file I/O need the car (see [ClusterArtworkStore]).
 */

/** `<applicationId>.artwork`: the authority registered by the provider in `:common`'s manifest. */
internal const val CLUSTER_ARTWORK_AUTHORITY_SUFFIX = ".artwork"

/** The path segment the reference uses (`.../covers/<session>/<uuid>.jpg`). */
internal const val CLUSTER_ARTWORK_PATH = "covers"

/**
 * `<root>/cluster-artwork/<session>/`, under the cache for the provider tier, under the external
 * files directory for that form of the file tier and under [CLUSTER_ARTWORK_SHARED_ROOT] for the
 * shared form. One session directory per process, so a run's covers can be recognised and deleted as
 * a group.
 */
internal const val CLUSTER_ARTWORK_DIRECTORY = "cluster-artwork"

/**
 * The app's own directory inside the external storage *shared* area: `/sdcard/DiPlay/`.
 *
 * The 2026-10-10 `adb` run is why the file tier writes here rather than into `Android/data/<pkg>/`:
 * the sandbox directory is `Permission denied` for every other UID on this ROM, while a shared
 * directory is where the car's own media application keeps the covers the cluster does display. The
 * name is ours so nothing else's files are ever swept (see [cleanStaleClusterArtworkSessions]).
 */
internal const val CLUSTER_ARTWORK_SHARED_ROOT = "DiPlay"

/**
 * The flat name the pre-2026-10-09 fallback wrote straight into the external files directory
 * (`cluster-artwork-<millis>.jpg`).
 *
 * Nothing produces it any more; it is a constant because the leftovers of that implementation are
 * still on the car's storage and have to be recognised to be deleted (see
 * [cleanLegacyClusterArtworkFiles]).
 */
internal const val CLUSTER_ARTWORK_LEGACY_FILE_PREFIX = "cluster-artwork-"

/** The first JPEG quality tried, and the value the reference encodes with. */
internal const val CLUSTER_ARTWORK_QUALITY = 88

/**
 * Qualities tried in order until the encoding fits the byte budget.
 *
 * The reference gives up when its one encoding is too large. A cover the cluster could have shown
 * would then be lost for a reason nobody sees, so this walks down instead, and only the last result
 * being too large is a rejection. The values are far enough apart to actually shrink a noisy cover.
 */
internal val CLUSTER_ARTWORK_QUALITIES = intArrayOf(88, 62, 45)

/** The longest edge handed to the head unit. */
internal const val CLUSTER_ARTWORK_MAX_EDGE = 256

/** The encoded cover's ceiling: `bytes in 1..1048576`, the reference's accepted range. */
internal const val CLUSTER_ARTWORK_MAX_ENCODED_BYTES = 1_048_576L

/** Source edges and pixels the reference refuses before decoding. */
internal const val CLUSTER_ARTWORK_MAX_SOURCE_EDGE = 8_192
internal const val CLUSTER_ARTWORK_MAX_SOURCE_PIXELS = 16_777_216L

/** How many handed-over covers stay readable; the oldest is revoked and deleted past this. */
internal const val CLUSTER_ARTWORK_MAX_RETAINED = 4

/**
 * The two processes that read the cover, taken from the reference's `OEM_READERS`.
 *
 * `com.ecarx.dim_interaction` is the DIM service whose log line proves the *handover* works (the
 * `albumCover2` line in docs/_digest/48 §1); `ecarx.xsf.mediacenter` is the car's own media centre,
 * which shows the same artwork elsewhere on the head unit. Neither line proves the cluster painted it,
 * which is what the 2026-10-09 run taught us.
 */
internal val CLUSTER_ARTWORK_READERS = listOf("com.ecarx.dim_interaction", "ecarx.xsf.mediacenter")

/** `Intent.FLAG_GRANT_READ_URI_PERMISSION`, as a constant so the rules stay unit-testable. */
internal const val CLUSTER_ARTWORK_GRANT_FLAG = Intent.FLAG_GRANT_READ_URI_PERMISSION

private const val TAG = "DiPlay-Cluster"

private val UUID_PATTERN = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

/** The name of the file a base probe writes: fixed per process, so a reused path cannot look fresh. */
private const val CLUSTER_ARTWORK_PROBE_FILE = "base-probe.jpg"

/**
 * The shared roots a `file://` cover may be written under, in the order they are probed.
 *
 * `/sdcard/DiPlay` is first because that is the form the car's own media application uses for the
 * covers the cluster demonstrably displays, and because `/sdcard` is the alias the 2026-10-10 `adb`
 * run showed to be the one that resolves on this ROM (`/sdcard` -> `/storage/emulated/legacy` ->
 * `/mnt/shell/emulated/0`, while `/storage/emulated/0` is not a path at all there). The legacy and
 * `sdcard0` spellings follow as fallbacks for ROMs that disagree, and each is only ever probed when
 * every earlier candidate failed, so the normal case pays one probe.
 *
 * Duplicates are removed, because two spellings of one volume would otherwise be probed twice.
 */
internal fun clusterArtworkSharedBases(): List<String> = clusterArtworkSharedBasesFrom(
    listOf(
        externalStorageRoot(),
        "/storage/emulated/legacy",
        "/mnt/shell/emulated/0",
        "/storage/sdcard0",
    ),
)

/**
 * The shared bases built from [roots], with the nulls and the duplicate spellings of one volume
 * dropped.
 *
 * Takes the roots as plain values so the order and the de-duplication are asserted on a JVM: the
 * framework's own answer there is the host's temporary directory rather than one of the car's mount
 * points, which is the only reason this is separate from [clusterArtworkSharedBases].
 */
internal fun clusterArtworkSharedBasesFrom(roots: List<String?>): List<String> =
    roots.filterNotNull().map { it.trimEnd('/') }.distinct().map { "$it/$CLUSTER_ARTWORK_SHARED_ROOT" }

/**
 * The framework's external storage root, lowercased on this old ROM because the getter answers
 * `/sdcard` all-caps there.
 *
 * Extracted (and deprecated-suppressed) in one place so the candidate list above stays portable: a
 * device that exposes no external storage simply contributes no candidate, and a device with normal
 * storage contributes the same path the framework's own external files directory is built on.
 */
@Suppress("DEPRECATION")
internal fun externalStorageRoot(): String? =
    runCatching { Environment.getExternalStorageDirectory()?.absolutePath?.lowercase() }.getOrNull()

/**
 * The app's external files directory rewritten onto the shared-volume aliases.
 *
 * On this ROM the framework's answer (`/storage/emulated/0/Android/data/<pkg>/files`, per the
 * 2026-10-10 delivery that failed to resolve) is not a path the reader side can open, but the very
 * same directory *is* reachable as `/sdcard/Android/data/<pkg>/files`. Mapping the
 * `/…/Android/…` tail onto `/sdcard` and `/storage/emulated/legacy` gives the file tier two more
 * spellings to try before it gives up on files altogether. Only the `Android/` tail is taken, so a
 * framework answer of an unexpected shape produces no candidate rather than a guessed path.
 */
internal fun frameworkAliasCandidates(frameworkFilesRoot: File?, aliases: List<File>): List<File> {
    val root = frameworkFilesRoot ?: return emptyList()
    val text = root.path.replace('\\', '/').trimEnd('/')
    val index = text.indexOf("/Android/")
    if (index <= 0) return emptyList()
    val tail = text.substring(index + 1)
    val candidates = ArrayList<File>(aliases.size + 1)
    for (alias in aliases) {
        candidates += File(alias, tail)
    }
    candidates += root
    return candidates.distinctBy { it.path }
}

/** A per-process session directory name, so covers of one run can be revoked as a group. */
internal fun newClusterArtworkSession(): String = UUID.randomUUID().toString()

/** One cover file name: a fresh UUID per handover, so a reader caching by Uri cannot show a stale one. */
internal fun newClusterArtworkFileName(): String = UUID.randomUUID().toString() + ".jpg"

/** The provider authority for an application id. */
internal fun clusterArtworkAuthority(packageName: String): String =
    packageName + CLUSTER_ARTWORK_AUTHORITY_SUFFIX

/**
 * The exact `content://` text handed to the vendor, as a string.
 *
 * Kept as text (rather than a built `Uri`) so the shape -- which the provider must parse back and
 * which the two readers only ever see as an opaque string -- is unit-testable on the JVM.
 */
internal fun clusterArtworkUriText(packageName: String, session: String, fileName: String): String =
    "content://${clusterArtworkAuthority(packageName)}/$CLUSTER_ARTWORK_PATH/$session/$fileName"

/**
 * The exact `file://` text handed to the vendor for a path on disk.
 *
 * Built from the path string rather than from a [File] again, so the text that is announced is the
 * same text that was read back a moment earlier (see [clusterArtworkFileMatchesBytes]).
 */
internal fun clusterArtworkFileUriText(path: String): String = "file://$path"

/**
 * One candidate base, as a path a [File] can build on.
 *
 * Android has a single separator and no volume letters, so for the car this only removes a trailing
 * separator -- a spelling the ROM may answer, but one that would make two candidates out of one
 * directory. A host JVM is the reason for the volume branch: there a candidate may arrive with the
 * *other* separator (`C:\x` from the framework's own directory next to a `/sdcard` candidate), and
 * `File` answers a path built from mixed separators without its volume, which would put the probe
 * somewhere else entirely. No Android path has a `:` before its first separator, so the branch is
 * dead on the car and the delivered string stays byte-identical to the validated one.
 */
internal fun clusterArtworkCandidatePath(path: String): String {
    val compact = path.trimEnd('/', '\\')
    val volume = compact.indexOf(':')
    // A volume letter is a colon before the first separator (`C:\x`); Android paths have neither.
    val firstSeparator = compact.indexOf('/')
    val windowsPath = volume > 0 && (firstSeparator < 0 || volume < firstSeparator)
    return if (windowsPath) File(compact).canonicalPath else compact
}

/**
 * The scalar bound to the `{base}` tag of a [ClusterArtworkBaseSelector.reject] log line: the base
 * path with its separators folded into single dashes.
 *
 * The base can be any of the ROM's mount spellings, and none of them contains a dash, so the scalar
 * says which one was refused while staying greppable alongside `base=/sdcard/DiPlay (probe ok)`.
 */
internal fun clusterArtworkBaseLogTag(base: String): String {
    var text = base.trimEnd('/').removePrefix("/")
    if (text.isEmpty()) return "root"
    return text.map { if (it == '/') '-' else it }.joinToString("")
}

/**
 * The scale factor for a source image: `min(1, 256 / longestEdge)`.
 *
 * Never enlarges: the head unit's reader is not given a upscaled cover it did not ask for.
 */
internal fun clusterArtworkScale(width: Int, height: Int): Float {
    if (width <= 0 || height <= 0) return 0f
    return minOf(1f, CLUSTER_ARTWORK_MAX_EDGE.toFloat() / maxOf(width, height))
}

/** One scaled edge, rounded down but never below one pixel (the reference's `max(1, ...)`). */
internal fun clusterArtworkScaledEdge(edge: Int, scale: Float): Int =
    if (scale <= 0f) 0 else maxOf(1, (edge * scale).toInt())

/**
 * Whether a source image may be encoded at all: `1..8192` px per edge, at most 16 M pixels, and --
 * when the compressed size is known -- `1..1048576` bytes.
 *
 * [byteSize] is null on the path this module actually uses: the bridge receives an already decoded
 * bitmap whose bytes were bounded by the iAP2 file-transfer cap before decoding, so re-checking a
 * byte count that no longer exists would be theatre. The parameter stays because the rule is part of
 * the reference's contract and is asserted by the tests.
 */
internal fun clusterArtworkSourceAccepted(width: Int, height: Int, byteSize: Int? = null): Boolean {
    if (width !in 1..CLUSTER_ARTWORK_MAX_SOURCE_EDGE) return false
    if (height !in 1..CLUSTER_ARTWORK_MAX_SOURCE_EDGE) return false
    if (width.toLong() * height.toLong() > CLUSTER_ARTWORK_MAX_SOURCE_PIXELS) return false
    if (byteSize != null && byteSize.toLong() !in 1..CLUSTER_ARTWORK_MAX_ENCODED_BYTES) return false
    return true
}

/** The encoded-cover budget: a size in `1..1048576` bytes, nothing else. */
internal fun clusterArtworkWithinBudget(encodedBytes: Long): Boolean =
    encodedBytes in 1..CLUSTER_ARTWORK_MAX_ENCODED_BYTES

/**
 * Encodes with the first [qualities] entry that lands inside the byte budget, or null when none does.
 *
 * The encode/size/discard trio is injected so the ladder itself -- how many attempts happen, in which
 * order, and that a rejected attempt is thrown away rather than kept -- is a pure rule. `discard`
 * also runs when every attempt fails, so a partially written file cannot stay behind.
 */
internal fun <T : Any> encodeClusterArtworkWithinBudget(
    encode: (quality: Int) -> T?,
    sizeOf: (T) -> Long,
    discard: (T) -> Unit,
    qualities: IntArray = CLUSTER_ARTWORK_QUALITIES,
): T? {
    for (quality in qualities) {
        val encoded = encode(quality) ?: continue
        if (clusterArtworkWithinBudget(sizeOf(encoded))) return encoded
        discard(encoded)
    }
    return null
}

/** Which tier the handover ended in. */
internal enum class ClusterArtworkTier {
    /** The primary tier: a readable `file://` copy in a tree the reader can open. */
    FILE,

    /** The fallback tier: `content://` with an explicit read grant for the vendor's reader processes. */
    CONTENT,
}

/**
 * The tier to use, given what the device answered, or null when neither tier can serve the cover.
 *
 * The external copy is decided first because it is the only one the car ever proved it displays (see
 * the file KDoc). The provider is reached only when no base validated: either half of that tier
 * missing -- no provider in the merged manifest, or every grant refused -- means the cover is dropped
 * instead, because a Uri its reader can only fail on is worse than no cover at all.
 */
internal fun clusterArtworkTier(
    fileUsable: Boolean,
    providerRegistered: Boolean,
    granted: Int,
): ClusterArtworkTier? = when {
    fileUsable -> ClusterArtworkTier.FILE
    providerRegistered && granted > 0 -> ClusterArtworkTier.CONTENT
    else -> null
}

/**
 * Whether the external copy [file] really holds readable bytes: it exists, has a length, and one byte
 * reads back through *that* [File].
 *
 * Kept as the cheap shape check. It is deliberately not what decides a handover any more: it answers
 * "can this process read this descriptor", while the failure that reached the car was "does the string
 * we deliver resolve for the reader", and a check that reuses the writer's own descriptor cannot tell
 * the two apart (see [clusterArtworkFileMatchesBytes]).
 */
internal fun clusterArtworkFileReadable(file: File): Boolean {
    if (!file.isFile || file.length() == 0L) return false
    return runCatching { file.inputStream().use { it.read() } >= 0 }.getOrDefault(false)
}

/**
 * Whether [file] holds exactly [expected]: a real file, with a byte count in the accepted range, every
 * byte of which can be read, matching the encoded cover that is about to be announced.
 *
 * This is the self-check that replaced the old "read it back with the same `File` object" test. That
 * one could pass while the string handed to the vendor pointed at a path nothing else on the ROM could
 * open, which is exactly how three delivered covers reached the car on 2026-10-10 without the cluster
 * ever showing one. A reader misled by a wrong length, a truncated file or a failed mid-file read would
 * paint a cover that is not there, so all three are rejections rather than warnings.
 *
 * The whole file is compared because a cover is bounded by [CLUSTER_ARTWORK_MAX_ENCODED_BYTES]: at most
 * one mebibyte is held, and only for the length of one probe or one handover.
 */
internal fun clusterArtworkFileMatchesBytes(file: File, expected: ByteArray): Boolean {
    if (!clusterArtworkWithinBudget(expected.size.toLong())) return false
    if (!file.isFile) return false
    if (file.length() != expected.size.toLong()) return false
    return runCatching { file.readBytes().contentEquals(expected) }.getOrDefault(false)
}

/**
 * Maps a `content://<package>.artwork/covers/<session>/<uuid>.jpg` Uri back to the cached file.
 *
 * Public because the provider itself lives in `:common` -- `internal` in this module is not visible
 * from there -- while the writer and the eviction logic stay here. Both sides must agree on the
 * shape, so there is exactly one implementation of it, and it takes plain values rather than an
 * `android.net.Uri` so it is unit-testable without a device.
 *
 * Only the fallback tier writes into [cacheDir]: the primary tier's `file://` copy is never served
 * through the provider. Returns null for anything that is not our authority, carries a query or
 * fragment, has the wrong number of path segments, or names a file that is not there: a provider that
 * guessed would hand out files outside the cover directory.
 */
fun clusterArtworkCoverFile(
    cacheDir: File,
    packageName: String,
    scheme: String?,
    authority: String?,
    pathSegments: List<String>,
    query: String?,
    fragment: String?,
): File? {
    if (scheme != "content") return null
    if (authority != clusterArtworkAuthority(packageName)) return null
    if (query != null || fragment != null) return null
    if (pathSegments.size != 3) return null
    if (pathSegments[0] != CLUSTER_ARTWORK_PATH) return null
    if (!UUID_PATTERN.matches(pathSegments[1])) return null
    if (!pathSegments[2].endsWith(".jpg")) return null
    val stem = pathSegments[2].removeSuffix(".jpg")
    if (!UUID_PATTERN.matches(stem)) return null
    val file = File(File(cacheDir, CLUSTER_ARTWORK_DIRECTORY), "${pathSegments[1]}/${pathSegments[2]}")
    return if (file.isFile) file else null
}

/**
 * The covers handed over so far, oldest first, capped at [CLUSTER_ARTWORK_MAX_RETAINED].
 *
 * The cap is the reference's. A cover that falls out of it must stop being readable: the caller
 * revokes the grant and deletes the file for every entry [add] returns, otherwise a long drive would
 * leave every cover of the session readable (and the grant table growing) in one of the trees.
 */
internal class ClusterArtworkRetention(private val limit: Int = CLUSTER_ARTWORK_MAX_RETAINED) {
    /** One handed-over cover: the file on disk and the Uri granted for it, if the fallback tier ran. */
    data class Entry(val file: File, val uri: Uri?)

    private val held = ArrayList<Entry>()

    /** Records [entry] and returns every entry pushed past the cap, oldest first. */
    fun add(entry: Entry): List<Entry> {
        held.add(entry)
        if (held.size <= limit) return emptyList()
        val evicted = ArrayList<Entry>(held.size - limit)
        while (held.size > limit) evicted.add(held.removeAt(0))
        return evicted
    }

    /** Forgets what is held; the caller is responsible for revoking and deleting it. */
    fun clear(): List<Entry> {
        val all = ArrayList(held)
        held.clear()
        return all
    }
}

/**
 * Why one candidate base was not taken. Every value is a distinct on-car `logcat` fact, so a driver's
 * report can be matched to the exact step that refused the delivery.
 */
internal enum class ClusterArtworkRejection {
    /** The write itself was refused (missing parent, read-only volume, no permission). */
    WRITE_FAILED,

    /** The write reported success but the same path string reads nothing, or too few bytes, back. */
    READ_FAILED,

    /** The path reads back, but not the encoded cover: a stale or truncated file at that path. */
    BYTES_MISMATCH,
}

/** What validating one candidate base decided. */
internal sealed class ClusterArtworkBaseProbe {
    /** The base took the encoded bytes and read them back; [file] is the probe file that proved it. */
    data class Valid(val file: File) : ClusterArtworkBaseProbe()

    /** The base is unusable, for [reason]. */
    data class Invalid(val reason: ClusterArtworkRejection) : ClusterArtworkBaseProbe()
}

/**
 * Where a base is rejected, once per process.
 *
 * The decision is cached, so a rejection usually happens once anyway; this exists for a base that is
 * re-probed after a later write failed, and it is what keeps a long drive from repeating the same
 * three lines for every track.
 */
internal fun interface ClusterArtworkRejectionLog {
    fun rejected(base: String, reason: ClusterArtworkRejection)
}

/** The raw file operations a candidate probe needs, injectable so the rules run on the JVM. */
internal data class RawFileSystem(
    /** Whether the path is an existing directory. */
    val isDirectory: (File) -> Boolean = { it.isDirectory },
    /** Creates the directory chain; true when the path is a directory afterwards. */
    val mkdirs: (File) -> Boolean = { it.mkdirs() || it.isDirectory },
    /**
     * Copy operation or test fixture: writes [source]'s bytes into [directory] and reads the result
     * back *through the path it created*.
     *
     * The directory rather than the target file, because a test double has to identify the candidate
     * base from the path, and `File.parentFile` answers null for a Unix-absolute path on a JVM running
     * on Windows -- which is where the pure tests for this car run. Handing the bytes and the read-back
     * in one operation is also what makes the check honest: the bytes that must come back are the bytes
     * that went in, and the read is of a path this operation built rather than one the caller assumed.
     */
    val writeCopyAndReadBack: (source: File, directory: File) -> ByteArray? = { source, directory ->
        val target = File(directory, CLUSTER_ARTWORK_PROBE_FILE)
        source.inputStream().use { input -> target.outputStream().use { output -> input.copyTo(output) } }
        runCatching { target.readBytes() }.getOrNull()
    },
    /** Whether the path is an existing *file*; a path that is not there yet is not a blocker. */
    val isFile: (File) -> Boolean = { it.isFile },
    /** Best-effort removal; false leaves [file] alone, e.g. because it is a cached source. */
    val delete: (File) -> Boolean = { it.delete() },
    /** Copy operation or test fixture: writes [source]'s bytes to [target]; throws when refused. */
    val copy: (source: File, target: File) -> Unit = { source, target ->
        source.inputStream().use { input -> target.outputStream().use { output -> input.copyTo(output) } }
    },
)

/**
 * Validates candidate bases and remembers the one that worked, as a pure decision.
 *
 * Three things make this a class rather than a function. The decision is cached, so a drive pays one
 * probe instead of one per track, and a base that was refused once is not probed again. A base that
 * fails *later* -- the volume unmounts, the directory is removed underneath us -- is probed again by
 * [invalidate] before the cache is trusted, because a cached answer that outlives the volume would
 * turn every following track into a silent failure and a volume that comes back must not need an app
 * restart. And each rejection is reported through [onRejected] exactly once per base per process, so
 * the `logcat` of a drive that never got a cover states the reason once per base rather than once per
 * track.
 *
 * The probe executes against the *delivered path shape*: a probe file inside
 * `<base>/cluster-artwork/<session>/`, the same directory the cover goes into. Validating only the
 * volume or only the writability of the base would accept exactly the ROM this class exists for,
 * where the bytes are on disk and the string handed to the reader still resolves nowhere.
 */
internal class ClusterArtworkBaseSelector(
    private val candidates: List<String>,
    private val session: String,
    private val raw: RawFileSystem,
    private val onRejected: ClusterArtworkRejectionLog,
) {
    private val rejected = LinkedHashSet<String>()

    /** Bases whose rejection has already been logged, so a drive's logcat states each reason once. */
    private val reported = LinkedHashSet<String>()

    private var chosen: String? = null

    /** A base that was chosen and then failed a write: probed first on the next handover. */
    private var suspect: String? = null

    /** The base a previous handover proved, or null while a probe has to run. */
    val currentBase: String? get() = chosen

    /**
     * The first candidate that accepts [source] and reads it back, or null when none does.
     *
     * [source] is the freshly encoded cover, so validation compares the real payload rather than a
     * marker string; the probe copy is deleted again, whatever the answer.
     */
    fun select(source: File): String? {
        chosen?.let { return it }
        // The base whose own delivery failed *after* it had been chosen is probed again, even though it
        // is in the refused set: a volume that comes back must become usable without an app restart, and
        // one failure must not keep a directory out of the search for the rest of the drive. It is
        // probed once, and a second refusal is not logged, because the on-car log already has the reason.
        val retry = suspect
        suspect = null
        if (retry != null && probeCandidate(retry, source)) return retry
        for (candidate in candidates) {
            if (candidate == retry || candidate in rejected) continue
            if (probeCandidate(candidate, source)) return candidate
        }
        return null
    }

    /** Probes one candidate and adopts it when it validates. */
    private fun probeCandidate(candidate: String, source: File): Boolean =
        when (val probe = probe(candidate, source)) {
            is ClusterArtworkBaseProbe.Valid -> {
                chosen = candidate
                Log.i(TAG, "cluster artwork base=$candidate (probe ok)")
                true
            }
            is ClusterArtworkBaseProbe.Invalid -> {
                rejected += candidate
                if (reported.add(candidate)) {
                    runCatching { onRejected.rejected(candidate, probe.reason) }
                }
                false
            }
        }

    /**
     * Forgets a base that accepted a probe but failed a later write, so the next handover probes it
     * again before trusting the cache.
     *
     * It is remembered as a *suspect* rather than simply dropped: a mount that flaps, or a directory
     * that is removed underneath us, is exactly the case where the same base is the most likely one to
     * work again. It stays in the refused set too, so a probe that fails once more does not send the
     * search back through candidates that were already refused.
     */
    fun invalidate(base: String) {
        // The cached decision goes even when the failure was reported for another base: the store only
        // reports a failure after a delivery it attempted, so the directory it was using has just been
        // found wanting too.
        chosen = null
        suspect = base
        // Dropped from the refused set as well, because "it failed once" must not keep a directory out
        // of the ordinary walk for the rest of the drive: the retry above only gets one attempt per
        // handover, and the walk is what finds it again when it comes back.
        rejected -= base
    }

    private fun probe(base: String, source: File): ClusterArtworkBaseProbe {
        val directory = File(File(base, CLUSTER_ARTWORK_DIRECTORY), session)
        if (!raw.isDirectory(directory) && !raw.mkdirs(directory)) {
            return ClusterArtworkBaseProbe.Invalid(ClusterArtworkRejection.WRITE_FAILED)
        }
        // The probe target is the delivered shape -- `<base>/cluster-artwork/<session>/…` -- under a name
        // that is fixed rather than a UUID: a reused name cannot look like a fresh write, which is what a
        // stale file under a fresh UUID could.
        val held = File(directory, CLUSTER_ARTWORK_PROBE_FILE)
        val encoded = source.readBytes()
        val result = try {
            // A leftover probe file that cannot be cleared means this base cannot be written -- but a
            // probe file that is simply not there yet is the normal case, and `File.delete` answers
            // false for it, so existence is what decides.
            if (raw.isFile(held) && !raw.delete(held)) {
                return ClusterArtworkBaseProbe.Invalid(ClusterArtworkRejection.WRITE_FAILED)
            }
            val read = raw.writeCopyAndReadBack(source, directory)
            when {
                read == null || read.size != encoded.size ->
                    ClusterArtworkBaseProbe.Invalid(ClusterArtworkRejection.READ_FAILED)
                !read.contentEquals(encoded) ->
                    ClusterArtworkBaseProbe.Invalid(ClusterArtworkRejection.BYTES_MISMATCH)
                else -> ClusterArtworkBaseProbe.Valid(held)
            }
        } catch (refused: Exception) {
            ClusterArtworkBaseProbe.Invalid(ClusterArtworkRejection.WRITE_FAILED)
        } finally {
            raw.delete(held)
        }
        return result
    }
}

/**
 * Encodes one cover and hands it over, or returns null when no tier could.
 *
 * Device-only work: JPEG compression, the session directories, the candidate probes, the external
 * copy with its read-back of the delivered string, the provider check and the grants. What can be
 * decided without a device is delegated to the pure functions above. A [ClusterArtworkStore] is
 * created once per process: its session directory lets the next process recognise and delete this
 * run's leftovers (see [cleanStaleClusterArtworkSessions]).
 *
 * The device facts the tier decision needs ([externalFilesDir], [cacheRoot], [providerRegistered])
 * are constructor parameters, so both tiers can be exercised in a JVM test; the car passes its own
 * external directory and asks its own package manager.
 */
internal class ClusterArtworkStore(
    private val context: Context,
    /** The app's external files directory, or null when the car exposes none. */
    private val externalFilesDir: File? = context.getExternalFilesDir(null),
    /** The internal tree the provider serves, `<cacheDir>/cluster-artwork`. */
    private val cacheRoot: File = File(context.cacheDir, CLUSTER_ARTWORK_DIRECTORY),
    /** Whether the merged manifest registered the artwork provider (the car answers from its package manager). */
    private val providerRegistered: () -> Boolean = {
        runCatching {
            context.packageManager.resolveContentProvider(clusterArtworkAuthority(context.packageName), 0) != null
        }.getOrDefault(false)
    },
    /** The raw file operations the base probe uses; the car leaves the defaults in place. */
    private val raw: RawFileSystem = RawFileSystem(),
    /** The platform level, injected so the sandbox-alias candidates can be reasoned about in a test. */
    private val sdkInt: Int = Build.VERSION.SDK_INT,
    /** The shared roots to try, in order; the car leaves this at [clusterArtworkSharedBases]. */
    private val sharedBases: List<String>? = null,
) {
    private val session = newClusterArtworkSession()
    private val cacheDirectory = File(cacheRoot, session)

    /** `/sdcard/DiPlay`, `/storage/emulated/legacy/DiPlay`, … in probe order. */
    private val resolvedSharedBases: List<String> =
        if (sharedBases.isNullOrEmpty()) clusterArtworkSharedBases() else sharedBases

    /** `<externalFilesDir>/cluster-artwork`: the pre-2026-10-10 primary tier, now the second choice. */
    private val externalRoot = externalFilesDir?.let { File(it, CLUSTER_ARTWORK_DIRECTORY) }

    /**
     * Every root the file tier may write into, in the order they are probed.
     *
     * The shared roots come first (the car's own media application proves the cluster's reader is
     * given a path there), then the framework's external files directory, then its `/sdcard` and
     * `/storage/emulated/legacy` spellings. On API 29+ the sandbox alias is dropped: the platform
     * documents that path as unreadable for other apps there, so it would only add a rejection line to
     * a tablet that does not need it.
     */
    private val externalDirectories: List<String> = ArrayList<String>(resolvedSharedBases.size + 4).apply {
        addAll(resolvedSharedBases)
        externalFilesDir?.let { add(File(it, CLUSTER_ARTWORK_DIRECTORY).path) }
        if (sdkInt < Build.VERSION_CODES.Q) {
            addAll(
                frameworkAliasCandidates(
                    externalFilesDir,
                    listOfNotNull(externalStorageRoot()?.let(::File), File("/storage/emulated/legacy")),
                ).map { File(it, CLUSTER_ARTWORK_DIRECTORY).path },
            )
        }
    }
        .map(::clusterArtworkCandidatePath)
        .distinct()

    /** Readable for the retention sweep: the shared tree a stale session may be left in. */
    private val sharedRoot: File? = resolvedSharedBases.firstOrNull()?.let(::File)

    private val selector = ClusterArtworkBaseSelector(
        candidates = externalDirectories,
        session = session,
        raw = raw,
        onRejected = { base, reason ->
            Log.w(TAG, "cluster artwork base rejected base=$base reason=$reason")
            // One scalar per base, so an on-car grep can count spellings without reading the detail.
            Log.w(TAG, "cluster artwork rejected_{base=${clusterArtworkBaseLogTag(base)}} reason=$reason")
        },
    )

    private val retention = ClusterArtworkRetention()

    init {
        cleanStaleClusterArtworkSessions(cacheRoot, session)
        sharedRoot?.let { cleanStaleClusterArtworkSessions(File(it, CLUSTER_ARTWORK_DIRECTORY), session) }
        externalRoot?.let { cleanStaleClusterArtworkSessions(it, session) }
        cleanLegacyClusterArtworkFiles(externalFilesDir)
    }

    /**
     * Encodes [bitmap] and returns the Uri to hand to the bridge, or null when nothing was produced.
     *
     * The cover is always encoded into the cache first, because that tree is the reliably writable one
     * and because the provider tier serves from it; the file tier then *moves* those exact bytes to a
     * base that validated. Only one of the two survives a handover: the cache file is deleted once the
     * file tier has it, and the external copy is deleted when the file tier gave up, so neither tier
     * leaves a file behind for the other. The quality ladder means a noisy cover is retried smaller
     * rather than dropped.
     */
    fun publish(bitmap: Bitmap): Uri? {
        val encoded = encodeCover(bitmap) ?: return null
        val base = selector.select(encoded)
        if (base != null) {
            // The cover lands under the session directory of the base that just read its own probe back.
            val directory = File(File(base, CLUSTER_ARTWORK_DIRECTORY), session)
            val target = File(directory, encoded.name)
            if (deliverToBase(encoded, target)) {
                // Moved, not copied: nothing will serve the cache copy now that the provider tier lost.
                runCatching { encoded.delete() }
                val delivered = target.absolutePath
                evict(retention.add(ClusterArtworkRetention.Entry(target, uriFor(delivered))))
                return uriFor(delivered)
            }
            // The volume went away between the probe and the write: re-probe on the next track.
            selector.invalidate(base)
        }
        val uri = Uri.parse(clusterArtworkUriText(context.packageName, session, encoded.name))
        val registered = providerRegistered()
        val granted = if (registered) grant(uri) else 0
        Log.i(TAG, "cluster artwork fallback grants=$granted provider=$registered uri=$uri")
        if (clusterArtworkTier(fileUsable = false, providerRegistered = registered, granted = granted) ==
            ClusterArtworkTier.CONTENT
        ) {
            evict(retention.add(ClusterArtworkRetention.Entry(encoded, uri)))
            return uri
        }
        // No tier can serve it: a cached file nobody may read is a leak, not a cover.
        runCatching { encoded.delete() }
        return null
    }

    /** Revokes and deletes everything this store handed over; called when a session ends. */
    fun clear() {
        evict(retention.clear())
    }

    private fun encodeCover(bitmap: Bitmap): File? {
        if (bitmap.isRecycled) return null
        val width = bitmap.width
        val height = bitmap.height
        if (!clusterArtworkSourceAccepted(width, height)) {
            Log.w(TAG, "cluster artwork rejected size=${width}x$height")
            return null
        }
        val scale = clusterArtworkScale(width, height)
        val scaled = if (scale >= 1f) {
            bitmap
        } else {
            runCatching {
                Bitmap.createScaledBitmap(
                    bitmap,
                    clusterArtworkScaledEdge(width, scale),
                    clusterArtworkScaledEdge(height, scale),
                    true,
                )
            }.getOrNull() ?: return null
        }
        val encoded = runCatching {
            if (cacheDirectory.isDirectory || cacheDirectory.mkdirs()) {
                encodeClusterArtworkWithinBudget(
                    encode = { quality -> compress(scaled, quality) },
                    sizeOf = File::length,
                    discard = { it.delete() },
                )
            } else {
                null
            }
        }.getOrNull()
        val edge = "${scaled.width}x${scaled.height}"
        if (scaled !== bitmap) scaled.recycle()
        if (encoded == null) {
            Log.w(TAG, "cluster artwork encode failed size=${width}x$height")
            return null
        }
        Log.i(TAG, "cluster artwork encoded bytes=${encoded.length()} edge=$edge")
        return encoded
    }

    private fun compress(bitmap: Bitmap, quality: Int): File? {
        val file = File(cacheDirectory, newClusterArtworkFileName())
        return runCatching {
            FileOutputStream(file).use { out ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)) {
                    error("JPEG compression refused")
                }
            }
            file
        }.getOrElse {
            file.delete()
            null
        }
    }

    /**
     * The file tier's write: copies the encoded cover to [target] under the validated base and returns
     * whether the *delivered path string* reads those exact bytes back.
     *
     * The self-check is deliberately built from the string that is about to be handed over, through
     * [clusterArtworkFileMatchesBytes], not from a descriptor the writer already holds: reusing the
     * writer's own `File` is what let three covers reach the car on 2026-10-10 while the path the
     * reader was given pointed nowhere. `setReadable(true, false)` stays best-effort and its answer is
     * only logged -- the project already learned that chmod on the emulated `/sdcard` view does not
     * necessarily reach another UID (docs/_digest/38 §3) -- so a false from it must not cost the cover.
     *
     * [target] is never deleted here. It may be the cached source when a probe returned the cache tree
     * itself, and even when it is a copy the next candidate's write simply overwrites it, so a failed
     * attempt cannot destroy the cover of the attempt that follows.
     */
    private fun deliverToBase(encoded: File, target: File): Boolean {
        return try {
            val directory = target.parentFile
            if (directory != null && !raw.isDirectory(directory) && !raw.mkdirs(directory)) {
                Log.w(TAG, "cluster artwork external directory unavailable dir=$directory")
                return false
            }
            // The copy goes to the delivered name, not to the probe name, so the read-back below is of
            // the exact file the vendor will be told to open.
            raw.copy(encoded, target)
            val worldReadable = runCatching { target.setReadable(true, false) }.getOrDefault(false)
            // The absolute form: on the car it is the same string as `path` (there is one root and one
            // separator), and on a host JVM it is the form that keeps the volume letter, which a path
            // built from a forward-slash volume string loses. The reader is handed exactly this text.
            val delivered = target.absolutePath
            // A fresh descriptor built from the delivered string, so the check cannot inherit the
            // writer's view of the path.
            if (!clusterArtworkFileMatchesBytes(File(delivered), encoded.readBytes())) {
                Log.w(TAG, "cluster artwork external copy unreadable file=$delivered chmod=$worldReadable")
                return false
            }
            Log.i(
                TAG,
                "cluster artwork tier=file chmod=$worldReadable bytes=${target.length()} " +
                    "uri=${clusterArtworkFileUriText(delivered)}",
            )
            true
        } catch (refused: Exception) {
            Log.w(TAG, "cluster artwork external copy failed file=${target.absolutePath}", refused)
            false
        }
    }

    /**
     * The Uri for a delivered path, built from that exact string.
     *
     * `Uri.fromFile` would accept the `File` again and re-encode it; parsing the string keeps the
     * delivered text and the string that was validated identical by construction, and leaves the
     * `content://` branch on its own [Uri.parse] path.
     */
    private fun uriFor(delivered: String): Uri = Uri.parse(clusterArtworkFileUriText(delivered))

    /**
     * Grants the read permission on [uri] to every reader in [CLUSTER_ARTWORK_READERS] and counts the
     * ones that took.
     *
     * `grantUriPermission` is the whole reason the `content://` tier can work across UIDs; a refusal
     * is logged by name rather than thrown, because one missing reader (a car without the media
     * centre, say) must not cost the cover for the reader that is there.
     */
    private fun grant(uri: Uri): Int {
        var granted = 0
        for (reader in CLUSTER_ARTWORK_READERS) {
            runCatching { context.grantUriPermission(reader, uri, CLUSTER_ARTWORK_GRANT_FLAG) }
                .onSuccess { granted++ }
                .onFailure { Log.w(TAG, "cluster artwork grant to $reader failed", it) }
        }
        return granted
    }

    private fun evict(entries: List<ClusterArtworkRetention.Entry>) {
        for (entry in entries) {
            entry.uri?.let { uri ->
                runCatching { context.revokeUriPermission(uri, CLUSTER_ARTWORK_GRANT_FLAG) }
            }
            runCatching { entry.file.delete() }
        }
    }
}

/**
 * Deletes the cover session directories a previous process left behind in [root].
 *
 * A process that is killed mid-drive never revokes its grants or removes its covers, so the next
 * start cleans up after it -- in the cache, in the app's own external directory and in our shared
 * `DiPlay/cluster-artwork` directory, since a cover in one is not visible to the reader of another.
 * Only directories that are exactly a session UUID are touched, and only inside the given root, so a
 * bug here can not walk out of the tree it was handed. [activeSession] is spared because it belongs
 * to the store that is doing the cleaning.
 *
 * The sweep never walks past [root]: `/sdcard/DiPlay` is ours and nothing else's -- in particular
 * `/sdcard/Music/mediacenter/…`, which the car's own media application shares, is never passed in
 * and never touched.
 */
internal fun cleanStaleClusterArtworkSessions(root: File, activeSession: String) {
    val entries = root.listFiles() ?: return
    for (candidate in entries) {
        if (!candidate.isDirectory || !UUID_PATTERN.matches(candidate.name)) continue
        if (candidate.name == activeSession) continue
        runCatching { candidate.deleteRecursively() }
    }
}

/**
 * Deletes the flat `cluster-artwork-<millis>.jpg` files the pre-2026-10-09 `file://` fallback left in
 * the root of the external files directory.
 *
 * That implementation wrote one file per handover directly into this directory and deleted only the
 * previous one, so the last cover of every run stayed there. The current writer uses
 * `<root>/cluster-artwork/<session>/` instead, which is why these leftovers can go unconditionally:
 * nothing produces this shape any more, and a `file://` reader that lists the directory is only
 * confused by them. Only the app's own files directory is passed in, so the sweep cannot reach
 * anything else.
 */
internal fun cleanLegacyClusterArtworkFiles(externalFilesRoot: File?) {
    val entries = externalFilesRoot?.listFiles() ?: return
    for (candidate in entries) {
        if (!candidate.isFile) continue
        if (!candidate.name.startsWith(CLUSTER_ARTWORK_LEGACY_FILE_PREFIX)) continue
        runCatching { candidate.delete() }
    }
}





