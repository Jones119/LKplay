package com.shilapi.xcertplay.media

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * How one album cover reaches the head unit, in the order this car was measured to accept.
 *
 * Two tiers remain, and the car -- not the reference implementation -- decided which one goes first:
 *
 * 1. **A world-readable `file://` copy under the app's external files directory.** On 2026-10-09 the
 *    `content://` handover below *succeeded three times in a row* on the car (fresh app process, fresh
 *    CarPlay session, music playing): `artwork decoded id=…` -> `cluster cover identity=… published=true`
 *    -> `cluster artwork encoded bytes=… edge=256x256` -> `cluster artwork grants=2 provider=true
 *    uri=content://…`, and the vendor's own service logged receiving every new Uri
 *    (`albumCover2 = content://…`) -- yet the instrument cluster never painted one of them, including
 *    in a session whose media card was created from scratch with that very Uri. In the 2026-10-08 run
 *    the same feature handed over a `file://` path in `getExternalFilesDir(null)` with
 *    `setReadable(true, false)` and the cluster *did* show the cover (docs/_digest/38 §3). A reader
 *    that accepts our Uri and then never opens it is not something grants or a manifest can fix from
 *    here, so the file goes first.
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
 * scale, the byte budget, the Uri shape, the grant list, the tier precedence and the path mapping are
 * unit-tested; only the compression, the read-back, the grant and the file I/O need the car (see
 * [ClusterArtworkStore]).
 */

/** `<applicationId>.artwork`: the authority registered by the provider in `:common`'s manifest. */
internal const val CLUSTER_ARTWORK_AUTHORITY_SUFFIX = ".artwork"

/** The path segment the reference uses (`.../covers/<session>/<uuid>.jpg`). */
internal const val CLUSTER_ARTWORK_PATH = "covers"

/**
 * `<root>/cluster-artwork/<session>/`, under the cache for the provider tier and under the external
 * files directory for the file tier. One session directory per process, so a run's covers can be
 * recognised and deleted as a group.
 */
internal const val CLUSTER_ARTWORK_DIRECTORY = "cluster-artwork"

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
    /** The primary tier: a readable `file://` copy in the app's external files directory. */
    FILE,

    /** The fallback tier: `content://` with an explicit read grant for the vendor's reader processes. */
    CONTENT,
}

/**
 * The tier to use, given what the device answered, or null when neither tier can serve the cover.
 *
 * The external copy is decided first because it is the only one the car ever proved it displays (see
 * the file KDoc). The provider is reached only when there is no readable external file: either half
 * of that tier missing -- no provider in the merged manifest, or every grant refused -- means the
 * cover is dropped instead, because a Uri its reader can only fail on is worse than no cover at all.
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
 * Whether the external copy can really be handed over: it exists, holds bytes, and one byte reads
 * back in this process.
 *
 * A write that reported success on emulated storage is not proof the file is there -- `/sdcard` is a
 * FUSE view and `setReadable` on it is best-effort -- and handing over a `file://` Uri nobody can
 * open is precisely the failure this file exists to avoid, so the copy is read back once before it is
 * announced. The check is deliberately cheap (a `file://` reader opens the path itself; what matters
 * is that it yields bytes now) and pure, so both the success and the failure can be tested.
 */
internal fun clusterArtworkFileReadable(file: File): Boolean {
    if (!file.isFile || file.length() == 0L) return false
    return runCatching { file.inputStream().use { it.read() } >= 0 }.getOrDefault(false)
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
 * leave every cover of the session readable (and the grant table growing) in one of the two trees.
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
 * Encodes one cover and hands it over, or returns null when no tier could.
 *
 * Device-only work: JPEG compression, the session directories, the external copy with its read-back,
 * the provider check and the grants. What can be decided without a device is delegated to the pure
 * functions above. A [ClusterArtworkStore] is created once per process: its session directory lets
 * the next process recognise and delete this run's leftovers (see [cleanStaleClusterArtworkSessions]).
 *
 * The three device facts the tier decision needs ([externalFilesDir], [cacheRoot],
 * [providerRegistered]) are constructor parameters, so both tiers can be exercised in a JVM test; the
 * car passes its own external directory and asks its own package manager.
 */
internal class ClusterArtworkStore(
    private val context: Context,
    /** The app's external files directory, or null when the car exposes none; the primary tier writes here. */
    private val externalFilesDir: File? = context.getExternalFilesDir(null),
    /** The internal tree the provider serves, `<cacheDir>/cluster-artwork`. */
    private val cacheRoot: File = File(context.cacheDir, CLUSTER_ARTWORK_DIRECTORY),
    /** Whether the merged manifest registered the artwork provider (the car answers from its package manager). */
    private val providerRegistered: () -> Boolean = {
        runCatching {
            context.packageManager.resolveContentProvider(clusterArtworkAuthority(context.packageName), 0) != null
        }.getOrDefault(false)
    },
) {
    private val session = newClusterArtworkSession()
    private val externalRoot = externalFilesDir?.let { File(it, CLUSTER_ARTWORK_DIRECTORY) }
    private val cacheDirectory = File(cacheRoot, session)
    private val retention = ClusterArtworkRetention()

    init {
        cleanStaleClusterArtworkSessions(cacheRoot, session)
        externalRoot?.let { cleanStaleClusterArtworkSessions(it, session) }
        cleanLegacyClusterArtworkFiles(externalFilesDir)
    }

    /**
     * Encodes [bitmap] and returns the Uri to hand to the bridge, or null when nothing was produced.
     *
     * The cover is always encoded into the cache first, because that tree is the reliably writable one
     * and because the provider tier serves from it; the primary tier then *moves* those exact bytes to
     * the external copy the car reads. Only one of the two survives a handover: the cache file is
     * deleted once the file tier has it, and the external copy is deleted when the file tier gave up,
     * so neither tier leaves a file behind for the other. The quality ladder means a noisy cover is
     * retried smaller rather than dropped.
     */
    fun publish(bitmap: Bitmap): Uri? {
        val encoded = encodeCover(bitmap) ?: return null
        val external = promoteToExternal(encoded)
        if (external != null) {
            // Moved, not copied: nothing will serve the cache copy now that the provider tier lost.
            runCatching { encoded.delete() }
            evict(retention.add(ClusterArtworkRetention.Entry(external, null)))
            return Uri.fromFile(external)
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
     * The primary tier: copies the encoded cover into `<externalFilesDir>/cluster-artwork/<session>/`
     * under the same UUID name and returns it, or null when that tree cannot serve it.
     *
     * `setReadable(true, false)` is best-effort and its answer is only logged: the project already
     * learned that chmod on the emulated `/sdcard` view does not necessarily reach another UID
     * (docs/_digest/38 §3), so a false from it must not cost the cover. What does decide the tier is
     * [clusterArtworkFileReadable]: a copy that does not read back here is deleted and the fallback
     * runs, so the external tree never keeps a file no tier owns.
     */
    private fun promoteToExternal(encoded: File): File? {
        val root = externalRoot ?: return null
        val directory = File(root, session)
        val target = File(directory, encoded.name)
        return runCatching {
            if (!directory.isDirectory && !directory.mkdirs()) {
                Log.w(TAG, "cluster artwork external directory unavailable dir=$directory")
                return@runCatching null
            }
            encoded.inputStream().use { input -> target.outputStream().use { output -> input.copyTo(output) } }
            val worldReadable = runCatching { target.setReadable(true, false) }.getOrDefault(false)
            if (!clusterArtworkFileReadable(target)) {
                Log.w(TAG, "cluster artwork external copy unreadable file=$target chmod=$worldReadable")
                target.delete()
                return@runCatching null
            }
            Log.i(TAG, "cluster artwork tier=file chmod=$worldReadable bytes=${target.length()} uri=${Uri.fromFile(target)}")
            target
        }.getOrElse {
            Log.w(TAG, "cluster artwork external copy failed", it)
            target.delete()
            null
        }
    }

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
 * Deletes the cover directories a previous process left behind in [root].
 *
 * A process that is killed mid-drive never revokes its grants or removes its covers, so the next
 * start cleans up after it -- in both trees, since the file tier's copy is not visible to the
 * provider and the provider's is not visible to the file tier. Only directories that are exactly a
 * session UUID are touched, and only inside the given root, so a bug here can not walk out of the
 * app's storage. [activeSession] is spared because it belongs to the store that is doing the cleaning.
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
