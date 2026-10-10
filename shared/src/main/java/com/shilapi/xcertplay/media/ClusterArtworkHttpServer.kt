package com.shilapi.xcertplay.media

import android.util.Log
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * The URL path segment that mirrors the provider tier's `covers` (see [CLUSTER_ARTWORK_PATH]).
 *
 * One cover therefore has one identity in all three tiers: `covers/<session>/<uuid>.jpg` is the tail
 * of the `content://` Uri, the tail of the `file://` path and the whole HTTP path, so an on-car log
 * with a tier line for each can be read against the same names.
 */
internal const val CLUSTER_ARTWORK_HTTP_ROOT = CLUSTER_ARTWORK_PATH

/** The only address this server listens on: covers are for this device, not for the network. */
private const val CLUSTER_ARTWORK_HTTP_LOOPBACK = "127.0.0.1"

/** Accept-queue depth: as many pending readers as the worker count, all a car needs. */
private const val CLUSTER_ARTWORK_HTTP_BACKLOG = 4

/**
 * The cover an HTTP request path names, or null when it names anything this app did not publish.
 *
 * Extracted from [ClusterArtworkHttpServer] and given its resolver as an argument, because "which
 * paths may be served" is the security rule of this tier and has to be assertable without a socket or
 * a car. A request is accepted only as exactly `covers/<session-uuid>/<file-uuid>.jpg`, and the
 * session is handed to [sessionDirectory], which is the server's own map of directories the store
 * actually wrote to: a segment that is closed, empty, a `.`/`..` or a session this process never
 * registered has no directory at all, so no request can name a path outside the cover trees -- which
 * is a stronger rule than normalising a path string and hoping. The file is *not* checked for
 * existence here; a cover the retention sweep deleted is a resolvable name with nothing behind it, and
 * the caller answers that with the same 404 as an illegal path.
 *
 * The path is used verbatim, so `%2e%2e` and every other encoding stay literal names that do not
 * match the UUID pattern rather than being decoded into path segments.
 */
internal fun parseClusterArtworkHttpPath(
    path: String?,
    sessionDirectory: (String) -> File?,
): File? {
    if (path == null) return null
    val segments = path.split('/')
    if (segments.size != 4) return null
    if (segments[0] != "" || segments[1] != CLUSTER_ARTWORK_HTTP_ROOT) return null
    val session = segments[2]
    val fileName = segments[3]
    if (!UUID_PATTERN.matches(session)) return null
    if (!fileName.endsWith(".jpg")) return null
    if (!UUID_PATTERN.matches(fileName.removeSuffix(".jpg"))) return null
    val directory = sessionDirectory(session) ?: return null
    return File(directory, fileName)
}

/**
 * The socket the car listens on: loopback only, kernel-chosen port, backlog [CLUSTER_ARTWORK_HTTP_BACKLOG].
 *
 * A top-level function rather than an inline expression so [ClusterArtworkHttpServer] can take its
 * binding as a value and a test can hand over one that refuses. `reuseAddress = false` is deliberate:
 * the port is ephemeral, so there is nothing to rebind quickly, and a lingering `TIME_WAIT` socket on
 * this car is not something a cover should ever wait for.
 */
private fun loopbackSocket(): ServerSocket = ServerSocket().apply {
    reuseAddress = false
    bind(InetSocketAddress(InetAddress.getByName(CLUSTER_ARTWORK_HTTP_LOOPBACK), 0), CLUSTER_ARTWORK_HTTP_BACKLOG)
}

/**
 * A server that cannot bind, so the `file://` fallback can be exercised where the HTTP tier does not
 * belong in the assertion.
 *
 * Every call to [ClusterArtworkHttpServer.start] fails, exactly as it does on a device whose platform
 * refuses the loopback bind -- which is the only difference between this and a real server for a test
 * that is not about the HTTP tier at all. Named rather than left as a lambda at the call sites, so a
 * test that uses it says "this handover is decided by the cheaper tiers" in one place.
 */
internal fun clusterArtworkServerWithoutPort(): ClusterArtworkHttpServer =
    ClusterArtworkHttpServer(binder = { error("no port for the artwork server in this test") })

/**
 * The third artwork tier: a loopback HTTP server that answers one cover per request.
 *
 * The 2026-10-09/10-10 on-car runs delivered three different `content://` Uris with explicit grants
 * and three different `file://` paths in two trees, the vendor's own DIM service logged every one of
 * them, and the instrument cluster painted none. The car's own media application, whose covers the
 * cluster *does* display and switch per track, hands it
 * `http://y.gtimg.cn/music/photo_new/T002R300x300M000000FUEPg2e4lIX_3.jpg` for its online tracks. The
 * reading process therefore appears to *fetch* a remote URL and never to open a
 * `content://`/`file://` Uri at all -- which is exactly why the reference implementation carries this
 * loopback server as its last tier and enables it only on this model (`usesSinglePassArtwork()` is
 * literally `sdk == 19 && Freescale && CS11`, docs/_digest/45 §2.4): the head unit's reader must be
 * given a URL it can GET.
 *
 * What it serves is the encoded cover the existing transport already produced, read **from disk on
 * every request** and never held in memory: the bytes handed to the reader are therefore always the
 * bytes currently on the volume, and a cover the retention sweep deleted becomes a `404` instead of a
 * stale copy served from a buffer.
 *
 * It is a *preferred* tier, not a replacement: [ClusterArtworkStore] still writes the file copy and
 * still falls back to `file://` and then `content://` when this server cannot bind (see
 * [clusterArtworkTier]). Nothing about the encoding contract changes with the tier.
 *
 * ## Why it cannot leak or wedge
 *
 * - **Loopback only.** The socket binds `127.0.0.1` and a port the kernel picks (`port = 0`), so the
 *   server is reachable from this device and nowhere else, and it cannot collide with a port another
 *   process already holds: a collision is simply the next ephemeral port, and a refusal to bind at all
 *   is reported as "no HTTP tier" rather than retried in a loop.
 * - **Nothing unbounded.** The accept backlog is [CLUSTER_ARTWORK_HTTP_BACKLOG] and the workers are a
 *   *bounded* queue in front of [CLUSTER_ARTWORK_HTTP_THREADS] threads: a client that connects and
 *   never sends anything costs one queue slot, and once the queue is full the connection is closed
 *   instead of a thread or a task being created for it. There is no thread per connection and no
 *   growth with the number of covers.
 * - **Nothing blocks forever.** The server socket carries an accept timeout so the loop can observe
 *   [shutdown], and every accepted socket carries read and write timeouts, so a client that opens a
 *   connection and stalls -- the classic way to pin a one-thread-per-request server -- is closed after
 *   [CLUSTER_ARTWORK_HTTP_SOCKET_TIMEOUT_MILLIS] and cannot hold a worker. The request line itself is
 *   capped at [CLUSTER_ARTWORK_HTTP_MAX_REQUEST_LINE] bytes, so a client that never sends a terminator
 *   cannot make the handler grow a buffer either.
 * - **Nothing escapes the root.** A request is resolved by looking its `<session>` up in the map this
 *   server was told about (one entry per cover session this process published) and by accepting only
 *   the `covers/<session-uuid>/<file-uuid>.jpg` shape ([parseClusterArtworkHttpPath]); `.`, `..`, an
 *   empty segment, a query, a fragment, an absolute path in the file name and every method other than
 *   `GET` are refused with [HTTP_STATUS_NOT_FOUND] before a [File] is built.
 * - **Nothing keeps the process alive.** The accept thread and every worker are daemon threads, and
 *   [shutdown] closes the listening socket (which makes the blocked `accept` throw), stops the pool and
 *   drops the session map. A stopped server answers no request: [uriFor] is only called after a
 *   successful [start], and [ClusterArtworkStore] drops a server that failed to start, so a torn-down
 *   server can never be handed to the vendor as a still-working URL.
 *
 * ## Lifecycle
 *
 * **Start lazily, stop with the bridge.** The object is constructed by [ClusterArtworkStore] on the
 * first cover of a process but binds nothing until [start] is called, and the store calls that only
 * once a base has been probed and written -- a car that never publishes a cover never opens a port.
 * [shutdown] is called from `VendorClusterBridge.detach()`, i.e. when the bridge is torn down (the
 * host activity's `onDestroy`): the listening socket is closed, in-flight requests are given
 * [CLUSTER_ARTWORK_HTTP_SHUTDOWN_GRACE_MILLIS] to finish and then interrupted, and the session map is
 * cleared. After `shutdown` the object is inert; a later cover creates a new instance through the
 * store rather than resurrecting this one, which is why the bind lives in [start] and never in `init`.
 *
 * **Only the car can confirm** that a reader in another process really fetches this URL: a JVM test can
 * open the socket and prove the status, the headers and the bytes (see `ClusterArtworkHttpServerTest`),
 * but whether the reading process ever connects is a device fact, and the
 * `cluster artwork tier=http port=… uri=…` line is what the on-car check greps for.
 */
internal class ClusterArtworkHttpServer(
    /**
     * How the listening socket is obtained, as a value so the "no port for us" path is a test rather
     * than an arrangement.
     *
     * The car leaves it at the default, which binds `127.0.0.1` on a kernel-chosen port. A JVM test
     * cannot exhaust a host's ports on purpose, and "the car has no free port / the platform refuses
     * the bind" is exactly the case the `file://` and `content://` fallbacks exist for, so it is
     * stated here: a double that throws or answers null makes `start` answer null the same way a
     * refused bind does, and the store's tier preference can then be asserted without a socket.
     */
    private val binder: () -> ServerSocket = ::loopbackSocket,
) {
    /** The listening socket, or null while [start] has not run or after [shutdown]. */
    private var serverSocket: ServerSocket? = null

    /**
     * The workers that write the responses, or null while stopped.
     *
     * Core and maximum are both [CLUSTER_ARTWORK_HTTP_THREADS], so the pool never grows past that
     * count; the queue is bounded at [CLUSTER_ARTWORK_HTTP_QUEUE] and a submission that does not fit is
     * discarded by the caller, which keeps a flood of connections from creating threads or an
     * unbounded queue.
     */
    private var workers: ExecutorService? = null

    /** [running] plus [serverSocket] make [shutdown] idempotent and safe to call from any thread. */
    @Volatile
    private var running = false

    /**
     * The session directories this process published, oldest first, so an old cover stays fetchable
     * until the retention sweep actually deletes it.
     *
     * The map is as much the traversal defence as a lookup table: a `<session>` that is not one of
     * these names has no directory to resolve to. Bounded at [CLUSTER_ARTWORK_HTTP_MAX_SESSIONS]; a
     * session pushed past the cap loses its HTTP tier (its `file://` and `content://` forms still
     * work), which is a bounded amount of memory rather than a growing map.
     */
    private val sessions = LinkedHashMap<String, File>()

    /**
     * The port the server listens on, 0 before [start] and after [shutdown].
     *
     * Read without a lock, which is safe because a started server's port never changes and a stopped
     * one is never asked for a URL; `@Volatile` so the accept thread cannot observe the field without
     * its value.
     */
    @Volatile
    var port: Int = 0
        private set

    /** True once [start] bound the socket, so the tier choice can be asserted without a request. */
    val started: Boolean get() = running

    /**
     * Binds the loopback socket and starts accepting, or answers null when the port cannot be taken.
     *
     * Null is a supported outcome, not an error: the store treats it as "this tier is unavailable" and
     * delivers the same cover as a `file://` copy instead, which is what a car whose reader does not
     * fetch URLs needs anyway. Synchronised with [shutdown], so a stop that races a start either wins
     * before the bind or is undone by this method noticing [running] was cleared.
     */
    fun start(): ClusterArtworkHttpServer? = synchronized(this) {
        if (running) return@synchronized this
        val socket = try {
            // The default binder picks a free ephemeral port on the loopback address; a test's binder
            // may refuse instead, which is the state the two cheaper tiers fall back to.
            binder().apply {
                // The accept timeout is what lets the loop observe `shutdown` without a wakeup socket.
                soTimeout = CLUSTER_ARTWORK_HTTP_ACCEPT_TIMEOUT_MILLIS
            }
        } catch (refused: Exception) {
            Log.w(TAG, "cluster artwork http server unavailable", refused)
            return@synchronized null
        }
        val pool = ThreadPoolExecutor(
            CLUSTER_ARTWORK_HTTP_THREADS,
            CLUSTER_ARTWORK_HTTP_THREADS,
            0L,
            TimeUnit.MILLISECONDS,
            ArrayBlockingQueue(CLUSTER_ARTWORK_HTTP_QUEUE),
            { runnable -> Thread(runnable, CLUSTER_ARTWORK_HTTP_THREAD).apply { isDaemon = true } },
            // A full queue means the readers are not keeping up: dropping the connection is better than
            // running it on the accepting thread, which would stop the server accepting anything at all.
            ThreadPoolExecutor.AbortPolicy(),
        )
        serverSocket = socket
        workers = pool
        port = socket.localPort
        running = true
        Thread({ acceptLoop(socket, pool) }, CLUSTER_ARTWORK_HTTP_ACCEPT_THREAD).apply {
            isDaemon = true
            start()
        }
        this
    }

    /**
     * Stops accepting, stops the workers and forgets every session; safe to call more than once.
     *
     * Closing the listening socket is what unblocks `accept`, so this needs no wakeup connection and
     * cannot be delayed by a client that is not connecting. The pool is shut down without draining the
     * queue -- a cover nobody asked for must not keep an activity's teardown waiting -- and a request
     * already being written gets [CLUSTER_ARTWORK_HTTP_SHUTDOWN_GRACE_MILLIS] to finish before the
     * workers are interrupted; a half-written response is detected by the client through the
     * `Content-Length` it was promised, so a truncated cover is never mistaken for a whole one.
     */
    fun shutdown() {
        val socket: ServerSocket?
        val pool: ExecutorService?
        synchronized(this) {
            socket = serverSocket
            pool = workers
            serverSocket = null
            workers = null
            running = false
            port = 0
            synchronized(sessions) { sessions.clear() }
        }
        runCatching { socket?.close() }.onFailure { Log.w(TAG, "cluster artwork http close failed", it) }
        if (pool != null) {
            pool.shutdownNow()
            runCatching { pool.awaitTermination(CLUSTER_ARTWORK_HTTP_SHUTDOWN_GRACE_MILLIS, TimeUnit.MILLISECONDS) }
        }
    }

    /**
     * Registers [directory] under its own [File.getName] as a servable session.
     *
     * The directory name is the session UUID the store generated, which is also the first path segment
     * of the URL, so a request can never select a directory the store did not name. The oldest entry is
     * dropped past [CLUSTER_ARTWORK_HTTP_MAX_SESSIONS], and its covers are then a 404 here while their
     * `file://` form still works.
     */
    fun register(directory: File) {
        val name = directory.name
        if (!UUID_PATTERN.matches(name)) return
        synchronized(sessions) {
            sessions.remove(name)
            sessions[name] = directory
            while (sessions.size > CLUSTER_ARTWORK_HTTP_MAX_SESSIONS) {
                val oldest = sessions.keys.firstOrNull() ?: break
                sessions.remove(oldest)
            }
        }
    }

    /**
     * The URL for the cover on [disk], built from the two path segments that identify it.
     *
     * The shape is `http://127.0.0.1:<port>/covers/<session>/<uuid>.jpg`: the same
     * `covers/<session>/<uuid>.jpg` the provider tier uses and, in the file tier, the last two segments
     * of the delivered path. It is built from [disk]'s own parent and name rather than from the
     * generated session string, so the URL is the one [resolve] will serve -- a caller that changed one
     * without the other would get a 404 rather than the wrong cover.
     */
    fun uriFor(disk: File): String =
        "$CLUSTER_ARTWORK_HTTP_BASE:$port/$CLUSTER_ARTWORK_HTTP_ROOT/" +
            "${disk.parentFile?.name.orEmpty()}/${disk.name}"

    /**
     * Accepts connections until [shutdown] closes [socket].
     *
     * One short-lived task per connection, and the only per-connection state is that task: a failure to
     * hand one to the pool closes that socket and leaves the loop serving the next reader.
     */
    private fun acceptLoop(socket: ServerSocket, pool: ExecutorService) {
        while (running) {
            val client = try {
                socket.accept()
            } catch (timeout: SocketTimeoutException) {
                continue
            } catch (closed: IOException) {
                // `shutdown` closed the listening socket, which is the normal way this loop ends.
                return
            }
            try {
                pool.execute { handle(client) }
            } catch (busy: RejectedExecutionException) {
                Log.w(TAG, "cluster artwork http request dropped (workers busy)")
                runCatching { client.close() }
            }
        }
    }

    /**
     * Reads one request, answers it and closes the connection.
     *
     * Every request gets exactly one complete response, including the ones that are not requests at
     * all. A client that connects and sends nothing is closed *with* a `404` rather than silently:
     * a reader that waits for end-of-stream would otherwise be left guessing whether we answered, and
     * a bare close is indistinguishable from a truncated `200` at the far end. The socket's read
     * timeout bounds how long that takes, so a silent client cannot hold a worker either.
     */
    private fun handle(client: Socket) {
        client.use { socket ->
            // The timeouts are the anti-wedge: a client that connects and then stalls is closed when
            // this expires, so no worker can be held by a reader that never finishes its request.
            runCatching { socket.soTimeout = CLUSTER_ARTWORK_HTTP_SOCKET_TIMEOUT_MILLIS }
            val out = BufferedOutputStream(socket.getOutputStream())
            val requestLine = try {
                readRequestLine(socket.getInputStream())
            } catch (timeout: SocketTimeoutException) {
                writeNotFound(out)
                return
            } catch (failure: IOException) {
                return
            }
            // `GET <path> <version>`, exactly: `GETX /covers/…` is not a GET, and a line with a query
            // string or a second path segment cannot match this shape, so neither reaches `resolve`.
            val parts = requestLine?.split(' ')
            if (parts == null || parts.size != 3 || parts[0] != GET) {
                writeNotFound(out)
                return
            }
            val cover = resolve(parts[1])
            if (cover == null) {
                writeNotFound(out)
                return
            }
            writeCover(out, cover)
        }
    }

    /**
     * The file a request path names, or null for anything this server must refuse.
     *
     * The shape rule and the registered-session rule live in [parseClusterArtworkHttpPath]; this adds
     * the one thing that needs the disk: the cover has to be there with a positive length, so a path
     * the retention sweep emptied is a 404 rather than a zero-byte "cover".
     */
    private fun resolve(path: String): File? {
        val cover = parseClusterArtworkHttpPath(path, { session ->
            synchronized(sessions) { sessions[session] }
        }) ?: return null
        if (!cover.isFile || cover.length() <= 0L) return null
        return cover
    }

    /**
     * Writes one `200` with the cover's real byte count and the bytes themselves, streamed from disk.
     *
     * `Content-Length` is the file's length as measured now, so the reader always knows whether it got
     * everything; `Connection: close` says this socket serves exactly one response, which also lets the
     * client read to end-of-stream. A copy that fails halfway (the cover was deleted by the retention
     * sweep while it was being read) closes the socket without the promised tail, and the reader sees a
     * short response instead of a wrong-length cover. The bytes are never buffered whole: one
     * [CLUSTER_ARTWORK_HTTP_COPY_BUFFER]-sized block is copied at a time.
     */
    private fun writeCover(out: BufferedOutputStream, cover: File) {
        val length = cover.length()
        val head = "HTTP/1.1 $HTTP_STATUS_OK $HTTP_REASON_OK\r\n" +
            "Content-Type: $HTTP_CONTENT_TYPE_JPEG\r\n" +
            "Content-Length: $length\r\n" +
            "Connection: close\r\n" +
            "\r\n"
        out.write(head.toByteArray(Charsets.ISO_8859_1))
        cover.inputStream().use { input -> input.copyTo(out, CLUSTER_ARTWORK_HTTP_COPY_BUFFER) }
        out.flush()
    }

    /** Writes the one refusal this server has: a bodyless `404`; [handle] closes the socket after it. */
    private fun writeNotFound(out: BufferedOutputStream) {
        val head = "HTTP/1.1 $HTTP_STATUS_NOT_FOUND $HTTP_REASON_NOT_FOUND\r\n" +
            "Content-Length: 0\r\n" +
            "Connection: close\r\n" +
            "\r\n"
        out.write(head.toByteArray(Charsets.ISO_8859_1))
        out.flush()
    }

    /**
     * One CRLF- or LF-terminated request line, or null when the client sent none.
     *
     * Read a byte at a time through the socket's own timeout and capped at
     * [CLUSTER_ARTWORK_HTTP_MAX_REQUEST_LINE] bytes: the cap stops a client that never sends a
     * terminator from making the handler allocate, and the timeout stops it from holding a worker while
     * it does nothing at all. Reading byte by byte is deliberate -- one HTTP request line per
     * connection is the whole protocol this tier speaks, so a read buffer would only be a place for the
     * next request to hide.
     */
    private fun readRequestLine(input: InputStream): String? {
        val line = StringBuilder(REQUEST_LINE_INITIAL_CAPACITY)
        while (line.length < CLUSTER_ARTWORK_HTTP_MAX_REQUEST_LINE) {
            val next = input.read()
            if (next < 0) return if (line.isEmpty()) null else line.toString()
            val character = next.toChar()
            if (character == '\n') return line.toString()
            if (character != '\r') line.append(character)
        }
        // Over the cap: this is not a request this server would answer anyway, and answering it would
        // mean reading the rest of an unbounded request line first.
        return null
    }

    private companion object {
        const val TAG = "DiPlay-Cluster"

        /** `http://127.0.0.1`, the prefix of every URL this tier hands over. */
        const val CLUSTER_ARTWORK_HTTP_BASE = "http://127.0.0.1"

        /** The one method this tier speaks; anything else is a 404. */
        const val GET = "GET"

        /** One 8 KiB block per read: a cover is at most 1 MiB, so this is a handful of blocks. */
        const val CLUSTER_ARTWORK_HTTP_COPY_BUFFER = 8192

        /** Concurrent readers; one request is a small file read and a small write. */
        const val CLUSTER_ARTWORK_HTTP_THREADS = 4

        /** Requests that may wait for a worker; past this the connection is dropped on purpose. */
        const val CLUSTER_ARTWORK_HTTP_QUEUE = 8

        /** Covers in old sessions stay fetchable, which bounds the session state. */
        const val CLUSTER_ARTWORK_HTTP_MAX_SESSIONS = 8

        /** How long a stalled reader may hold a worker, before and during its request. */
        const val CLUSTER_ARTWORK_HTTP_SOCKET_TIMEOUT_MILLIS = 8_000

        /** How long `accept` waits before re-checking [running]; also the shutdown latency bound. */
        const val CLUSTER_ARTWORK_HTTP_ACCEPT_TIMEOUT_MILLIS = 1_000

        /** How long an in-flight response may take to finish while [shutdown] is running. */
        const val CLUSTER_ARTWORK_HTTP_SHUTDOWN_GRACE_MILLIS = 500L

        /** The request-line cap; a real line here is about 60 bytes. */
        const val CLUSTER_ARTWORK_HTTP_MAX_REQUEST_LINE = 2048

        /** A request line is shorter than this in every real case. */
        const val REQUEST_LINE_INITIAL_CAPACITY = 64

        const val HTTP_STATUS_OK = 200
        const val HTTP_STATUS_NOT_FOUND = 404
        const val HTTP_REASON_OK = "OK"
        const val HTTP_REASON_NOT_FOUND = "Not Found"
        const val HTTP_CONTENT_TYPE_JPEG = "image/jpeg"

        /** The threads this server owns, named so a `logcat` or a thread dump identifies the tier. */
        const val CLUSTER_ARTWORK_HTTP_ACCEPT_THREAD = "diplay-artwork-http-accept"
        const val CLUSTER_ARTWORK_HTTP_THREAD = "diplay-artwork-http"
    }
}
