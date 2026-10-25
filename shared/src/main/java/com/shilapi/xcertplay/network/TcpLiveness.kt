package com.shilapi.xcertplay.network

import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.Os
import java.io.FileDescriptor
import java.net.Socket

/**
 * Detect a vanished peer, without treating a legitimately idle CarPlay screen as a failure.
 *
 * Ported from EasyPlay v71's `network/TcpLiveness` (docs/_digest/42 C4). The version we had before
 * returned straight away below API 29, so on the CS11 (API 19) only `setKeepAlive(true)` ran and the
 * kernel kept its **7200 s** default idle time: a phone that walked away left the session's TCP alive
 * for two hours. Every option is now attempted individually, and the result says which ones worked.
 */
internal object TcpLiveness {
    // Linux UAPI <linux/tcp.h>. Android's SDK omits the three keepalive fields and calls the last one
    // OsConstants.TCP_USER_TIMEOUT only from API 21, so all four are numeric here.
    private const val IPPROTO_TCP = 6
    private const val TCP_KEEPIDLE = 4
    private const val TCP_KEEPINTVL = 5
    private const val TCP_KEEPCNT = 6
    private const val TCP_USER_TIMEOUT = 18

    private const val IDLE_SECONDS = 10
    private const val INTERVAL_SECONDS = 3
    private const val COUNT = 3
    private const val USER_TIMEOUT_MILLIS = 20_000

    fun configure(socket: Socket, diagnostic: (String) -> Unit) =
        configure(socket, ::openOptions, diagnostic)

    /** [openOptions] is injectable so the per-option behaviour is testable without a live socket. */
    internal fun configure(
        socket: Socket,
        openOptions: (Socket) -> SocketOptions?,
        diagnostic: (String) -> Unit,
    ) {
        if (!attempt("enable", diagnostic) { socket.keepAlive = true }) {
            report(false, false, diagnostic)
            return
        }
        val options = try {
            openOptions(socket)
        } catch (failure: Exception) {
            diagnostic("TCP peer health descriptor failed: ${failure.javaClass.simpleName}")
            null
        }
        if (options == null) {
            report(false, false, diagnostic)
            return
        }
        try {
            val idle = attempt("idle", diagnostic) { options.set(TCP_KEEPIDLE, IDLE_SECONDS) }
            val interval = attempt("interval", diagnostic) { options.set(TCP_KEEPINTVL, INTERVAL_SECONDS) }
            val count = attempt("count", diagnostic) { options.set(TCP_KEEPCNT, COUNT) }
            // A peer that vanishes without a FIN is only noticed after the full keepalive sequence, so
            // the deadline is reported separately: it bounds how long a dead session can linger.
            val userTimeout = attempt("user_timeout", diagnostic) {
                options.set(TCP_USER_TIMEOUT, USER_TIMEOUT_MILLIS)
            }
            // Keep this line: it is the judge for whether the tuning took effect on the head unit.
            report(idle && interval && count, userTimeout, diagnostic)
        } finally {
            runCatching { options.close() }
        }
    }

    private inline fun attempt(what: String, diagnostic: (String) -> Unit, block: () -> Unit): Boolean =
        try {
            block()
            true
        } catch (failure: Exception) {
            diagnostic("TCP peer health $what failed: ${failure.javaClass.simpleName}")
            false
        }

    private fun report(keepalive: Boolean, userTimeout: Boolean, diagnostic: (String) -> Unit) {
        diagnostic(
            "TCP peer health configured idle=${IDLE_SECONDS}s interval=${INTERVAL_SECONDS}s " +
                "count=$COUNT keepalive=$keepalive userTimeout=$userTimeout; no video-idle timeout",
        )
    }

    /** A descriptor we own plus the platform's setsockopt entry point. */
    internal interface SocketOptions {
        fun set(option: Int, value: Int)
        fun close()
    }

    private fun openOptions(socket: Socket): SocketOptions? {
        val duplicate = duplicateDescriptor(socket) ?: return null
        val fd = duplicate.fileDescriptor
        return object : SocketOptions {
            override fun set(option: Int, value: Int) {
                libcoreSetSockopt(fd, option, value)
            }

            override fun close() = duplicate.close()
        }
    }

    /**
     * A duplicate we may close. `ParcelFileDescriptor.fromSocket()` takes ownership of the live
     * session descriptor (this ROM can even return null), so closing it would tear the session down;
     * `getFileDescriptor$()` plus `ParcelFileDescriptor.dup()` gives us a private copy instead.
     */
    private fun duplicateDescriptor(socket: Socket): ParcelFileDescriptor? = try {
        val original = synchronized(socket) {
            Socket::class.java.getMethod("getFileDescriptor\$").invoke(socket) as? FileDescriptor
        }
        original?.let { ParcelFileDescriptor.dup(it) }
    } catch (failure: Exception) {
        null
    }

    /** `android.system.Os` arrived in API 21; below it the same call lives in libcore. */
    private fun libcoreSetSockopt(fd: FileDescriptor, option: Int, value: Int) {
        val os = Class.forName("libcore.io.Libcore").getField("os").get(null)
        os.javaClass.getMethod(
            "setsockoptInt",
            FileDescriptor::class.java,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
        ).invoke(os, fd, IPPROTO_TCP, option, value)
    }
}
