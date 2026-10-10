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
 * Which tier a handover ends in, driven through the real [ClusterArtworkStore] (docs/_digest/38 §3
 * and the 2026-10-09 on-car run).
 *
 * The pure rules are asserted in `ClusterArtworkTransportTest`; this file checks the part that only a
 * device can answer -- that a successful external write really is the Uri handed over, that a car
 * without that tree really falls back to the provider, and that neither tier leaves its file behind
 * for the other. The device facts the store needs are constructor parameters, so both answers can be
 * produced here without a car: the external directory it writes into, the internal tree the provider
 * serves from, and whether the provider is registered.
 *
 * Robolectric supplies the Context (the store grants through it) and a real JPEG encoder.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class ClusterArtworkStoreTierTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val context = RuntimeEnvironment.getApplication()

    @Test
    fun aReadableExternalCopyIsTheUriHandedOverAndTheCacheCopyIsNotLeftBehind() {
        val external = folder.newFolder("external-files")
        val cacheRoot = folder.newFolder("cache-root")
        val store = ClusterArtworkStore(
            context = context,
            externalFilesDir = external,
            cacheRoot = cacheRoot,
            providerRegistered = { true },
        )

        val uri = store.publish(bitmap())

        assertNotNull("the cover must be handed over", uri)
        assertEquals("file", uri!!.scheme)
        val file = File(uri.path!!)
        val sessionDirectory = file.parentFile!!
        // `<externalFilesDir>/cluster-artwork/<session>/<uuid>.jpg`, with a fresh tail per handover.
        assertEquals(
            "the cover sits in the external cluster-artwork tree",
            File(external, CLUSTER_ARTWORK_DIRECTORY),
            sessionDirectory.parentFile,
        )
        assertTrue("one session directory per process", sessionDirectory.name.length == 36)
        assertTrue("a fresh UUID file name per handover", file.name.length == 40)
        assertTrue("the handed-over file exists: $file", clusterArtworkFileReadable(file))
        // The cache copy exists only for the provider tier, which lost this handover.
        assertFalse("no cache session directory may survive the file tier", cacheRoot.listFiles().orEmpty().any {
            it.isDirectory && it.listFiles().orEmpty().isNotEmpty()
        })
    }

    @Test
    fun aCarWithoutAnExternalTreeFallsBackToTheProviderUri() {
        val cacheRoot = folder.newFolder("cache-root")
        val store = ClusterArtworkStore(
            context = context,
            externalFilesDir = null,
            cacheRoot = cacheRoot,
            providerRegistered = { true },
        )

        val uri = store.publish(bitmap())

        assertNotNull("the provider tier must carry the cover instead", uri)
        assertEquals("content", uri!!.scheme)
        assertTrue(uri.toString(), uri.toString().startsWith("content://${context.packageName}.artwork/"))
        // The provider can only serve it while the file is in its own tree.
        val session = cacheRoot.listFiles().orEmpty().single { it.isDirectory }
        val cover = session.listFiles().orEmpty().single()
        assertTrue(cover.name, cover.name.endsWith(".jpg"))
        assertTrue(cover.name, clusterArtworkFileReadable(cover))
    }

    @Test
    fun anExternalTreeThatCannotBeWrittenFallsBackToTheProviderUri() {
        // Not a directory, so the write tier cannot create its session directory: the trigger the
        // fallback exists for, as opposed to "this car has no external storage at all".
        val notADirectory = File(folder.root, "external-files-is-a-file").apply { writeBytes(byteArrayOf(1)) }
        val store = ClusterArtworkStore(
            context = context,
            externalFilesDir = notADirectory,
            cacheRoot = folder.newFolder("cache-root"),
            providerRegistered = { true },
        )

        val uri = store.publish(bitmap())

        assertEquals("content", uri?.scheme)
    }

    @Test
    fun aCoverNeitherTierCanServeIsDeletedRatherThanLeftInTheCache() {
        val cacheRoot = folder.newFolder("cache-root")
        val store = ClusterArtworkStore(
            context = context,
            externalFilesDir = null,
            cacheRoot = cacheRoot,
            providerRegistered = { false },
        )

        assertNull(store.publish(bitmap()))

        assertFalse(
            "an unreadable cached cover is a leak, not a cover",
            cacheRoot.walkTopDown().any { it.isFile },
        )
    }

    @Test
    fun clearingTheStoreDeletesTheCoversItHandedOver() {
        val external = folder.newFolder("external-files")
        val store = ClusterArtworkStore(
            context = context,
            externalFilesDir = external,
            cacheRoot = folder.newFolder("cache-root"),
            providerRegistered = { true },
        )
        val uri = store.publish(bitmap())

        store.clear()

        assertFalse(File(uri!!.path!!).exists())
    }

    private fun bitmap(): Bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
}
