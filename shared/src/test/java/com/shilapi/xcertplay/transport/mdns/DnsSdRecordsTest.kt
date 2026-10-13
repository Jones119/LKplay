package com.shilapi.xcertplay.transport.mdns

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the DNS-SD wire format the userspace link will advertise. It is pure, so it is checked here
 * rather than on the car; what the car has to confirm later is only that the iPhone answers the
 * advertisement with an inbound AirPlay connection (docs/_digest/64).
 */
class DnsSdRecordsTest {
    @Test
    fun aNameIsALabelSequenceEndingInAZeroOctet() {
        assertArrayEquals(
            byteArrayOf(1, 'a'.code.toByte(), 5, 'l'.code.toByte(), 'o'.code.toByte(), 'c'.code.toByte(),
                'a'.code.toByte(), 'l'.code.toByte(), 0),
            DnsSdRecords.name("a.local"),
        )
        assertArrayEquals(byteArrayOf(0), DnsSdRecords.name("."))
    }

    @Test
    fun aPtrMapsTheServiceTypeToAnInstanceAndDoesNotFlushOtherReceivers() {
        val record = DnsSdRecords.ptr("DiPlay._airplay._tcp.local")
        assertEquals(DnsSdRecords.TYPE_PTR, record.type)
        assertEquals(DnsSdRecords.SERVICE, record.name)
        assertEquals(false, record.flush)
        assertArrayEquals(DnsSdRecords.name("DiPlay._airplay._tcp.local"), record.rdata)
    }

    @Test
    fun aSrvCarriesPortAndTargetBehindSixBytesOfHeader() {
        val record = DnsSdRecords.srv("DiPlay._airplay._tcp.local", 7100, "DiPlay.local")
        val rdata = record.rdata
        // priority 0, weight 0, port 7100, then the target name.
        assertArrayEquals(byteArrayOf(0, 0, 0, 0, 0x1b, 0xbc.toByte()), rdata.copyOfRange(0, 6))
        assertArrayEquals(
            DnsSdRecords.name("DiPlay.local"),
            rdata.copyOfRange(6, rdata.size),
        )
    }

    @Test
    fun aTxtWritesKeyValuePairsAndBareKeysForEmptyValues() {
        val record = DnsSdRecords.txt(
            "DiPlay._airplay._tcp.local",
            linkedMapOf("deviceid" to "9e:92:4f:09:bf:8b", "features" to "0x5A7FFFF7", "srcvers" to ""),
        )
        val text = String(record.rdata, Charsets.UTF_8)
        assertTrue(text.startsWith("${"deviceid=9e:92:4f:09:bf:8b".length.toChar()}deviceid=9e:92:4f:09:bf:8b"))
        assertTrue(text.contains("features=0x5A7FFFF7"))
        // The empty value becomes a bare key, which is how DNS-SD encodes a valueless attribute.
        assertTrue(text.endsWith("srcvers"))
    }

    @Test
    fun anEmptyTxtStillCarriesOneZeroLengthString() {
        assertArrayEquals(byteArrayOf(0), DnsSdRecords.txt("X._airplay._tcp.local", emptyMap()).rdata)
    }

    @Test
    fun anEncodedResponseHasTheMdnsHeaderAndEveryRecordCounted() {
        val address = ByteArray(16) { if (it == 15) 2 else 0 }
        val records = listOf(
            DnsSdRecords.ptr("DiPlay._airplay._tcp.local"),
            DnsSdRecords.srv("DiPlay._airplay._tcp.local", 7100, "DiPlay.local"),
            DnsSdRecords.txt("DiPlay._airplay._tcp.local", mapOf("srcvers" to "220.68")),
            DnsSdRecords.aaaa("DiPlay.local", address),
        )
        val bytes = DnsSdRecords.encode(records)
        assertEquals(0, bytes[0].toInt())
        assertEquals(0, bytes[1].toInt())
        assertEquals(0x84, bytes[2].toInt() and 0xff) // response, authoritative
        assertEquals(0, bytes[3].toInt())
        assertEquals(0, bytes[4].toInt()) // questions
        assertEquals(0, bytes[5].toInt())
        assertEquals(0, bytes[6].toInt()) // answers = 4
        assertEquals(4, bytes[7].toInt())
        // The AAAA record is last: 16 address bytes after its own header.
        assertArrayEquals(address, bytes.copyOfRange(bytes.size - 16, bytes.size))
    }

    @Test(expected = IllegalArgumentException::class)
    fun anAaaaWithoutSixteenBytesIsRefused() {
        DnsSdRecords.aaaa("DiPlay.local", ByteArray(4))
    }
}
