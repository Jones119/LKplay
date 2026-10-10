package com.shilapi.xcertplay.media

import android.graphics.Bitmap
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Which tier a handover ends in and what the delivered string is, driven through the real
 * [ClusterArtworkStore] (docs/_digest/38 §3, the 2026-10-09 on-car run and the 2026-10-10 `adb` one).
 *
 * The pure rules are asserted in `ClusterArtworkTransportTest` and
 * `ClusterArtworkBaseSelectionTest`; this file checks the part that needs real file I/O -- that the
 * bytes really are written where the delivered string says they are, that a tier which cannot write
 * falls through to the next one and then to the provider, and that neither tier leaves its file
 * behind for the other. The device facts the store needs are constructor parameters, so the answers can
 * be produced without a car: the shared base it writes into, the external directory, the internal tree
 * the provider serves from, and whether the provider is registered.
 *
 * The shared base is injected as a temporary directory rather than `/sdcard/DiPlay`, because only the
 * car can confirm that `/sdcard` resolves for the reader there. What this file proves is the
 * mechanism: the cover is written into `<base>/cluster-artwork/<session>/<uuid>.jpg` and the Uri
 * handed over is `file://` plus that exact path. **Only the car can confirm** that the DIM service's
 * reader opens that string, which is what `cluster artwork tier=file … uri=file:///sdcard/DiPlay/…`
 * followed by a painted cover answers.
 *
 * The spelling rule added on top of that mechanism is decided by [ClusterArtworkBaseSelector] and
 * asserted in `ClusterArtworkBaseSelectionTest` on the injected filesystem; here only the two halves
 * that need real files are checked: that the store's own candidate list leads with the literal `/sdcard`
 * spelling of the shared tree however the ROM answers the framework, and that a `/sdcard` candidate
 * which cannot be written does not become the delivered string. **Only the car can confirm** the third
 * half -- that `/sdcard/DiPlay/cluster-artwork/<session>/<uuid>.jpg` and
 * `/storage/emulated/0/DiPlay/cluster-artwork/<session>/<uuid>.jpg` really are one file there while only
 * the first is a path the cluster's reader can open.
 *
 * Robolectric supplies the Context (the store grants through it) and a real JPEG encoder.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class ClusterArtworkStoreTierTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val context = RuntimeEnvironment.getApplication()

    /** Stands in for `/sdcard/DiPlay`: the shared tree the car's own media application also uses. */
    private val sharedBase: File by lazy { folder.newFolder("sdcard-DiPlay") }

    /** [sharedBase] as a store candidate: a path the store can build a [File] on. */
    private val sharedBasePath: String
        get() = sharedBase.path

    /** A candidate root that does not exist: on a car with no writable external tree. */
    private fun missingBasePath(name: String): String = File(folder.root, name).path

    @Test
    fun theSharedCandidatesStartWithTheSdcardSpellingTheReadersResolve() {
        // The whole 2026-10-10 bug in one assertion: the candidate list was the framework's own answer
        // (`/storage/emulated/0/DiPlay` on the car) ahead of the alias, so the walk adopted a spelling
        // that exists only inside our process' mount namespace. The literal `/sdcard` spelling is now
        // first whatever `Environment` answers -- and it stays first when it is also that answer, since
        // the list is de-duplicated.
        assertEquals(
            "/sdcard/$CLUSTER_ARTWORK_SHARED_ROOT",
            clusterArtworkSharedBases().first(),
        )
    }

    @Test
    fun aCandidateWhoseSdcardSpellingCannotBeWrittenDoesNotBecomeTheDeliveredString() {
        // A `/sdcard` candidate that cannot be written, next to a base that can: the cover must be
        // delivered on the base whose own write and read-back worked, and the `uri=` text must name that
        // base rather than a spelling nothing validated. On this host `/sdcard` is exactly that -- a
        // path no process can create -- which is the same rule the car applies when the alias is refused
        // (see `ClusterArtworkBaseSelectionTest` for the accepted-rewrite half).
        val cacheRoot = folder.newFolder("cache-root")
        val refusedAlias = "/sdcard/$CLUSTER_ARTWORK_SHARED_ROOT"
        val store = ClusterArtworkStore(
            context = context,
            externalFilesDir = null,
            cacheRoot = cacheRoot,
            providerRegistered = { true },
            sharedBases = listOf(refusedAlias, sharedBasePath),
            raw = withoutTheSdcardSpelling(),
        )

        val uri = store.publish(bitmap())

        assertNotNull("the cover must still be handed over", uri)
        assertEquals("file", uri!!.scheme)
        assertFalse(
            "a refused spelling must not appear in the delivered text: $uri",
            uri.toString().contains(refusedAlias),
        )
        val delivered = File(clusterArtworkUriPath(uri.toString()))
        assertTrue(
            "the delivered string names the base that was validated: ${uri}",
            sameDirectory(sharedBase, clusterArtworkBaseOf(delivered)),
        )
        // Read back through the delivered string itself, which is the string the store validated.
        assertTrue("the delivered file holds the cover: $delivered", clusterArtworkFileReadable(delivered))
        assertTrue(clusterArtworkWithinBudget(delivered.length()))
    }

    @Test
    fun aReadableSharedCopyIsTheUriHandedOverAndTheCacheCopyIsNotLeftBehind() {
        val cacheRoot = folder.newFolder("cache-root")
        val store = ClusterArtworkStore(
            context = context,
            externalFilesDir = null,
            cacheRoot = cacheRoot,
            providerRegistered = { true },
            sharedBases = listOf(sharedBasePath),
        )

        val uri = store.publish(bitmap())

        assertNotNull("the cover must be handed over", uri)
        assertEquals("file", uri!!.scheme)
        // The delivered *text* is what the reader is given, so that is what these assertions read: the
        // parsed path of a `file://C:\…` Uri loses its volume on the host's `Uri` implementation while
        // the string the store hands over keeps it.
        assertEquals(clusterArtworkFileUriText(clusterArtworkUriPath(uri.toString())), uri.toString())
        val file = File(clusterArtworkUriPath(uri.toString()))
        val sessionDirectory = file.parentFile!!
        // `<candidate>/cluster-artwork/<session>/<uuid>.jpg`, in the shared tree the store was handed as
        // its first candidate, not in any directory the app owns.
        assertTrue(
            "the cover sits in the shared cluster-artwork tree: ${uri.toString()} | candidate=$sharedBasePath",
            sameDirectory(sharedBase, clusterArtworkBaseOf(file)),
        )
        assertTrue("one session directory per process", sessionDirectory.name.length == 36)
        assertTrue("a fresh UUID file name per handover", file.name.length == 40)
        assertTrue("the handed-over file exists: $file", clusterArtworkFileReadable(file))
        // The cache copy exists only for the provider tier, which lost this handover.
        assertFalse("no cache session directory may survive the file tier", cacheRoot.listFiles().orEmpty().any {
            it.isDirectory && it.listFiles().orEmpty().isNotEmpty()
        })
        // The probe file must not be left next to the covers.
        assertFalse(
            "the base probe cleans up after itself",
            sessionDirectory.listFiles().orEmpty().any { it.name.contains("probe") },
        )
    }

    @Test
    fun aBaseThatCannotBeWrittenFallsThroughToTheNextCandidate() {
        // A file where the shared base should be: the write tier cannot create its session directory
        // there, which is the failure the second candidate exists for.
        val blocked = File(folder.newFolder("sdcard"), "DiPlay-is-a-file").apply { writeBytes(byteArrayOf(1)) }
        val externalRoot = folder.newFolder("external-files")
        val store = ClusterArtworkStore(
            context = context,
            externalFilesDir = externalRoot,
            cacheRoot = folder.newFolder("cache-root"),
            providerRegistered = { true },
            sharedBases = listOf(blocked.path),
        )

        val uri = store.publish(bitmap())

        assertNotNull(uri)
        assertEquals("file", uri!!.scheme)
        assertTrue(
            "the cover moved to the framework external directory instead: ${uri.toString()}",
            sameDirectory(
                File(externalRoot, CLUSTER_ARTWORK_DIRECTORY),
                clusterArtworkBaseOf(File(clusterArtworkUriPath(uri.toString()))),
            ),
        )
        assertTrue(clusterArtworkFileReadable(File(clusterArtworkUriPath(uri.toString()))))
    }

    @Test
    fun aCarWithoutAUsableExternalTreeFallsBackToTheProviderUri() {
        val volume = File(folder.root, "no-such-volume")
        val cacheRoot = folder.newFolder("cache-root")
        val store = ClusterArtworkStore(
            context = context,
            externalFilesDir = null,
            cacheRoot = cacheRoot,
            providerRegistered = { true },
            sharedBases = listOf(File(volume, CLUSTER_ARTWORK_SHARED_ROOT).path),
            // A volume that cannot be created at all. On a JVM the host creates every directory it is
            // asked for, so the "no external storage" car has to be said rather than arranged.
            raw = noSuchVolume(volume),
        )

        val uri = store.publish(bitmap())

        assertNotNull("the provider tier must carry the cover instead", uri)
        assertEquals("content", uri!!.scheme)
        assertTrue(uri.toString(), uri.toString().startsWith("content://${context.packageName}.artwork/"))
        // The provider can only serve it while the file is in its own tree.
        val session = cacheRoot.listFiles().orEmpty().single { it.isDirectory }
        val cover = session.listFiles().orEmpty().single { it.isFile }
        assertTrue(cover.name, cover.name.endsWith(".jpg"))
        assertTrue(cover.name, clusterArtworkFileReadable(cover))
    }

    @Test
    fun aCoverNeitherTierCanServeIsDeletedRatherThanLeftInTheCache() {
        val volume = File(folder.root, "no-such-volume")
        val cacheRoot = folder.newFolder("cache-root")
        val store = ClusterArtworkStore(
            context = context,
            externalFilesDir = null,
            cacheRoot = cacheRoot,
            providerRegistered = { false },
            sharedBases = listOf(File(volume, CLUSTER_ARTWORK_SHARED_ROOT).path),
            raw = noSuchVolume(volume),
        )

        assertNull(store.publish(bitmap()))

        assertFalse(
            "an unreadable cached cover is a leak, not a cover",
            cacheRoot.walkTopDown().any { it.isFile },
        )
    }

    @Test
    fun clearingTheStoreDeletesTheCoversItHandedOver() {
        val store = ClusterArtworkStore(
            context = context,
            externalFilesDir = null,
            cacheRoot = folder.newFolder("cache-root"),
            providerRegistered = { true },
            sharedBases = listOf(sharedBasePath),
        )
        val uri = store.publish(bitmap())

        store.clear()

        assertFalse(File(uri!!.path!!).exists())
    }

    @Test
    fun oldSessionsInTheSharedTreeAreDeletedAndNothingElseThereIsTouched() {
        // A previous process' covers and a neighbour's own file, next to the directory this store owns.
        val neighbour = File(sharedBase, "mediacenter-song.jpg").apply { writeBytes(byteArrayOf(9)) }
        val stale = File(File(sharedBase, CLUSTER_ARTWORK_DIRECTORY), "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")
        assertTrue(stale.mkdirs())
        File(stale, "cover.jpg").writeBytes(byteArrayOf(7))
        val cacheRoot = folder.newFolder("cache-root")

        ClusterArtworkStore(
            context = context,
            externalFilesDir = null,
            cacheRoot = cacheRoot,
            providerRegistered = { true },
            sharedBases = listOf(sharedBasePath),
        )

        assertEquals(
            "the shared base keeps exactly the cluster-artwork directory and nothing was swept beside it",
            listOf(CLUSTER_ARTWORK_DIRECTORY, neighbour.name).sorted(),
            sharedBase.listFiles().orEmpty().map { it.name }.sorted(),
        )
        assertFalse("a dead process' covers are removed", stale.exists())
        assertTrue("only our own directory is swept", neighbour.exists())
    }

    /**
     * A filesystem without [volume]: nothing under it can be created or read.
     *
     * A real temporary directory is the only way to exercise the store's own file I/O, but a JVM will
     * happily create any directory it is asked for, so the car that exposes no external storage is
     * stated through the same injectable operations the pure tests use.
     */
    private fun noSuchVolume(volume: File): RawFileSystem = RawFileSystem(
        isDirectory = { file -> !file.path.startsWith(volume.path) && file.isDirectory },
        mkdirs = { file -> !file.path.startsWith(volume.path) },
    )

    /**
     * The same real file operations, with one thing said rather than arranged: `/sdcard` is not a
     * directory.
     *
     * A host JVM resolves `/sdcard/…` against its own drive root, so "this device cannot write the
     * alias" has to be stated -- and stating it is also what keeps the assertion independent of whether
     * the machine running the tests happens to allow a directory at a drive root. Every other operation
     * stays the real one, because the point of this file is the real write and the real read-back.
     */
    private fun withoutTheSdcardSpelling(): RawFileSystem = RawFileSystem(
        isDirectory = { file -> !isSdcardPath(file) && file.isDirectory },
        mkdirs = { file -> !isSdcardPath(file) && (file.mkdirs() || file.isDirectory) },
    )

    /** Whether [file] is spelled under the alias, whatever separator the host folded into it. */
    private fun isSdcardPath(file: File): Boolean {
        val path = file.path.replace('\\', '/')
        return path == CLUSTER_ARTWORK_SDCARD_ROOT || path.startsWith("$CLUSTER_ARTWORK_SDCARD_ROOT/")
    }

    /** The path of a delivered `file://` string, which is the text the store announced. */
    private fun clusterArtworkUriPath(delivered: String): String =
        delivered.removePrefix("file://").replace("/", File.separator)

    /**
     * `<candidate>/cluster-artwork/<session>/<uuid>.jpg` -> `<candidate>`, by walking up.
     *
     * Walked rather than string-split from a known root, so the assertion says the same thing whatever
     * the host's separator or volume spelling is.
     */
    private fun clusterArtworkBaseOf(delivered: File): File =
        delivered.parentFile!!.parentFile!!.parentFile!!

    /**
     * Whether two paths name the same directory, whatever separator or `..` the platform left in them.
     *
     * The store's candidates are the car's own `/`-separated paths and the host's `File` may fold them
     * differently (`C:/…` versus `C:\…`), so the assertions compare normalised paths instead of `File`
     * objects -- `File.equals` is a string comparison and would call those two different.
     */
    private fun sameDirectory(first: File, second: File): Boolean =
        first.toPath().normalize() == second.toPath().normalize()


    private fun bitmap(): Bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
}



