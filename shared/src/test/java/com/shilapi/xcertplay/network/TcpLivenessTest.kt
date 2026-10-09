package com.shilapi.xcertplay.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket

/**
 * The per-option behaviour EasyPlay v71 added to its TCP liveness tuning (docs/_digest/42 C4): every
 * option is attempted on its own and the report says which ones actually took effect, which is the
 * only way to tell on the CS11 whether the tuning ran at all - the previous code returned before any
 * of it below API 29.
 */
class TcpLivenessTest {
    private class FakeOptions(private vararg val failing: Int) : TcpLiveness.SocketOptions {
        val applied = LinkedHashMap<Int, Int>()
        var closed = false

        override fun set(option: Int, value: Int) {
            if (option in failing) throw IllegalArgumentException("option $option refused")
            applied[option] = value
        }

        override fun close() {
            closed = true
        }
    }

    private fun <T> withSocket(block: (Socket) -> T): T {
        ServerSocket(0).use { server ->
            Socket("127.0.0.1", server.localPort).use { socket -> return block(socket) }
        }
    }

    @Test
    fun appliesAllFourOptionsAndReportsBothHalves() = withSocket { socket ->
        val options = FakeOptions()
        val lines = mutableListOf<String>()
        TcpLiveness.configure(socket, { options }, { lines += it })

        assertEquals(mapOf(4 to 10, 5 to 3, 6 to 3, 18 to 20_000), options.applied)
        assertTrue(options.closed)
        assertTrue(lines.single(), lines.single().contains("keepalive=true userTimeout=true"))
        assertTrue(lines.single().contains("idle=10s interval=3s count=3"))
    }

    @Test
    fun aRefusedTimingOptionMakesKeepaliveFalseButLeavesTheDeadlineAlone() = withSocket { socket ->
        val options = FakeOptions(5)
        val lines = mutableListOf<String>()
        TcpLiveness.configure(socket, { options }, { lines += it })

        assertFalse(options.applied.containsKey(5))
        assertTrue(options.applied.containsKey(18))
        assertTrue(lines.any { it.contains("interval failed: IllegalArgumentException") })
        assertTrue(lines.last().contains("keepalive=false userTimeout=true"))
    }

    @Test
    fun aRefusedDeadlineIsReportedSeparately() = withSocket { socket ->
        val options = FakeOptions(18)
        val lines = mutableListOf<String>()
        TcpLiveness.configure(socket, { options }, { lines += it })

        assertTrue(lines.any { it.contains("user_timeout failed") })
        assertTrue(lines.last().contains("keepalive=true userTimeout=false"))
    }

    @Test
    fun aFailedDescriptorReportsFailureWithoutPretendingToTune() = withSocket { socket ->
        val lines = mutableListOf<String>()
        TcpLiveness.configure(socket, { null }, { lines += it })

        assertTrue(lines.single(), lines.single().contains("keepalive=false userTimeout=false"))
    }

    @Test
    fun aThrowingDescriptorIsContainedAndStillReported() = withSocket { socket ->
        val lines = mutableListOf<String>()
        TcpLiveness.configure(socket, { throw IllegalStateException("no fd") }, { lines += it })

        assertTrue(lines.any { it.contains("descriptor failed: IllegalStateException") })
        assertTrue(lines.last().contains("keepalive=false userTimeout=false"))
    }
}
