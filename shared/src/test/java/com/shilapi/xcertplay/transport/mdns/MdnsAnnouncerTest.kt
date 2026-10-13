package com.shilapi.xcertplay.transport.mdns

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The announcement sender, with a fake socket: which address it talks to, how many times, and what it
 * reports when the link refuses. What the car has to add later is only whether the iPhone answers an
 * announcement with an AirPlay connection (docs/_digest/64).
 */
class MdnsAnnouncerTest {
    private class FakeSocket(
        private val openResult: Int = 7,
        private val writeResult: (Int) -> Int = { it },
    ) : MdnsDatagramSocket {
        val sent = ArrayList<Pair<ByteArray, Int>>()
        var closed: Int? = null
        var opens = 0

        override fun open(): Int {
            opens++
            return openResult
        }

        override fun sendTo(fd: Int, message: ByteArray, address: ByteArray, port: Int): Int {
            sent.add(address to port)
            return writeResult(message.size)
        }

        override fun close(fd: Int) {
            closed = fd
        }
    }

    private val message = DnsSdMessages.announcement(
        DnsSdMessages.Service(
            instance = "DiPlay._airplay._tcp.local",
            host = "DiPlay.local",
            port = 7100,
            address = ByteArray(16) { if (it == 15) 2 else 0 },
            txt = mapOf("srcvers" to "220.68"),
        ),
    )

    @Test
    fun anAnnouncementGoesToTheMdnsGroupOnPort5353TwiceAndTheSocketIsClosed() {
        val socket = FakeSocket()
        val log = ArrayList<String>()
        assertTrue(MdnsAnnouncer(socket) { log.add(it) }.announce(message, times = 2, gapMillis = 0))
        assertEquals(2, socket.sent.size)
        socket.sent.forEach { (address, port) ->
            assertArrayEquals(byteArrayOf(0xff.toByte(), 0x02, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0xfb.toByte()), address)
            assertEquals(5353, port)
        }
        assertEquals(7, socket.closed)
        assertTrue(log.any { it.contains("announcement 1/2") })
        assertTrue(log.any { it.contains("announcement 2/2") })
    }

    @Test
    fun aSocketThatCannotBeOpenedIsReportedAndNothingIsSent() {
        val socket = FakeSocket(openResult = -92)
        assertFalse(MdnsAnnouncer(socket) {}.announce(message))
        assertEquals(0, socket.sent.size)
        assertEquals(null, socket.closed)
    }

    @Test
    fun aShortWriteIsReportedAndStopsTheSecondAttempt() {
        val socket = FakeSocket(writeResult = { it - 4 })
        val log = ArrayList<String>()
        assertFalse(MdnsAnnouncer(socket) { log.add(it) }.announce(message, times = 2, gapMillis = 0))
        assertEquals(1, socket.sent.size)
        assertTrue(log.any { it.contains("wrote") })
        assertEquals(7, socket.closed)
    }
}
