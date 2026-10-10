package com.shilapi.xcertplay.media

import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The loopback HTTP tier against a **real socket** (`docs/_digest/45 §2.4`).
 *
 * Everything asserted here is protocol, not policy: the server binds a loopback port, answers a `GET`
 * for a registered cover with `200`, `image/jpeg`, the file's exact length and the file's exact bytes,
 * answers `404` for a traversal path and for a cover that is not there, survives a second concurrent
 * request, refuses a non-`GET`, and stops answering once it is shut down. These run on the JVM with no
 * Robolectric, because the tier's whole risk is at the socket layer and a mocked socket would prove
 * nothing about it.
 *
 * **Only the car can confirm** the half that matters most: that the vendor's reading process actually
 * connects to `127.0.0.1:<port>` when it is handed the URL. What this file proves is that the URL we
 * deliver is the one our own server answers, byte for byte, and that nothing a client can send makes
 * the server read or serve outside the cover session it published.
 */
class ClusterArtworkHttpServerTest {
    @get:Rule
    val folder = TemporaryFolder()

    /** A real JPEG-shaped payload; the server never inspects it, it only copies bytes. */
    private val cover = ByteArray(31326) { index -> (index % 251).toByte() }

    @Test
    fun theServerBindsTheLoopbackAddressAndAnswersTheCoverPathWithTheFilesBytes() {
        val session = "0f0e0d0c-0b0a-0908-0706-050403020100"
        val file = "11111111-2222-3333-4444-555555555555.jpg"
        val directory = coverDirectory(session, file)
        val server = ClusterArtworkHttpServer().start()!!
        try {
            server.register(directory)

            assertEquals("loopback only", "127.0.0.1", serverUrlHost(server))
            assertTrue("a kernel-chosen port", server.port > 0)
            val url = server.uriFor(File(directory, file))
            assertEquals(server.port, serverUrlPort(url))
            assertEquals(
                "the URL shape the vendor is handed: $url",
                "/covers/$session/$file",
                url.substringAfter("${server.port}"),
            )

            val response = get(server.port, "/covers/$session/$file")

            assertEquals("200 OK", 200, response.status)
            assertEquals("image/jpeg", "image/jpeg", response.contentType)
            assertEquals("Content-Length is the file's own length", cover.size, response.contentLength)
            assertArrayEquals("the bytes served are the bytes on disk", cover, response.body)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun aTraversalPathAndAMissingCoverAreBothRefusedWith404() {
        val session = "0f0e0d0c-0b0a-0908-0706-050403020100"
        val root = folder.newFolder("cluster-artwork")
        val directory = File(root, session)
        assertTrue(directory.mkdirs())
        File(directory, "11111111-2222-3333-4444-555555555555.jpg").writeBytes(cover)
        // A readable file the traversal would reach if the path were honoured, and a real file the
        // server must still not serve because its name is not a cover name.
        File(root, "secret.jpg").writeBytes(ByteArray(16) { 9 })
        File(directory, "not-a-uuid.jpg").writeBytes(cover)
        val server = ClusterArtworkHttpServer().start()!!
        try {
            server.register(directory)

            val cases = listOf(
                "a traversal to a real file" to "/covers/$session/../../../secret.jpg",
                "an encoded traversal" to "/covers/$session/%2e%2e%2fsecret.jpg",
                "a session that was never registered" to "/covers/aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee/" +
                    "11111111-2222-3333-4444-555555555555.jpg",
                "a cover that is not on disk" to "/covers/$session/22222222-3333-4444-5555-666666666666.jpg",
                "a file name that is not a uuid" to "/covers/$session/not-a-uuid.jpg",
                "not the covers root" to "/other/$session/11111111-2222-3333-4444-555555555555.jpg",
                "a query string" to "/covers/$session/11111111-2222-3333-4444-555555555555.jpg?x=1",
                "a bare path" to "/",
            )
            for ((label, path) in cases) {
                val response = get(server.port, path)
                assertEquals("$label must be 404: $path", 404, response.status)
                assertTrue("$label must not carry a body", response.body.isEmpty())
            }
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun aSecondConcurrentRequestForTheSameCoverStillSucceeds() {
        val session = "0f0e0d0c-0b0a-0908-0706-050403020100"
        val file = "11111111-2222-3333-4444-555555555555.jpg"
        val directory = coverDirectory(session, file)
        val server = ClusterArtworkHttpServer().start()!!
        val pool = Executors.newFixedThreadPool(2)
        try {
            server.register(directory)
            val path = "/covers/$session/$file"

            val answers = pool.invokeAll(
                listOf(Callable { get(server.port, path) }, Callable { get(server.port, path) }),
            ).map { it.get(CLIENT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS) }

            // One worker is enough for one request, so two overlapping ones prove the second is not
            // dropped: both must be whole responses, not a 404 and a 200.
            for (response in answers) {
                assertEquals(200, response.status)
                assertEquals(cover.size, response.contentLength?.toInt())
                assertArrayEquals(cover, response.body)
            }
        } finally {
            pool.shutdownNow()
            server.shutdown()
        }
    }

    @Test
    fun aMethodThatIsNotGetAndARequestThatIsNotOneAreBothRefusedWith404() {
        val session = "0f0e0d0c-0b0a-0908-0706-050403020100"
        val file = "11111111-2222-3333-4444-555555555555.jpg"
        val directory = coverDirectory(session, file)
        val server = ClusterArtworkHttpServer().start()!!
        try {
            server.register(directory)

            // Every one of these gets a complete 404 rather than a bare close: a reader that waits for
            // end-of-stream must never be left unable to tell "refused" from "truncated cover". Only
            // terminated lines are sent, because an unterminated one is not a request yet -- the socket
            // read timeout is what ends that case, which is a separate guarantee from this one.
            val cases = listOf(
                "another method" to "POST /covers/$session/$file HTTP/1.1",
                "a method that merely starts with GET" to "GETX /covers/$session/$file HTTP/1.1",
                "no request line at all" to "",
                "an empty target" to "GET  HTTP/1.1",
                "a fourth field on the line" to "GET /covers/$session/$file HTTP/1.1 extra",
            )
            for ((label, requestLine) in cases) {
                val response = request(server.port, requestLine)
                assertEquals("$label must be 404", 404, response.status)
                assertEquals("$label must promise an empty body", 0, response.contentLength)
            }
            // The cover is still served afterwards, so a refused request did not wedge the server.
            assertEquals(200, get(server.port, "/covers/$session/$file").status)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun onlyTheLastSessionsStayServableAndTheOldestIsForgotten() {
        // Ten sessions, so at least one more than the server's bounded map holds however large the cap
        // is set: the oldest must be forgotten and the newest must still be served.
        val server = ClusterArtworkHttpServer().start()!!
        try {
            val directories = (1..10).map { index ->
                val session = "%08d-1111-2222-3333-444444444444".format(index)
                val file = "11111111-2222-3333-4444-555555555555.jpg"
                coverDirectory(session, file).also { server.register(it) }
            }

            val forgotten = directories.first()
            assertEquals(
                "a session pushed past the cap is forgotten, so its URL 404s",
                404,
                get(server.port, "/covers/${forgotten.name}/${forgotten.listFiles()!!.single().name}").status,
            )
            val kept = directories.last()
            assertEquals(
                "the newest session is still served",
                200,
                get(server.port, "/covers/${kept.name}/${kept.listFiles()!!.single().name}").status,
            )
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun aStoppedServerIsInertAndStopsServing() {
        val session = "0f0e0d0c-0b0a-0908-0706-050403020100"
        val file = "11111111-2222-3333-4444-555555555555.jpg"
        val directory = coverDirectory(session, file)
        val server = ClusterArtworkHttpServer().start()!!
        server.register(directory)
        val request = get(server.port, "/covers/$session/$file")
        assertEquals(200, request.status)

        server.shutdown()

        assertFalse("a torn-down server is not started", server.started)
        assertEquals("and holds no port", 0, server.port)
        // `shutdown` twice is how a second teardown of a dying activity arrives; it must not throw.
        server.shutdown()
    }

    @Test
    fun aServerWhoseBindIsRefusedAnswersNullInsteadOfHalfStarting() {
        val server = ClusterArtworkHttpServer(binder = { error("the platform refused the loopback bind") })

        assertNull("no port means no HTTP tier, not a broken one", server.start())
        assertFalse(server.started)
        assertEquals(0, server.port)
        server.shutdown()
    }

    @Test
    fun aPathIsResolvedOnlyForARegisteredSessionAndTheExactCoverShape() {
        val session = "0f0e0d0c-0b0a-0908-0706-050403020100"
        val directory = folder.newFolder(session)
        val fileName = "11111111-2222-3333-4444-555555555555.jpg"
        File(directory, fileName).writeBytes(cover)
        val known: (String) -> File? = { name -> if (name == session) directory else null }

        assertEquals(
            File(directory, fileName).path,
            parseClusterArtworkHttpPath("/covers/$session/$fileName", known)?.path,
        )
        val refused = listOf(
            "null" to null,
            "empty" to "",
            "a bare slash" to "/",
            "two segments" to "/covers/$fileName",
            "four segments" to "/covers/$session/$fileName/extra",
            "another root" to "/other/$session/$fileName",
            "no root" to "/$session/$fileName",
            "a session that is not a uuid" to "/covers/session/$fileName",
            "a name that is not a uuid" to "/covers/$session/cover.jpg",
            "an extension that is not jpg" to "/covers/$session/${fileName.removeSuffix(".jpg")}.png",
            "an unregistered session" to "/covers/aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee/$fileName",
        )
        for ((label, path) in refused) {
            assertNull(label, parseClusterArtworkHttpPath(path, known))
        }
    }

    /**
     * The one `cluster-artwork` tree this test method serves from.
     *
     * Created once per test, because a session directory lives inside it and several sessions are
     * registered in the cap test; `TemporaryFolder.newFolder` refuses a name it already handed out.
     */
    private val artworkTree: File by lazy { folder.newFolder("cluster-artwork") }

    /** A session directory under [artworkTree] holding one real cover, the tree the server serves. */
    private fun coverDirectory(session: String, fileName: String): File {
        val directory = File(artworkTree, session)
        assertTrue(directory.mkdirs())
        File(directory, fileName).writeBytes(cover)
        return directory
    }

    private fun serverUrlHost(server: ClusterArtworkHttpServer): String =
        server.uriFor(File(folder.root, "x/y.jpg")).substringAfter("http://").substringBefore(":")

    private fun serverUrlPort(url: String): Int = url.substringAfter("http://127.0.0.1:").substringBefore('/').toInt()

    /**
     * One HTTP request written by hand and one response read by hand.
     *
     * Deliberately not `HttpURLConnection`: the point of this test is the wire format this server
     * produces -- the status line, `Content-Type`, `Content-Length` and the body -- and a client
     * library would hide the framing this tier exists to get right. `Connection: close` lets the body
     * be read to end-of-stream, so a body shorter than the promised length shows up as a comparison
     * failure rather than as a hang.
     */
    private fun get(port: Int, path: String): Response = request(port, "GET $path HTTP/1.1")

    private fun request(port: Int, requestLine: String): Response {
        Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", port), CLIENT_READ_TIMEOUT_MILLIS)
            socket.soTimeout = CLIENT_READ_TIMEOUT_MILLIS
            if (requestLine.isNotEmpty()) {
                socket.getOutputStream().write(
                    ("$requestLine\r\nHost: 127.0.0.1:$port\r\n\r\n").toByteArray(Charsets.ISO_8859_1),
                )
                socket.getOutputStream().flush()
            }
            // The client half of `Connection: close`: this write half is finished, so the body can be
            // read to end-of-stream instead of the promised length, and a server that never answers at
            // all shows up as a read timeout rather than as a hang.
            socket.shutdownOutput()
            val raw = socket.getInputStream().readBytes()
            if (raw.isEmpty()) return Response(null, null, null, ByteArray(0))
            val text = raw.toString(Charsets.ISO_8859_1)
            val separator = text.indexOf("\r\n\r\n")
            val head = if (separator < 0) text else text.substring(0, separator)
            val lines = head.split("\r\n")
            val bodyStart = if (separator < 0) raw.size else separator + 4
            val length = lines.drop(1)
                .firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }
                ?.substringAfter(':')?.trim()?.toIntOrNull() ?: 0
            return Response(
                status = lines.first().split(' ').getOrNull(1)?.toIntOrNull(),
                contentType = lines.drop(1)
                    .firstOrNull { it.startsWith("Content-Type:", ignoreCase = true) }
                    ?.substringAfter(':')?.trim(),
                contentLength = length,
                body = raw.copyOfRange(bodyStart, minOf(raw.size, bodyStart + length)),
            )
        }
    }

    /** What one hand-written request answered: the parsed head and the body it promised. */
    private data class Response(
        val status: Int?,
        val contentType: String?,
        val contentLength: Int?,
        val body: ByteArray,
    )

    private companion object {
        /** How long a hand-written request may take in total, so a wedged server fails the test. */
        const val CLIENT_TIMEOUT_MILLIS = 5_000L

        /** The per-read timeout on the client socket, in the unit `Socket.setSoTimeout` takes. */
        const val CLIENT_READ_TIMEOUT_MILLIS = 5_000
    }
}
