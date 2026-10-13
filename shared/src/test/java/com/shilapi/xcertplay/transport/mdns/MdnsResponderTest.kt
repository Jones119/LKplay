package com.shilapi.xcertplay.transport.mdns

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The answer path, with a fake socket: what it binds, which group it joins, and what it replies to a query.
 * The car only has to add whether the iPhone then opens the AirPlay stream (docs/_digest/64 §8).
 */
class MdnsResponderTest {
    /** Replays [incoming] and then reports -ENODEV, which is how the responder learns to stop. */
    private class FakeSocket(
        private val incoming: List<ByteArray>,
        private val openResult: Int = 9,
    ) : MdnsQuerySocket {
        val log = ArrayList<String>()
        private var index = 0

        override fun open(): Int = openResult

        override fun bind(fd: Int, address: ByteArray, port: Int): Int {
            log.add("bind:${address.joinToString("") { "%02x".format(it) }}:$port")
            return 0
        }

        override fun joinGroup(fd: Int, group: ByteArray, interfaceIndex: Int): Int {
            log.add("join:${group.joinToString("") { "%02x".format(it) }}:$interfaceIndex")
            return 0
        }

        override fun receive(fd: Int, buffer: ByteArray, fromAddress: ByteArray, fromPort: IntArray): Int {
            if (index >= incoming.size) return -19 // ENODEV: the loop ends instead of spinning
            val message = incoming[index++]
            message.copyInto(buffer)
            fromAddress[15] = 0x99.toByte()
            fromPort[0] = 5353
            return message.size
        }

        override fun sendTo(fd: Int, message: ByteArray, address: ByteArray, port: Int): Int {
            log.add("send:${message.size}:$port")
            return message.size
        }

        override fun setTimeout(fd: Int, millis: Int): Int {
            log.add("timeout:$millis")
            return 0
        }

        override fun close(fd: Int) {
            log.add("close:$fd")
        }

        val sent: List<String> get() = log.filter { it.startsWith("send:") }
    }

    private val service = DnsSdMessages.Service(
        instance = "DiPlay._airplay._tcp.local",
        host = "DiPlay.local",
        port = 7000,
        address = ByteArray(16) { if (it == 15) 2 else 0 },
        txt = mapOf("features" to "0x615653AEE2", "srcvers" to "220.68"),
    )

    private fun query(name: String, type: Int): ByteArray {
        val out = ArrayList<Byte>()
        repeat(4) { out.add(0) }
        out.add(0); out.add(1) // one question
        repeat(6) { out.add(0) }
        DnsSdRecords.name(name).forEach { out.add(it) }
        out.add(((type ushr 8) and 0xff).toByte()); out.add((type and 0xff).toByte())
        out.add(0); out.add(1)
        return out.toByteArray()
    }

    @Test
    fun itBindsTheMdnsPortJoinsTheGroupAndAnswersAPtrQuestion() {
        val socket = FakeSocket(listOf(query("_airplay._tcp.local", DnsSdRecords.TYPE_PTR)))
        val log = ArrayList<String>()
        assertTrue(MdnsResponder(socket) { log.add(it) }.run(service, interfaceIndex = 0))
        assertEquals("bind:${"00".repeat(16)}:5353", socket.log.first { it.startsWith("bind:") })
        assertEquals("join:ff0200000000000000000000000000fb:0", socket.log.first { it.startsWith("join:") })
        val sent = socket.sent.single()
        val bytes = sent.split(":")[1].toInt()
        // Four records: the PTR, the SRV, the TXT and the AAAA, i.e. clearly more than a bare header.
        assertTrue("an answer of $bytes bytes should carry the records", bytes > 100)
        assertEquals("send:$bytes:5353", sent)
        assertTrue(log.any { it.contains("answered 1 question") })
        assertTrue(log.any { it.contains("joined ff02::fb") })
        assertTrue("the socket must be closed", socket.log.any { it.startsWith("close:") })
    }

    @Test
    fun aQueryForAnotherServiceIsNotAnswered() {
        val socket = FakeSocket(listOf(query("_raop._tcp.local", DnsSdRecords.TYPE_PTR)))
        MdnsResponder(socket) {}.run(service)
        assertEquals(0, socket.sent.size)
        assertTrue(socket.log.any { it.startsWith("close:") })
    }

    @Test
    fun aSocketThatCannotBeOpenedIsReportedAndNothingIsBound() {
        val socket = FakeSocket(emptyList(), openResult = -92)
        val log = ArrayList<String>()
        assertFalse(MdnsResponder(socket) { log.add(it) }.run(service))
        assertEquals(1, log.count { it.contains("errno 92") })
        assertEquals(0, socket.log.count { it.startsWith("bind:") })
    }

    @Test
    fun theJoinedGroupIsTheSameSixteenBytesTheAnnouncementSendsTo() {
        val socket = FakeSocket(emptyList())
        MdnsResponder(socket) {}.run(service)
        val joined = socket.log.first { it.startsWith("join:") }.removePrefix("join:").substringBefore(":")
        assertArrayEquals(
            byteArrayOf(0xff.toByte(), 0x02, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0xfb.toByte()),
            joined.chunked(2).map { it.toInt(16).toByte() }.toByteArray(),
        )
    }
}
