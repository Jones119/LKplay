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
 * Which tier one handover ends in once the loopback HTTP server exists, and what the delivered string
 * is, driven through the real [ClusterArtworkStore] (docs/_digest/45 §2.4).
 *
 * Three outcomes are asserted here, because the tier the car gets depends on a port it may not have:
 * `http://127.0.0.1:<port>/covers/<session>/<uuid>.jpg` when the bind succeeded, the `file://` string
 * of the very same cover when it did not, and the `content://` provider Uri when no external base could
 * take it either. The `file://` and `content://` halves are the ones the previous runs already
 * delivered, so they are asserted through the same store the car runs, with a server that cannot bind
 * (see `clusterArtworkServerWithoutPort`) instead of an arrangement at the socket layer.
 *
 * The override is a pure rule and is asserted without a device in [ClusterArtworkTierTest]; the
 * handover it produces is not, because the vendor is only ever handed its result after a bitmap
 * arrives, and [VendorClusterBridge] is what does that (see `ClusterArtworkUriLogTest`).
 *
 * **Only the car can confirm** that the reading process connects to the loopback URL. On the JVM the
 * URL is proved to be the one the server answers, byte for byte
 * (`ClusterArtworkHttpServerTest`); whether the DIM service's reader fetches it is a device fact.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class ClusterArtworkStoreHttpTierTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val context = RuntimeEnvironment.getApplication()

    /** Stands in for `/sdcard/DiPlay`: the shared tree the car's own media application also uses. */
    private val sharedBase: File by lazy { folder.newFolder("sdcard-DiPlay") }

    @Test
    fun aStartedLoopbackServerIsTheDeliveredTierAndTheUrlServesTheCoverThatWasWritten() {
        val cacheRoot = folder.newFolder("cache-root")
        val server = ClusterArtworkHttpServer()
        val store = ClusterArtworkStore(
            context = context,
            externalFilesDir = null,
            cacheRoot = cacheRoot,
            providerRegistered = { true },
            sharedBases = listOf(sharedBase.path),
            server = server,
        )

        val uri = store.publish(bitmap())

        assertNotNull("the cover must be handed over", uri)
        assertEquals("http", uri!!.scheme)
        assertTrue(uri.toString(), uri.toString().startsWith("http://127.0.0.1:${server.port}/covers/"))
        // The rule that produced it, checked directly as well as through the store: this one line is
        // what fails if the tier order is ever pinned back to the `file://` tier that drew nothing.
        assertEquals(
            "TierPin",
            ClusterArtworkTier.HTTP,
            clusterArtworkTier(
                httpStarted = server.started,
                fileUsable = true,
                providerRegistered = true,
                granted = 2,
            ),
        )
        // The two path segments are the ones the server resolves, so the URL cannot 404 a handover that
        // just succeeded -- the fault the file tier's own read-back exists to catch.
        val parts = uri.toString().substringAfter("/covers/").split('/')
        assertEquals(2, parts.size)
        val session = parts[0]
        val fileName = parts[1]
        assertEquals("the session directory the cover was written into", session, sessionDirectory().name)
        assertEquals("a fresh UUID jpeg per push", 36, fileName.removeSuffix(".jpg").length)
        assertTrue(fileName, fileName.endsWith(".jpg"))
        val written = File(File(sharedBase, CLUSTER_ARTWORK_DIRECTORY), "$session/$fileName")
        assertTrue("the URL names the file on disk: $written", written.isFile)
        assertTrue(
            "the cache copy is deleted once the server serves the external one",
            cacheRoot.listFiles().orEmpty().all { it.listFiles().orEmpty().isEmpty() },
        )

        server.shutdown()
    }

    @Test
    fun aCarThatCannotBindThePortFallsBackToTheFileStringOfTheVerySameCover() {
        val cacheRoot = folder.newFolder("cache-root")
        val store = ClusterArtworkStore(
            context = context,
            externalFilesDir = null,
            cacheRoot = cacheRoot,
            providerRegistered = { true },
            sharedBases = listOf(sharedBase.path),
            server = clusterArtworkServerWithoutPort(),
        )

        val uri = store.publish(bitmap())

        assertNotNull("a refused port must not cost the cover", uri)
        assertEquals("file", uri!!.scheme)
        val path = uri.toString().removePrefix("file://").replace("/", File.separator)
        val delivered = File(path)
        assertTrue("the delivered string reads the cover back: $path", clusterArtworkFileReadable(delivered))
        assertTrue(
            "it is the shared base's own cover tree: ${delivered.parentFile}",
            delivered.parentFile.parentFile.absolutePath.endsWith(CLUSTER_ARTWORK_DIRECTORY),
        )
        assertTrue("the delivered file sits under the base the store was handed", delivered.absolutePath
            .startsWith(sharedBase.absolutePath))
        assertFalse("no URL is announced for a port that never bound", uri.toString().startsWith("http"))
    }

    @Test
    fun aCarWithNeitherAPortNorAnExternalTreeStillGetsTheProviderUri() {
        val volume = File(folder.root, "no-such-volume")
        val cacheRoot = folder.newFolder("cache-root")
        val store = ClusterArtworkStore(
            context = context,
            externalFilesDir = null,
            cacheRoot = cacheRoot,
            providerRegistered = { true },
            sharedBases = listOf(File(volume, CLUSTER_ARTWORK_SHARED_ROOT).path),
            // A volume that cannot be created at all: on a JVM the host creates every directory it is
            // asked for, so "no external storage" has to be said rather than arranged.
            raw = noSuchVolume(volume),
            server = clusterArtworkServerWithoutPort(),
        )

        val uri = store.publish(bitmap())

        assertNotNull("the provider tier must carry the cover instead", uri)
        assertEquals("content", uri!!.scheme)
        assertTrue(uri.toString(), uri.toString().startsWith("content://${context.packageName}.artwork/"))
        assertFalse("no URL was announced for a server that never bound", uri.toString().startsWith("http"))
        // The provider can only serve a file in its own tree.
        assertTrue(
            "the cover stays in the cache for the provider",
            cacheRoot.walkTopDown().any { it.isFile && clusterArtworkFileReadable(it) },
        )
    }

    @Test
    fun aClosedStoreReleasesThePortAndTheNextStoreBindsItsOwn() {
        val server = ClusterArtworkHttpServer()
        val store = ClusterArtworkStore(
            context = context,
            externalFilesDir = null,
            cacheRoot = folder.newFolder("cache-root"),
            providerRegistered = { true },
            sharedBases = listOf(sharedBase.path),
            server = server,
        )
        val first = store.publish(bitmap())
        assertEquals("http", first!!.scheme)

        // `close` is what `VendorClusterBridge.detach` calls on the way out: the covers go and the
        // listening socket goes with them, so nothing keeps a port after the bridge is gone.
        store.close()

        assertFalse("a torn-down store leaves no started server behind", server.started)
        assertEquals("and no port", 0, server.port)
        assertTrue("and no cover it handed over", sharedBase.walkTopDown().none { it.isFile })
        // A later session builds a new store, and therefore a new server: the bridge drops the closed
        // one rather than reusing it, which is why a port is never handed out twice by this object.
        val replacement = ClusterArtworkHttpServer()
        val second = ClusterArtworkStore(
            context = context,
            externalFilesDir = null,
            cacheRoot = folder.newFolder("cache-root-2"),
            providerRegistered = { true },
            sharedBases = listOf(sharedBase.path),
            server = replacement,
        )
        val next = second.publish(bitmap())
        assertEquals("http", next!!.scheme)
        assertTrue("the replacement bound a port of its own", replacement.port > 0)
        second.close()
    }

    /** The single session directory this store created under the shared base. */
    private fun sessionDirectory(): File =
        File(sharedBase, CLUSTER_ARTWORK_DIRECTORY).listFiles().orEmpty().single { it.isDirectory }

    /**
     * A filesystem without [volume]: nothing under it can be created or read.
     *
     * The same statement `ClusterArtworkStoreTierTest` makes, repeated here because the HTTP tier's
     * fallback order has to be walked from the real store rather than from a mocked one.
     */
    private fun noSuchVolume(volume: File): RawFileSystem = RawFileSystem(
        isDirectory = { file -> !file.path.startsWith(volume.path) && file.isDirectory },
        mkdirs = { file -> !file.path.startsWith(volume.path) },
    )

    private fun bitmap(): Bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
}
