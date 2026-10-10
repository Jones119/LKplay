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
 * How one album cover reaches the head unit, in the order of preference the working reference
 * implementation uses on this exact car (docs/_digest/45 §2.4, `LegacyArtworkLoader`).
 *
 * Three tiers exist, and each one exists for a measured reason:
 *
 * 1. **JPEG, quality 88, longest edge <= 256 px, encoded < 1 MiB.** The head unit's own reader is
 *    built around those numbers and the vendor's loader rejects anything else before it ever gets
 *    there. We used to hand over a full-size PNG, which is both far larger and a format the reader
 *    was never proven to take.
 * 2. **`content://<package>.artwork/covers/<session>/<uuid>.jpg` plus an explicit
 *    `grantUriPermission` to each reader package.** A `file://` Uri depends on the emulated-storage
 *    FUSE view and on mode bits that a different UID does not necessarily see, which is why the
 *    reference grants the read permission explicitly instead of relying on the file being readable.
 * 3. **The `file://` path.** It is the tier that was in place before this change and that did show
 *    the first track's cover on the car, so it is kept as the fallback for the case where the
 *    provider is missing from the merged manifest or every grant is refused. Tier 3 of the reference
 *    -- a loopback HTTP server -- is deliberately *not* built here: the two cheap tiers are
 *    unverified on the car, and the HTTP one costs a port, a lifetime and a socket for a benefit
 *    that only exists if both cheaper tiers fail (docs/_digest/45 §7.4).
 *
 * Everything that can be decided without a device lives in this file as a pure function, so the
 * scale, the byte budget, the Uri shape, the grant list and the path mapping are unit-tested; only
 * the compression, the grant and the file I/O need the car (see [ClusterArtworkStore]).
 */

/** `<applicationId>.artwork`: the authority registered by the provider in `:common`'s manifest. */
internal const val CLUSTER_ARTWORK_AUTHORITY_SUFFIX = ".artwork"

/** The path segment the reference uses (`.../covers/<session>/<uuid>.jpg`). */
internal const val CLUSTER_ARTWORK_PATH = "covers"

/** `<cacheDir>/cluster-artwork/<session>/`; a cache, never external storage, for the provider tier. */
internal const val CLUSTER_ARTWORK_DIRECTORY = "cluster-artwork"

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
 * `com.ecarx.dim_interaction` is the DIM service whose log line proves the channel works (the
 * `albumCover2` line in docs/_digest/48 §1); `ecarx.xsf.mediacenter` is the car's own media centre,
 * which shows the same artwork elsewhere on the head unit.
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

/** Which of the two cheap tiers the handover ended in. */
internal enum class ClusterArtworkTier {
    /** Tier 2: `content://` with an explicit read grant for the vendor's reader processes. */
    CONTENT,

    /** Tier 3: the `file://` Uri the previous implementation used. */
    FILE,
}

/**
 * The tier to use, given what the device answered.
 *
 * The `content://` tier needs both halves: a provider that the merged manifest actually registered
 * (otherwise the Uri resolves to nothing) and at least one granted reader (otherwise the reader gets
 * `SecurityException` instead of bytes). Either half missing means the fallback, which is what the
 * car already proved readable.
 */
internal fun clusterArtworkTier(providerRegistered: Boolean, granted: Int): ClusterArtworkTier =
    if (providerRegistered && granted > 0) ClusterArtworkTier.CONTENT else ClusterArtworkTier.FILE

/**
 * Maps a `content://<package>.artwork/covers/<session>/<uuid>.jpg` Uri back to the cached file.
 *
 * Public because the provider itself lives in `:common` -- `internal` in this module is not visible
 * from there -- while the writer and the eviction logic stay here. Both sides must agree on the
 * shape, so there is exactly one implementation of it, and it takes plain values rather than an
 * `android.net.Uri` so it is unit-testable without a device.
 *
 * Returns null for anything that is not our authority, carries a query or fragment, has the wrong
 * number of path segments, or names a file that is not there: a provider that guessed would hand out
 * files outside the cover directory.
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
 * leave every cover of the session readable in the cache (and the grant table growing).
 */
internal class ClusterArtworkRetention(private val limit: Int = CLUSTER_ARTWORK_MAX_RETAINED) {
    /** One handed-over cover: the file on disk and the Uri granted for it, if the content tier ran. */
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
 * Device-only work: JPEG compression, the session directory, the provider check, the grants and the
 * `file://` fallback. What can be decided without a device is delegated to the pure functions above.
 * A [ClusterArtworkStore] is created once per process: its session directory lets the next process
 * recognise and delete this run's leftovers (see [cleanStaleClusterArtworkSessions]).
 */
internal class ClusterArtworkStore(private val context: Context) {
    private val session = newClusterArtworkSession()
    private val root = File(context.cacheDir, CLUSTER_ARTWORK_DIRECTORY)
    private val directory = File(root, session)
    private val retention = ClusterArtworkRetention()

    init {
        cleanStaleClusterArtworkSessions(root, session)
    }

    /**
     * Encodes [bitmap] and returns the Uri to hand to the bridge, or null when nothing was produced.
     *
     * The encoded file always lands in the cache first, because the provider tier serves it from
     * there; only when that tier is unusable is the same encoded bytes copied into the app's external
     * files directory, where a `file://` reader can reach them. The quality ladder means a noisy
     * cover is retried smaller rather than dropped.
     */
    fun publish(bitmap: Bitmap): Uri? {
        val file = encodeCover(bitmap) ?: return null
        val authority = clusterArtworkAuthority(context.packageName)
        val providerRegistered = runCatching {
            context.packageManager.resolveContentProvider(authority, 0) != null
        }.getOrDefault(false)
        val uri = Uri.parse(clusterArtworkUriText(context.packageName, session, file.name))
        val granted = if (providerRegistered) grant(uri) else 0
        Log.i(TAG, "cluster artwork grants=$granted provider=$providerRegistered uri=$uri")
        if (clusterArtworkTier(providerRegistered, granted) == ClusterArtworkTier.CONTENT) {
            evict(retention.add(ClusterArtworkRetention.Entry(file, uri)))
            return uri
        }
        val fallback = copyToExternal(file) ?: return null
        // The cache copy only exists for the provider tier, which just proved unusable: leaving it
        // behind would be a file nothing can read and nothing ever deletes.
        runCatching { file.delete() }
        evict(retention.add(ClusterArtworkRetention.Entry(fallback, null)))
        return Uri.fromFile(fallback)
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
            if (directory.isDirectory || directory.mkdirs()) {
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
        val file = File(directory, newClusterArtworkFileName())
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

    /**
     * The `file://` tier: the encoded bytes copied next to the app's other exported files.
     *
     * The reader is another process, and the default 0660 leaves the file unreadable for anything not
     * in this app's group, which is what the car's service is -- the same rule the previous
     * implementation relied on when it wrote its PNG here.
     */
    private fun copyToExternal(encoded: File): File? = runCatching {
        val directory = context.getExternalFilesDir(null) ?: return null
        val file = File(directory, "cluster-artwork-${System.currentTimeMillis()}.jpg")
        encoded.inputStream().use { input -> file.outputStream().use { output -> input.copyTo(output) } }
        runCatching { file.setReadable(true, false) }
        directory.listFiles()?.forEach { candidate ->
            if (candidate.name.startsWith("cluster-artwork-") && candidate != file) {
                runCatching { candidate.delete() }
            }
        }
        file
    }.getOrElse {
        Log.w(TAG, "cluster artwork file fallback failed", it)
        null
    }
}

/**
 * Deletes the cover directories a previous process left behind in [root].
 *
 * A process that is killed mid-drive never revokes its grants or removes its covers, so the next
 * start cleans up after it. Only directories that are exactly a session UUID are touched, and only
 * inside the given root, so a bug here can not walk out of the app's cache. [activeSession] is spared
 * because it belongs to the store that is doing the cleaning.
 */
internal fun cleanStaleClusterArtworkSessions(root: File, activeSession: String) {
    val entries = root.listFiles() ?: return
    for (candidate in entries) {
        if (!candidate.isDirectory || !UUID_PATTERN.matches(candidate.name)) continue
        if (candidate.name == activeSession) continue
        runCatching { candidate.deleteRecursively() }
    }
}
