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
import java.net.URI
import java.util.UUID

/**
 * How one album cover reaches the head unit, in the order this car was measured to accept.
 *
 * Three tiers remain, and the car -- not the reference implementation -- decided which one goes first:
 *
 * 1. **A loopback `http://127.0.0.1:<port>/covers/<session>/<uuid>.jpg` served by
 *    [ClusterArtworkHttpServer] out of the same shared file copy.** This is the tier the car's own
 *    behaviour points at, and it was added after two cheaper tiers had been delivered to the car and
 *    drawn by nothing:
 *
 *    | delivered | vendor received | cluster drew |
 *    |---|---|---|
 *    | `content://<pkg>.artwork/covers/…` + `grantUriPermission` (grants=2, provider=true) | yes, 3 UUIDs | no |
 *    | `file:///storage/emulated/0/Android/data/<pkg>/files/cluster-artwork/…` | yes, 3 UUIDs | no |
 *    | `file:///sdcard/DiPlay/cluster-artwork/<session>/<uuid>.jpg` | yes, 36 + 6 lines | no |
 *
 *    The third row is provably readable by another process -- `adb shell ls -l` shows
 *    `-rw-rw---- root sdcard_r`, the same shape as the car media app's own JPEGs under
 *    `/sdcard/Music/mediacenter/images/`, and `adb shell cat` returns all 31326 bytes for a
 *    shell uid that holds only `sdcard_r`/`sdcard_rw` -- and the cluster still drew none of them.
 *    Meanwhile the car's **own** media application, whose covers the cluster does display and switch
 *    per track, hands it `http://y.gtimg.cn/music/photo_new/T002R300x300M000000FUEPg2e4lIX_3.jpg` for
 *    its online tracks (its local-file entries in that same `/sdcard/Music/mediacenter/images/` tree
 *    are the ones we have never seen drawn). The working hypothesis is therefore that **the reading
 *    process fetches a remote `http`/`https` URL and never opens a `content` or `file` Uri**, which is
 *    also why the reference implementation carries a loopback HTTP server as a tier at all and enables
 *    it only on this model (docs/_digest/45 §2.4). The server is [ClusterArtworkHttpServer]; it starts
 *    lazily on the first cover and stops when the bridge is torn down, and its KDoc records the port,
 *    threading and lifecycle design in full.
 *
 *    This tier is *preferred* but strictly optional: the cover is still written to the shared tree
 *    first, so when the server cannot bind the very same file is delivered as `file://` and the
 *    fallbacks below are unchanged. One handover therefore never depends on a port being free.
 *
 * 2. **A world-readable `file://` copy under a directory the reading process may actually open.**
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
 *    The *spelling* of the delivered path matters as much as the tree it points into, and that is the
 *    half the 2026-10-10 change still got wrong: it wrote the bytes into a shared tree and then handed
 *    over `/storage/emulated/0/...`, the framework's own answer, which resolves only inside our own
 *    process' mount namespace. `/sdcard` is the alias every other process on this ROM uses. See
 *    [CLUSTER_ARTWORK_SDCARD_ROOT] for the `adb` evidence and [clusterArtworkSdcardSpelling] for the
 *    rule that rewrites a validated path onto it.
 *
 *    A `content://` handover is still *below* this tier. On 2026-10-09 it succeeded three times in a
 *    row (`artwork decoded id=…` -> `cluster cover identity=… published=true` -> `cluster artwork
 *    encoded bytes=…` -> `cluster artwork grants=2 provider=true uri=content://…`, and the vendor's own
 *    service logged every new Uri as `albumCover2 = content://…`) while the instrument cluster never
 *    painted one of them. A reader that accepts our Uri and then never opens it is not something grants
 *    or a manifest can fix from here, so the file goes first.
 * 3. **`content://<package>.artwork/covers/<session>/<uuid>.jpg` plus an explicit
 *    `grantUriPermission` to each reader package**, kept for a car whose external tree is missing or
 *    unwritable. It stays fully wired (the provider is registered in `:common`'s manifest). This is
 *    the tier docs/_digest/45 §2.4 recorded from the reference app's `LegacyArtworkLoader` -- content
 *    provider plus grant first, then a loopback HTTP server -- and our own car disagreed with that
 *    order twice over, which is why the server is first here and the provider last.
 *
 * Encoding is not a tier. Whichever path serves the bytes, the head unit's reader was built around
 * JPEG at quality 88, a longest edge of at most 256 px and an encoded size under 1 MiB, so a cover is
 * scaled, encoded and size-checked identically here: we used to hand over a full-size PNG, which is
 * both far larger and a format that reader was never proven to take.
 *
 * Everything that can be decided without a device lives in this file as a pure function, so the
 * scale, the byte budget, the Uri shape, the grant list, the tier precedence, the override, the path
 * mapping, the candidate order and how a candidate is accepted or rejected are unit-tested; only the
 * compression, the real read-back, the socket and the file I/O need the car (see [ClusterArtworkStore]
 * and [ClusterArtworkHttpServer]).
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
 * The external-storage spelling a reader outside our process can resolve on this ROM: the literal
 * `/sdcard` alias.
 *
 * `Environment.getExternalStorageDirectory()` answers `/storage/emulated/0` on the car, and *inside our
 * own process* that spelling is a perfectly working path -- the store created it, wrote a JPEG into it
 * and read the same bytes back, which is why the 2026-10-10 probe passed -- but the path exists only in
 * that process' mount namespace. The global alias chain
 * `/sdcard` -> `/storage/emulated/legacy` -> `/mnt/shell/emulated/0` is what the shell (uid 2000) and
 * the vendor's reader resolve, and it is also the spelling the car's own media application delivers
 * (`file:///sdcard/Music/mediacenter/images/…`, docs/_digest/48). Both name one file, and the latest
 * `adb` run on the car is the whole evidence:
 *
 * ```
 * $ adb shell ls -l /sdcard/DiPlay/cluster-artwork/<session>/
 *   -rw-rw---- root sdcard_r 31326 …/4f928ddd-….jpg
 * $ adb shell cat /sdcard/DiPlay/cluster-artwork/<session>/4f928ddd-….jpg | wc -c
 *   31326                                    # the file our app wrote, readable from outside it
 * $ adb shell cat /storage/emulated/0/DiPlay/cluster-artwork/<session>/4f928ddd-….jpg | wc -c
 *   0    /  No such file or directory        # the string we actually delivered
 * ```
 *
 * That is why the delivered `file://` text has to carry this prefix, and why
 * [clusterArtworkSdcardSpelling] rewrites a path that validated under another spelling onto it before
 * anything is handed over.
 */
internal const val CLUSTER_ARTWORK_SDCARD_ROOT = "/sdcard"

/**
 * The other spellings of that one volume this ROM was measured to expose, without `/sdcard` itself.
 *
 * They are the prefixes [clusterArtworkSdcardSpelling] rewrites. `/mnt/sdcard` and `/storage/sdcard0`
 * are included because the candidate list already carries them as fallbacks for ROMs that disagree;
 * listing them here costs nothing and keeps "the same volume, another name" in one place. Longest
 * first is not needed -- no entry is a prefix of another -- but the boundary is checked on `/`, so
 * `/storage/emulated/0` never matches a directory called `/storage/emulated/0x`.
 */
private val CLUSTER_ARTWORK_VOLUME_SPELLINGS = listOf(
    "/storage/emulated/0",
    "/storage/emulated/legacy",
    "/mnt/shell/emulated/0",
    "/mnt/sdcard",
    "/storage/sdcard0",
)

/**
 * [path] rewritten onto its [CLUSTER_ARTWORK_SDCARD_ROOT] spelling, or null when there is nothing to
 * rewrite.
 *
 * Null means "deliver [path] as it is": the path already names the alias, it carries a volume this ROM
 * was never measured with, or it is a host path from a JVM test. The caller re-probes whatever this
 * returns through the rewritten string before it delivers it, so a wrong answer here costs one probe
 * and never a cover (see [ClusterArtworkBaseSelector]).
 *
 * The whole path is rewritten, not just its first segment, so the `Android/data/<pkg>/…` form of the
 * file tier gets `/sdcard/Android/data/<pkg>/…` as well. A trailing separator is dropped, because two
 * spellings of one directory would otherwise be probed twice for the same answer.
 */
internal fun clusterArtworkSdcardSpelling(path: String): String? {
    val compact = path.replace('\\', '/').trimEnd('/')
    if (compact.isEmpty()) return null
    for (root in CLUSTER_ARTWORK_VOLUME_SPELLINGS) {
        if (compact == root) return CLUSTER_ARTWORK_SDCARD_ROOT
        if (compact.startsWith("$root/")) {
            return CLUSTER_ARTWORK_SDCARD_ROOT + compact.substring(root.length)
        }
    }
    return null
}

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

/**
 * The cap on the covers that were written but never handed over on a URL.
 *
 * This is the *cache* bound, not the session's: a cover whose URL was already handed to the cluster is
 * never deleted while the session runs ([ClusterArtworkRetention]), because the reader fetches it
 * lazily and a URL the cluster still holds must not start answering `404`. What this constant caps is
 * the other kind -- the `content://` tier's cache copy, and a copy whose own tier lost the handover --
 * so the tree cannot grow with the number of tracks however a session goes. 32 covers of at most
 * [CLUSTER_ARTWORK_MAX_ENCODED_BYTES] each is the ceiling it states; a real 256 px cover is about
 * 25 KiB, so the cache side stays well under a megabyte in [CLUSTER_ARTWORK_DIRECTORY].
 *
 * The reference retains 4 and deletes the file of every evicted entry, live URL or not, which is the
 * behaviour that turned a painted cover into a `404` four tracks later. The number here is raised as
 * well, because a listening session changes track dozens of times and the *undelivered* entries are the
 * ones this bounds.
 */
internal const val CLUSTER_ARTWORK_MAX_RETAINED = 32

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

/**
 * A cover's session or file stem, as the provider, the sweeps and the loopback server all match it.
 *
 * `internal` rather than file-private because [ClusterArtworkHttpServer] uses the same pattern to
 * decide which path segments may name a cover: two spellings of "is this a cover name" would be one
 * more place for the two tiers to disagree about what they serve.
 */
internal val UUID_PATTERN = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

/** The name of the file a base probe writes: fixed per process, so a reused path cannot look fresh. */
private const val CLUSTER_ARTWORK_PROBE_FILE = "base-probe.jpg"

/**
 * The shared roots a `file://` cover may be written under, in the order they are probed.
 *
 * The literal `/sdcard` alias is first, and it is first *as text* -- not merely as "whatever the
 * framework answers" -- because that is the form the car's own media application uses for the covers
 * the cluster demonstrably displays, because it is the spelling the latest `adb` run proved resolvable
 * from outside our process, and because the 2026-10-10 build put the framework's own
 * `/storage/emulated/0` answer ahead of it and delivered a cover no reader could open (see
 * [CLUSTER_ARTWORK_SDCARD_ROOT]). The framework's answer, `/storage/emulated/legacy`, the shell's
 * `/mnt/shell/emulated/0` and `sdcard0` follow as fallbacks for ROMs that disagree; each is only ever
 * probed when every earlier candidate failed, so the normal case pays one probe. A fallback that wins
 * is still delivered on its `/sdcard` spelling when that spelling reads the cover back
 * ([clusterArtworkSdcardSpelling]).
 *
 * Duplicates are removed, because two spellings of one volume would otherwise be probed twice.
 */
internal fun clusterArtworkSharedBases(): List<String> = clusterArtworkSharedBasesFrom(
    listOf(
        CLUSTER_ARTWORK_SDCARD_ROOT,
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
 *
 * On this ROM the answer is `/storage/emulated/0`, which is a working path *for us* and no path at all
 * for anybody else (see [CLUSTER_ARTWORK_SDCARD_ROOT]). It stays a candidate -- writing through it and
 * reading the cover back through it is how this process finds out whether the volume is there at all --
 * but it is deliberately not the string that gets delivered: [clusterArtworkSdcardSpelling] turns it
 * into the `/sdcard` form before the handover.
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
 *
 * The caller puts [CLUSTER_ARTWORK_SDCARD_ROOT] first among [aliases], so the spelling a reader can
 * resolve is the first of these to be probed, exactly as in [clusterArtworkSharedBases]. This whole
 * family is the *second* choice: `/sdcard/Android/data/<pkg>/` is still unreadable for another UID on
 * this ROM (docs/_digest/48 §1), so it only ever carries a cover once no shared tree validated.
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
 * The bench override for the cover Uri: `debug.diplay.artwork_uri`.
 *
 * The 2026-10-09/10 runs handed the car three tiers and the cluster drew none of them, so the next
 * question is not which of *our* Uris is best but whether the reading process fetches a remote URL at
 * all. One ignition window with
 *
 * ```
 * adb shell setprop debug.diplay.artwork_uri 'http://y.gtimg.cn/music/photo_new/T002R300x300M000000FUEPg2e4lIX_3.jpg'
 * ```
 *
 * hands the vendor the exact URL the car's own media application uses for a cover the cluster is
 * known to display: if the cluster paints a cover that never passed through this app, the reader does
 * fetch HTTP and the loopback tier is the right shape; if it paints nothing, the problem is not the
 * Uri scheme and the remaining suspects are the vendor's own `setAlbumCover2` path or its use of
 * `albumCover2`, which no delivery string can fix. The property is read reflectively through the same
 * helper as `debug.diplay.arrow`/`debug.diplay.cluster` (never `android.os.SystemProperties`
 * directly: it is not public SDK and the JVM tests have no such class).
 */
internal const val CLUSTER_ARTWORK_URI_OVERRIDE = "debug.diplay.artwork_uri"

/** The `source=` value of a cover Uri that came from [CLUSTER_ARTWORK_URI_OVERRIDE]. */
internal const val CLUSTER_ARTWORK_SOURCE_OVERRIDE = "override"

/** The `source=` value of a cover Uri the tier walk computed. */
internal const val CLUSTER_ARTWORK_SOURCE_TIER = "tier"

/**
 * [property] as a Uri this app may hand the vendor, or null when it is not one.
 *
 * Only an absolute `http://`/`https://` URL is accepted, because that is the whole point of the
 * override: a bench session types one remote URL by hand and asks whether the reader fetches it. A
 * blank/unset property, a typo, a relative string, a `file://` path and every other scheme answer null
 * and leave the computed tier in charge, so a broken property can never cost the driver a cover --
 * which is the same rule [resolveClusterBridgeEnabled] and [parseArrowOverride] follow.
 *
 * Kept as a string rather than an `android.net.Uri`, so the rule is unit-tested on a JVM; the caller
 * parses the answer. An unset property and an unparseable one are both "no override" here, and the
 * caller's log line is what tells them apart (`property=` empty versus a value).
 */
internal fun parseArtworkUriOverride(property: String?): String? {
    if (property.isNullOrEmpty()) return null
    val trimmed = property.trim()
    val uri = runCatching { URI(trimmed) }.getOrNull() ?: return null
    val scheme = uri.scheme?.lowercase() ?: return null
    if (scheme != "http" && scheme != "https") return null
    if (uri.host.isNullOrEmpty()) return null
    // The trimmed text, so what the vendor is handed is what the URL check accepted: `setprop` from a
    // shell cannot add whitespace, but a quoted value typed on a bench occasionally does.
    return trimmed
}

/**
 * The bench override that pins which tier delivers the cover: `debug.diplay.artwork_tier`.
 *
 * Needed because [ClusterArtworkStore.publish] decides its own order inline -- a validated shared base
 * plus a bound loopback port hands over an `http://127.0.0.1` URL, and the tiers below it are only
 * reached when the port cannot bind. `debug.diplay.artwork_uri` cannot test the other shapes, because
 * it only accepts an absolute `http`/`https` URL (see [parseArtworkUriOverride]) and a bench cannot know
 * the per-track UUID file name a `file://`/`content://` delivery needs.
 *
 * Why this exists at all, measured on 2026-10-13 by decompiling the car's own DIM service
 * (`/system/app/XCDimInteractionService.apk`, package `com.ecarx.dim_interaction`, class
 * `MediaDimInteraction.updatePlayStatus`): the branch that actually sends a cover requires
 *
 * ```java
 * if (… && !coverString.contains(HTTP_PREFIX) && media.getBasePlayState() == 1)   // HTTP_PREFIX = "http"
 * ```
 *
 * and every other branch does `builder.albumCover2(0)` and logs `send messages except album cover.` --
 * which is exactly the line the car printed for our `http://127.0.0.1:<port>/covers/…` handover. An
 * `http` Uri therefore cannot reach the instrument cluster at all, whatever the loopback server answers.
 */
internal const val CLUSTER_ARTWORK_TIER_OVERRIDE = "debug.diplay.artwork_tier"

/**
 * [property] as the tier to force, or null to let [ClusterArtworkStore.publish] decide.
 *
 * Only the three tier names are accepted, case-insensitively and trimmed, so a bench typo leaves the
 * real order in charge instead of costing the driver a cover -- the same rule the other overrides follow.
 */
internal fun parseArtworkTierOverride(property: String?): ClusterArtworkTier? =
    when (property?.trim()?.lowercase()) {
        "http" -> ClusterArtworkTier.HTTP
        "file" -> ClusterArtworkTier.FILE
        "content" -> ClusterArtworkTier.CONTENT
        else -> null
    }

/** [CLUSTER_ARTWORK_TIER_OVERRIDE] as read from the system properties, or null when it is not forcing one. */
internal fun clusterArtworkTierOverride(): ClusterArtworkTier? =
    parseArtworkTierOverride(debugProperty(CLUSTER_ARTWORK_TIER_OVERRIDE))

/**
 * What the vendor is handed for one handover: the override when it is usable, the computed Uri
 * otherwise.
 *
 * The single precedence rule of the artwork path, and the reason the bench experiment needs no second
 * code path: `debug.diplay.artwork_uri` is handed over *instead of* whatever the tiers produced, so a
 * cover the cluster paints under the override proves the reader fetches that URL, and a cover it still
 * does not paint proves the problem is not the Uri at all. An unset, empty or unparsable property is
 * not an error state: it leaves [computed] in charge, so a typo on the bench cannot cost the driver the
 * cover the tiers would have delivered.
 *
 * [parseArtworkUriOverride] does the accepting, so this stays a two-line rule that cannot drift from
 * what the tests assert.
 */
internal fun clusterArtworkHandover(property: String?, computed: String?): String? =
    parseArtworkUriOverride(property) ?: computed

/**
 * Remembers the last cover Uri announced, so a per-track log line is not repeated for one Uri.
 *
 * `setArtwork` runs about twice a second while the iPhone is updating and the re-push repeats the same
 * payload every [CLUSTER_REPUSH_INTERVAL_MILLIS], so an unconditional line would bury the on-car
 * check, which greps for exactly that line. The key includes the source, because "the override stopped
 * applying" is worth seeing even when the Uri it forced happens to equal the computed one.
 *
 * Synchronised because the artwork path can be observed from a test thread while the real pushes all
 * run on the cluster worker thread.
 */
internal class ClusterArtworkUriChangeGate {
    private var last: String? = null

    /** True the first time [uri] is announced from [source], false for every repeat. */
    @Synchronized
    fun shouldLog(uri: String?, source: String): Boolean {
        val key = "$source/$uri"
        if (key == last) return false
        last = key
        return true
    }
}

/**
 * One `debug.diplay.*` property, read reflectively because `android.os.SystemProperties` is not part of
 * the public SDK. A missing class (the JVM unit tests) or a hidden-API refusal reads as "unset", which
 * every caller treats as "no override".
 */
internal fun debugProperty(name: String): String? = runCatching {
    val get = Class.forName("android.os.SystemProperties")
        .getMethod("get", String::class.java, String::class.java)
    get.invoke(null, name, "") as? String
}.getOrNull()

/**
 * Reads [CLUSTER_ARTWORK_URI_OVERRIDE], the bench override for the cover Uri.
 *
 * Read per handover rather than cached, so `adb shell setprop` reaches the very next track of a running
 * session -- the same rule the cluster switch and the arrow override follow -- and "unset" (the state
 * after every ignition, because `setprop` does not survive one) leaves the tier walk in charge.
 */
internal fun clusterArtworkUriProperty(): String? = debugProperty(CLUSTER_ARTWORK_URI_OVERRIDE)

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
    /** The preferred tier: our own loopback HTTP server, serving the shared copy (docs/_digest/45 §2.4). */
    HTTP,

    /** The first fallback: a readable `file://` copy in a tree the reader can open. */
    FILE,

    /** The last fallback: `content://` with an explicit read grant for the vendor's reader processes. */
    CONTENT,
}

/**
 * The tier to use, given what the device answered, or null when no tier can serve the cover.
 *
 * The loopback server is decided first because it is the only shape the car's own media application is
 * observed to use for a cover the cluster displays, and [httpStarted] is already the successful bind --
 * this function does not start anything. It is deliberately a *preference*, not a requirement: the
 * cover is written to the shared tree before this is called, so [fileUsable] is normally true here and
 * a car that refuses to bind a port still gets the `file://` string it got before this tier existed.
 *
 * The external copy is decided second because it is the only cheap one the car ever had on disk. The
 * provider is reached only when no base validated: either half of that tier missing -- no provider in
 * the merged manifest, or every grant refused -- means the cover is dropped instead, because a Uri its
 * reader can only fail on is worse than no cover at all.
 */
internal fun clusterArtworkTier(
    fileUsable: Boolean,
    providerRegistered: Boolean,
    granted: Int,
): ClusterArtworkTier? = clusterArtworkTier(
    httpStarted = false,
    fileUsable = fileUsable,
    providerRegistered = providerRegistered,
    granted = granted,
)

/**
 * The tier to use when the loopback server's state is known; [clusterArtworkTier] without [httpStarted]
 * is the same rule with the HTTP tier unavailable.
 *
 * Split rather than given a default so every existing caller keeps its meaning: the three-argument form
 * answers what a car with no loopback server would hand over, which is exactly the fallback order the
 * on-car 2026-10-10 run worked in.
 */
internal fun clusterArtworkTier(
    httpStarted: Boolean,
    fileUsable: Boolean,
    providerRegistered: Boolean,
    granted: Int,
): ClusterArtworkTier? = when {
    httpStarted -> ClusterArtworkTier.HTTP
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
 * Only the last-resort tier writes into [cacheDir]: the HTTP and `file://` tiers read the shared-tree
 * copy instead and never route a cover through the provider. Returns null for anything that is not our
 * authority, carries a query or fragment, has the wrong number of path segments, or names a file that
 * is not there: a provider that guessed would hand out files outside the cover directory.
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
 * The covers handed over so far, oldest first.
 *
 * **The retention rule.** A cover the cluster was *given a URL for* (`delivered = true`: the loopback
 * HTTP tier and the `file://` tier both hand over a string that names the file itself) is never handed
 * back for deletion while the session runs. The reader fetches it lazily -- it may ask for the cover of
 * the track that played ten minutes ago -- so deleting the file behind a live URL is what turned a
 * painted cover into a `404` and a blank cluster; that is the fault this class exists to prevent. Only
 * the *cache* copies (`delivered = false`: the `content://` tier's file and every cover whose tier was
 * superseded) count against [limit], which is the number of them that stays held, and the oldest of
 * them is what [add] answers when a new one arrives. A handover that never reached a URL therefore
 * cannot evict the URL that is on screen, however many tracks go by.
 *
 * The cost of that rule is stated rather than hidden: with every handover on the HTTP tier, nothing is
 * returned for deletion until [clear], so the session's tree holds one cover per distinct track. It is
 * bounded by the session (the store's `clear`/`close` deletes all of it, the next process sweeps the
 * directories of the last), and a 256 px JPEG is about 25 KiB, so an hour of listening is a fraction of
 * a megabyte. [limit] is what keeps the other direction bounded: a car that never gets a URL -- no
 * external base, no port, no provider -- still cannot accumulate cache copies without limit.
 *
 * The reference's own cap is 4 and it deletes the file of every evicted entry; that is exactly the
 * behaviour that made the fourth cover evict the third while the cluster might still ask for it.
 */
internal class ClusterArtworkRetention(private val limit: Int = CLUSTER_ARTWORK_MAX_RETAINED) {
    /**
     * One handed-over cover: the file on disk and the Uri granted for it, if the fallback tier ran.
     *
     * [delivered] is the whole retention rule: true when the string the vendor was handed names this
     * file (the HTTP URL and the `file://` path both do), false when the file is only a copy another
     * tier might still need -- the provider's cache copy, or a copy whose own tier lost the handover.
     */
    data class Entry(val file: File, val uri: Uri?, val delivered: Boolean = false)

    private val held = ArrayList<Entry>()

    /**
     * Records [entry] and returns the entries to delete, oldest first.
     *
     * Only undelivered entries are ever returned: the check is on the entry's own flag and not on its
     * position, so a delivered cover survives any number of sweeps. The count that decides is the number
     * of *undelivered* entries held, and [limit] is the cap on that count, exactly as it was on the
     * reference's four: `limit` covers are held, and the handover after them hands the oldest one back
     * for deletion -- so a drive never keeps more cache copies than the constant states. A session whose
     * covers all reached a URL therefore never returns anything here at all, which is the point: the
     * delivered ones are the bytes a lazily fetching cluster can still ask for, and they go only at
     * [clear].
     */
    fun add(entry: Entry): List<Entry> {
        held.add(entry)
        // Walk the oldest first and take cache copies until the undelivered count fits the cap. Counted
        // rather than sliced, because the delivered entries between them must not be touched and must
        // also not be deleted: `held.size - limit` would take a delivered entry with it.
        var undelivered = held.count { !it.delivered }
        if (undelivered <= limit) return emptyList()
        val evicted = ArrayList<Entry>(held.size)
        val survivors = ArrayList<Entry>(held.size)
        for (candidate in held) {
            if (undelivered > limit && !candidate.delivered) {
                evicted.add(candidate)
                undelivered--
            } else {
                survivors.add(candidate)
            }
        }
        held.clear()
        held.addAll(survivors)
        return evicted
    }

    /** How many covers are held, delivered and cache copies together; the tests assert the cap here. */
    internal fun heldCount(): Int = held.size

    /**
     * Forgets what is held; the caller is responsible for revoking and deleting it.
     *
     * This is the one path that hands delivered covers back, because it is also the end of the session
     * that made them fetchable: nothing will ask for that URL again after the caller has run, which is
     * the same statement [ClusterArtworkHttpServer.shutdown] makes for the port.
     */
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
 *
 * That shape rule is now also applied to the *spelling* of the base: the base this class answers is the
 * one that will be delivered, so a candidate that read its bytes back under `/storage/emulated/0/…` is
 * re-probed through its `/sdcard` spelling and the alias is adopted only when it reads the same bytes
 * back in turn (see [deliverableBase]). The probe stays the ordinary one -- one write, one read of the
 * delivered bytes, one delete -- so the alias is never trusted for free.
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

    /**
     * The base a previous handover proved *and delivered on*, or null while a probe has to run.
     *
     * It is the delivered spelling, not the candidate that won the walk, so a store reading this back
     * (or a test asserting it) sees the same string the reader was given.
     */
    val currentBase: String? get() = chosen

    /**
     * The first candidate that accepts [source] and reads it back, as the spelling to deliver on, or
     * null when none does.
     *
     * [source] is the freshly encoded cover, so validation compares the real payload rather than a
     * marker string; the probe copy is deleted again, whatever the answer. The answered string is the
     * one the last successful probe read back -- the `/sdcard` spelling of the candidate when that
     * alias validated too (see [deliverableBase]) -- so the caller can build the delivered path from it
     * directly.
     */
    fun select(source: File): String? {
        chosen?.let { return it }
        // The base whose own delivery failed *after* it had been chosen is probed again, even though it
        // is in the refused set: a volume that comes back must become usable without an app restart, and
        // one failure must not keep a directory out of the search for the rest of the drive. It is
        // probed once, and a second refusal is not logged, because the on-car log already has the reason.
        val retry = suspect
        suspect = null
        if (retry != null) probeCandidate(retry, source)?.let { return it }
        for (candidate in candidates) {
            if (candidate == retry || candidate in rejected) continue
            probeCandidate(candidate, source)?.let { return it }
        }
        return null
    }

    /**
     * Probes one candidate, adopts it when it validates, and answers the base to *deliver* on.
     *
     * The answer can differ from [candidate]: a base that validated under a spelling only this process
     * can resolve is delivered on its `/sdcard` spelling instead (see [deliverableBase]). Answering the
     * delivered spelling -- rather than a boolean with the caller re-deriving the base -- is what keeps
     * the retry path of [select] and the ordinary walk from handing `publish` a different base than the
     * one the last successful probe read back.
     */
    private fun probeCandidate(candidate: String, source: File): String? =
        when (val probe = probe(candidate, source)) {
            is ClusterArtworkBaseProbe.Valid -> {
                val delivered = deliverableBase(candidate, source)
                chosen = delivered
                Log.i(TAG, "cluster artwork base=$delivered (probe ok)")
                delivered
            }
            is ClusterArtworkBaseProbe.Invalid -> {
                rejected += candidate
                if (reported.add(candidate)) {
                    runCatching { onRejected.rejected(candidate, probe.reason) }
                }
                null
            }
        }

    /**
     * The base to deliver on: [base] itself, or its `/sdcard` spelling once that spelling has read the
     * cover back too.
     *
     * This is the one place where a spelling the framework handed us can win its probe and still not be
     * the string the reader is told to open. `/storage/emulated/0/DiPlay` and `/sdcard/DiPlay` are one
     * directory inside this process while only the alias resolves in the DIM service's namespace (see
     * [CLUSTER_ARTWORK_SDCARD_ROOT]), so a base that validated under the framework's spelling is
     * re-probed through the alias with the ordinary rule -- the same write, the same read-back of the
     * same bytes and the same delete against `<alias>/cluster-artwork/<session>/`. Nothing weaker is
     * accepted here: the alias is adopted only after it has read the cover back itself, and it is then
     * the base `publish` writes to and reads back, so the delivered string is a validated one by
     * construction.
     *
     * One extra write-and-read per process is the whole cost, and only when a rewrite happens: the
     * shared candidate list already puts `/sdcard` first, so the normal case never reaches this.
     *
     * A rewrite that does not read the same bytes back keeps [base] and says so. An alias nobody can
     * open is worth one log line, while refusing the cover because its *portable* spelling is missing
     * would cost the driver the only cover this process can prove is on disk -- a spelling that is not
     * ideal still beats the blank the car shows today.
     */
    private fun deliverableBase(base: String, source: File): String {
        val alias = clusterArtworkSdcardSpelling(base) ?: return base
        return when (val probe = probe(alias, source)) {
            is ClusterArtworkBaseProbe.Valid -> {
                Log.i(TAG, "cluster artwork alias $base -> $alias")
                alias
            }
            is ClusterArtworkBaseProbe.Invalid -> {
                // Not routed through `onRejected`: that log is about a base being unusable, and this one
                // *is* usable -- it is only its portable spelling that is not, which the driver's log
                // has to be able to tell apart.
                Log.w(TAG, "cluster artwork alias $base -> $alias refused reason=${probe.reason}")
                base
            }
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
 * copy with its read-back of the delivered string, the loopback HTTP server, the provider check and
 * the grants. What can be decided without a device is delegated to the pure functions above. A
 * [ClusterArtworkStore] is created once per process: its session directory lets the next process
 * recognise and delete this run's leftovers (see [cleanStaleClusterArtworkSessions]).
 *
 * The device facts the tier decision needs ([externalFilesDir], [cacheRoot], [providerRegistered]) are
 * constructor parameters, so all three tiers can be exercised in a JVM test; the car passes its own
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
    /**
     * The loopback HTTP tier, created on the first cover and bound only once a base validated.
     *
     * Injected so a JVM test can hand over a server whose bind is already known to fail and still
     * assert the `file://` fallback; the car leaves the default, which is the *process-wide* server
     * ([clusterArtworkProcessServer]): one bind for the whole session, so a URL the cluster was handed
     * keeps answering after the host activity that produced it is gone. It is bound lazily in [publish]
     * ([ClusterArtworkHttpServer.start] on every handover, which answers an already-running server as it
     * is), so a process that never publishes a cover never opens a port, and the port is released by
     * `VendorClusterBridge.release` rather than by this store's own [close].
     */
    private val server: ClusterArtworkHttpServer = clusterArtworkProcessServer(),
    /**
     * How many *undelivered* covers this store keeps; the car leaves it at the documented cap.
     *
     * A parameter rather than a constant read inside [publish], so a test can drive the sweep with two
     * covers instead of [CLUSTER_ARTWORK_MAX_RETAINED] and still assert the real rule: a cover that was
     * handed over on a URL is never the one the sweep deletes.
     */
    private val retentionLimit: Int = CLUSTER_ARTWORK_MAX_RETAINED,
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
     * given a path there) and the first of them is the literal `/sdcard` alias, then the framework's
     * external files directory with the alias spelling of its `Android/` tail ahead of it. Whichever
     * candidate wins, the base handed to [publish] is the one whose own probe read the cover back
     * through the string that will be delivered -- the alias spelling when it works, the spelling that
     * did when it does not. On API 29+ the sandbox alias is dropped: the platform documents that path
     * as unreadable for other apps there, so it would only add a rejection line to a tablet that does
     * not need it.
     */
    private val externalDirectories: List<String> = ArrayList<String>(resolvedSharedBases.size + 4).apply {
        addAll(resolvedSharedBases)
        externalFilesDir?.let { add(File(it, CLUSTER_ARTWORK_DIRECTORY).path) }
        if (sdkInt < Build.VERSION_CODES.Q) {
            addAll(
                frameworkAliasCandidates(
                    externalFilesDir,
                    // `/sdcard` first: of these spellings it is the one a reader outside our process can
                    // open, and the framework's own answer is the one it cannot.
                    listOfNotNull(
                        File(CLUSTER_ARTWORK_SDCARD_ROOT),
                        externalStorageRoot()?.let(::File),
                        File("/storage/emulated/legacy"),
                    ),
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

    private val retention = ClusterArtworkRetention(retentionLimit)

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
     * base that validated, and the HTTP tier serves that very file. Only one of the three survives a
     * handover: the cache copy is deleted once the external write succeeded (`file://` and the loopback
     * server both read the external copy, so nothing serves the cache one any more), and the external
     * copy is deleted when the file tier gave up, so no tier leaves a file behind for another. The
     * quality ladder means a noisy cover is retried smaller rather than dropped.
     *
     * A delivered cover is *retained*, not swept: `retention.add` is told which entries the vendor was
     * handed a URL for ([ClusterArtworkRetention.Entry.delivered]) and only ever answers cache copies
     * for deletion, so a cover the cluster may still ask for cannot be deleted by a later track -- which
     * is what the reference's four-cover cap did, and what turned a painted cover into a `404`. The
     * bound the session does have is the covers that were never handed over on a URL, plus the fact that
     * the whole tree goes at [clear].
     *
     * [ClusterArtworkBaseSelector.select] answers the *delivered* spelling -- `/sdcard/…` whenever that
     * alias read the cover back -- so the file target built from it and the string logged as `uri=` are
     * the same text the last successful probe validated, on this ROM and in the reader's namespace. The
     * loopback server is *started* only after that write succeeded and is told about the directory it
     * must serve, so a car whose storage refuses the cover never pays for a port either.
     */
    fun publish(bitmap: Bitmap): Uri? {
        val encoded = encodeCover(bitmap) ?: return null
        // A forced tier skips the shapes it did not ask for. CONTENT is served by the provider out of
        // the *cache* copy, so it must not take the shared-tree write; FILE and HTTP both need it.
        val forced = clusterArtworkTierOverride()
        val base = if (forced == ClusterArtworkTier.CONTENT) null else selector.select(encoded)
        if (base != null) {
            // The cover lands under the session directory of the base that just read its own probe back.
            val directory = File(File(base, CLUSTER_ARTWORK_DIRECTORY), session)
            val target = File(directory, encoded.name)
            if (deliverToBase(encoded, target)) {
                val serving = serverOrNull(directory)
                if (serving != null && forced != ClusterArtworkTier.FILE) {
                    // Moved, not copied: the loopback server reads the external file per request, so the
                    // cache copy has no reader left. The server was told about `directory` before the URL
                    // that names it was returned, so the URL cannot 404 on a handover that just worked.
                    runCatching { encoded.delete() }
                    val url = serving.uriFor(target)
                    evict(retention.add(ClusterArtworkRetention.Entry(target, null, delivered = true)))
                    Log.i(
                        TAG,
                        "cluster artwork tier=http port=${serving.port} bytes=${target.length()} " +
                            "uri=$url source=$CLUSTER_ARTWORK_SOURCE_TIER",
                    )
                    return Uri.parse(url)
                }
                // No port this time -- or the bench pinned this tier: the very same file is delivered on
                // its `file://` string, which is also a string that names this file, so it is retained
                // exactly like the URL above.
                val delivered = target.absolutePath
                runCatching { encoded.delete() }
                evict(retention.add(ClusterArtworkRetention.Entry(target, uriFor(delivered), delivered = true)))
                Log.i(
                    TAG,
                    "cluster artwork tier=file forced=${forced ?: "none"} port=${serving?.port ?: "none"} " +
                        "bytes=${target.length()} uri=${uriFor(delivered)} source=$CLUSTER_ARTWORK_SOURCE_TIER",
                )
                return uriFor(delivered)
            }
            // The volume went away between the probe and the write: re-probe on the next track.
            selector.invalidate(base)
        }
        val uri = Uri.parse(clusterArtworkUriText(context.packageName, session, encoded.name))
        val registered = providerRegistered()
        val granted = if (registered) grant(uri) else 0
        Log.i(
            TAG,
            "cluster artwork fallback grants=$granted provider=$registered " +
                "source=$CLUSTER_ARTWORK_SOURCE_TIER uri=$uri",
        )
        if (clusterArtworkTier(fileUsable = false, providerRegistered = registered, granted = granted) ==
            ClusterArtworkTier.CONTENT
        ) {
            // A provider Uri is a name, not this file's own path, and the file is the *cache* copy the
            // sweep may reclaim: this is the one handover whose bytes may be deleted while it is live,
            // which is why it stays undelivered here.
            evict(retention.add(ClusterArtworkRetention.Entry(encoded, uri)))
            return uri
        }
        // No tier can serve it: a cached file nobody may read is a leak, not a cover.
        runCatching { encoded.delete() }
        return null
    }

    /**
     * The started loopback server, or null when it cannot serve this handover.
     *
     * The server is bound once per *process* and the directory it serves is (re-)registered on every
     * handover, because a base that was refused earlier can come back, the cover can move with it, and a
     * store created after an activity recreate has a session directory the shared server has not seen
     * yet. [ClusterArtworkHttpServer.start] answers the running server itself, so the port never moves
     * under a URL the cluster already holds. A failed bind is cached in the negative sense -- `start`
     * answers null without retrying a port it cannot have, and this returns null so the caller delivers
     * `file://` -- which is what keeps a broken port from costing a cover or a CPU loop per track; the
     * cost is one `Log.e` per handover, which is the point of it.
     */
    private fun serverOrNull(directory: File): ClusterArtworkHttpServer? {
        val started = server.start() ?: return null
        started.register(directory)
        return started
    }

    /**
     * Revokes and deletes every cover this store handed over, delivered ones included.
     *
     * This is the session-end path: the caller is stating that no URL of this store will be fetched
     * again, which is why it is the one operation [ClusterArtworkRetention] does not protect a delivered
     * cover from. For the same reason it does **not** stop the loopback server -- a shared, process-wide
     * port is not this store's to close, and closing it here is exactly the defect that made a lazy
     * fetch return `connection refused` after the host activity went away. `VendorClusterBridge.release`
     * is what ends both, together.
     */
    fun clear() {
        evict(retention.clear())
    }

    /**
     * Ends this store: every cover it handed over is revoked and deleted.
     *
     * Called when the CarPlay session ends, not when an Activity is destroyed -- an Activity that is
     * merely recreated must leave the cluster's URL answering, and the store (with the covers it
     * retains) is reused by the next `setArtwork`. The empty session directory is left in place: the
     * next process' sweep removes it ([cleanStaleClusterArtworkSessions]), and deleting it here would be
     * the same disk work twice for a directory this session may publish into again. The loopback *port*
     * is deliberately not touched here: it belongs to the process-wide server and is released by
     * [releaseClusterArtworkServer] at the same moment. Idempotent, because [clear] is.
     */
    fun close() {
        clear()
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
     * [target] is built from the base [ClusterArtworkBaseSelector] answered, so on this ROM the text
     * below reads `uri=file:///sdcard/DiPlay/…` whenever the alias spelling validated; the framework's
     * `/storage/emulated/0/…` form only appears when the alias could not be read back, and then it is
     * the only string this process could prove, not a preferred one.
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
                    "uri=${clusterArtworkFileUriText(delivered)} source=$CLUSTER_ARTWORK_SOURCE_TIER",
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





