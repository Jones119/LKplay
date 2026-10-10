package com.shilapi.xcertplay.media

import android.graphics.Bitmap
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

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

    /**
     * D3: the degradation to the tier this ROM has never drawn is loud, and still happens.
     *
     * The fallback itself is the assertion above; what this adds is that the refusal is *reported*, so an
     * on-car session that ends up on `file://` after a failed bind is a line in `logcat` rather than a
     * blank cluster next to a healthy-looking `tier=file` log. Both halves matter: a loud failure that
     * dropped the cover would be worse than the silence it replaced, so the cover is asserted here too.
     */
    @Test
    fun aRefusedBindIsLoggedAsADegradationAndStillDeliversTheFileTier() {
        val cacheRoot = folder.newFolder("cache-root")
        val reason = "the platform refused the loopback bind"
        val store = ClusterArtworkStore(
            context = context,
            externalFilesDir = null,
            cacheRoot = cacheRoot,
            providerRegistered = { true },
            sharedBases = listOf(sharedBase.path),
            server = ClusterArtworkHttpServer(binder = { error(reason) }),
        )

        val uri = store.publish(bitmap())

        assertNotNull("a refused port must not cost the cover", uri)
        assertEquals("the documented fallback is still what a refused port yields", "file", uri!!.scheme)
        val logged = ShadowLog.getLogsForTag(SERVER_LOG_TAG).joinToString("\n") { "${it.type} ${it.msg}" }
        assertTrue(
            "the refused bind must say the server is unavailable: $logged",
            logged.contains("http server unavailable"),
        )
        assertTrue(
            "and must say it is degrading to a tier this ROM has never drawn: $logged",
            logged.contains("degrading to a tier this ROM has never drawn"),
        )
        assertTrue(
            "the refusal is an error, not a warning nobody greps for: $logged",
            ShadowLog.getLogsForTag(SERVER_LOG_TAG).any {
                it.msg.contains("http server unavailable") && it.type.toString() == SERVER_LOG_ERROR
            },
        )
    }

    /**
     * D1 through the bridge: `detach` is what `CarPlayHostActivity.onDestroy` calls, and it must not end
     * the artwork session.
     *
     * The defect this pins is the subject: the host activity going away used to run the store's
     * teardown, which deleted the covers *and* closed the loopback port, so a cluster fetching lazily
     * after a background or a recreate was handed `connection refused` for a URL whose `tier=http` line
     * had looked perfectly healthy. A detached bridge keeps the store, so a later `setArtwork` -- the
     * re-published playback payload of the recreated activity, or the next track -- publishes through
     * the same store and the same socket, while the cover of the track that is still on screen keeps
     * answering.
     *
     * Nothing is injected into the bridge on purpose: a test seam there would let this pass while the
     * car's own path stayed broken, which is the one thing a regression test for this defect must not
     * allow. So the handover, the car's own default server and the two bridge calls are the real ones,
     * and only the socket is read back by hand.
     */
    @Test
    fun detachingTheBridgeKeepsTheServerAndTheHandedOverCoverUntilTheSessionEnds() {
        // The *process-wide* server, because that is the one the bridge's own store resolves to on the
        // car: the assertion below would prove nothing about the default if it were a private instance.
        // Touching it here breaks the lazy cycle deliberately, so the finally block can close it even if
        // the handover never happens.
        val server = clusterArtworkProcessServer()
        assertFalse("the test starts from a stopped server", server.started)
        try {
            VendorClusterBridge.attach(context, false)
            VendorClusterBridge.setArtwork(bitmap())
            // The URL the vendor was handed, taken from the tier line rather than rebuilt: the store
            // works out its own base, and the delivered string is what an on-car grep reads anyway.
            val url = deliveredHttpUrl()
            assertNotNull("the handover must be the HTTP tier: ${serverLog()}", url)
            val path = "/" + url!!.substringAfter(":${server.port}/")

            // What `onDestroy` does while the CarPlay session continues.
            VendorClusterBridge.detach()

            assertTrue("the socket a handed-over URL names stays bound", server.started)
            assertEquals("and the URL of that cover still answers", 200, request(server.port, path)?.status)

            // The recreated activity publishes again: the same store, and therefore the same port. The
            // first cover answers throughout, which is the whole point -- the cluster may fetch it
            // minutes after its track, and after an activity the user never saw.
            VendorClusterBridge.setArtwork(bitmap())
            assertTrue("the port does not churn under a live URL", server.started)
            assertEquals("the first cover is still served", 200, request(server.port, path)?.status)

            // The session end is the only thing that takes the port and the covers.
            VendorClusterBridge.release()
            assertFalse("the session end releases the loopback port", server.started)
            assertEquals(0, server.port)
            assertEquals("and its covers stop resolving", null, request(server.port, path)?.status)
        } finally {
            // `release` already did this; running it again is what the host's double teardown looks like,
            // and it keeps the process-wide server inert for whatever test method runs next.
            VendorClusterBridge.release()
        }
    }

    /**
     * D2: a cover whose URL was handed over survives the retention sweep, however often it runs.
     *
     * The retention cap is injected as 2 so the sweep is driven by four more handovers instead of
     * thirty-two, and every one of them is on the HTTP tier, so the deliverable it is protecting is the
     * same URL shape the car hands over. The old behaviour deleted the file behind a URL the cluster
     * still held as soon as the cap was reached, which is exactly the `404` this asserts against.
     */
    @Test
    fun aCoverThatWasHandedOverIsStillServedAfterTheRetentionSweepHasRunPastItsLimit() {
        val server = ClusterArtworkHttpServer()
        val store = ClusterArtworkStore(
            context = context,
            externalFilesDir = null,
            cacheRoot = folder.newFolder("cache-root"),
            providerRegistered = { true },
            sharedBases = listOf(sharedBase.path),
            server = server,
            retentionLimit = 2,
        )

        val first = store.publish(bitmap())!!
        val firstPath = first.toString().substringAfter(":${server.port}")
        assertEquals("the first handover is the HTTP tier", "http", first.scheme)
        assertEquals(
            "and it is served before anything else happens",
            200,
            request(server.port, firstPath)?.status,
        )

        // Four more handovers, so the cache-copy cap of two was reached and passed twice over.
        repeat(4) { assertNotNull("every track still gets a cover", store.publish(bitmap())) }

        val stillServed = request(server.port, firstPath)
        assertEquals("a URL already handed to the cluster still answers", 200, stillServed?.status)
        assertTrue(
            "and it still serves a cover rather than an empty file: ${stillServed?.length}",
            (stillServed?.length ?: 0) > 0,
        )
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

    /**
     * The store's own end: its covers go, but the server does not.
     *
     * The positive half is already asserted where it belongs -- `detachingTheBridgeKeepsTheServerAnd
     * TheHandedOverCoverUntilTheSessionEnds` shows the socket surviving `detach` and `release` closing
     * it -- and the negative half is here, because it is the trap a later refactor would fall into:
     * putting the socket into `close` is the one-line "tidy-up" that re-creates D1.
     */
    @Test
    fun closingAStoreDeletesItsCoversButDoesNotTouchTheProcessWideServer() {
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
        val port = server.port

        store.close()

        assertTrue("a store teardown must not close the session's socket", server.started)
        assertEquals(port, server.port)
        assertTrue("but its own covers go", sharedBase.walkTopDown().none { it.isFile })
        // A later store publishing through the same process-wide server reuses the port rather than
        // churning it: the socket a handed-over URL names does not move under a recreated activity.
        val second = ClusterArtworkStore(
            context = context,
            externalFilesDir = null,
            cacheRoot = folder.newFolder("cache-root-2"),
            providerRegistered = { true },
            sharedBases = listOf(sharedBase.path),
            server = server,
        )
        val next = second.publish(bitmap())
        assertEquals("http", next!!.scheme)
        assertEquals("one bind per process", port, server.port)
        second.close()
    }

    /** The single session directory this store created under the shared base. */
    private fun sessionDirectory(): File =
        File(sharedBase, CLUSTER_ARTWORK_DIRECTORY).listFiles().orEmpty().single { it.isDirectory }

    /** The `DiPlay-Cluster` lines this test has produced so far, so a failure can show them. */
    private fun serverLog(): String =
        ShadowLog.getLogsForTag(SERVER_LOG_TAG).joinToString("\n") { "${it.type} ${it.msg}" }

    /**
     * The HTTP URL of the most recent handover, read back from the store's own `tier=http … uri=…` line.
     *
     * Read rather than rebuilt because the store chooses its base itself: on this host the literal
     * `/sdcard` alias wins over the injected candidate, so a path this test computed from
     * [sharedBase] would name a file in another tree. The `uri=` text is the string the vendor was
     * given, which is exactly what an on-car check greps for as well.
     */
    private fun deliveredHttpUrl(): String? =
        ShadowLog.getLogsForTag(SERVER_LOG_TAG)
            .lastOrNull { it.msg.contains("tier=http") && it.msg.contains("uri=http://") }
            ?.msg
            ?.substringAfter("uri=")
            ?.substringBefore(' ')

    /**
     * One hand-written request against the store's own server, so a test can ask whether a URL the store
     * handed over still answers. The framing is the same the socket test reads by hand; only the status
     * matters here, and `Connection: close` lets the body be read to end-of-stream.
     *
     * A connection that is *refused* answers null, which is the third outcome the on-car check
     * distinguishes: a port nobody listens on is not a `404`, and a test that threw here could not tell
     * the two apart.
     */
    private fun request(port: Int, path: String): Response? {
        val raw = runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", port), REQUEST_TIMEOUT_MILLIS)
                socket.soTimeout = REQUEST_TIMEOUT_MILLIS
                socket.getOutputStream().write("GET $path HTTP/1.1\r\nHost: 127.0.0.1:$port\r\n\r\n".toByteArray())
                socket.getOutputStream().flush()
                socket.shutdownOutput()
                socket.getInputStream().readBytes()
            }
        }.getOrNull() ?: return null
        if (raw.isEmpty()) return Response(null, 0)
        val text = raw.toString(Charsets.ISO_8859_1)
        val head = text.substringBefore("\r\n\r\n")
        return Response(
            status = head.lineSequence().first().split(' ').getOrNull(1)?.toIntOrNull(),
            length = Integer.parseInt(
                head.lineSequence().firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }
                    ?.substringAfter(':')?.trim() ?: "0",
            ),
        )
    }

    /** The status and promised body length of one hand-written response. */
    private data class Response(val status: Int?, val length: Int)

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

    private companion object {
        /** How long a hand-written request may take in total, so a wedged server fails the test. */
        const val REQUEST_TIMEOUT_MILLIS = 5_000

        /** The tag the artwork tier logs under, which is also the on-car `logcat` grep. */
        const val SERVER_LOG_TAG = "DiPlay-Cluster"

        /**
         * `Log.ERROR` as `ShadowLog` spells it.
         *
         * A literal rather than `Log.ERROR`: the assertion is about the *level* the log line carries, and
         * comparing against the same constant the production code passes to `Log.e` would make the test
         * agree with a mistake in that argument.
         */
        const val SERVER_LOG_ERROR = "6"
    }
}
