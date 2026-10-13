package com.shilapi.xcertplay.transport.mdns

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The responder's decisions, checked without a socket: what a receiver advertises, how questions are read
 * back, and which questions it answers. The car only has to confirm the last mile - that the iPhone
 * answers the advertisement with an AirPlay connection (docs/_digest/64).
 */
class DnsSdMessagesTest {
    private val address = ByteArray(16) { if (it == 15) 2 else 0 }

    private fun service() = DnsSdMessages.Service(
        instance = "DiPlay._airplay._tcp.local",
        host = "DiPlay.local",
        port = 7100,
        address = address,
        txt = linkedMapOf("deviceid" to "9e:92:4f:09:bf:8b", "features" to "0x5A7FFFF7", "srcvers" to "220.68"),
    )

    private fun query(name: String, type: Int, unicast: Boolean = false): ByteArray {
        val out = ArrayList<Byte>()
        repeat(4) { out.add(0) } // id and flags
        out.add(0); out.add(1)   // one question
        repeat(6) { out.add(0) } // no other sections
        DnsSdRecords.name(name).forEach { out.add(it) }
        out.add(((type ushr 8) and 0xff).toByte()); out.add((type and 0xff).toByte())
        val clazz = if (unicast) 0x8001 else 0x0001
        out.add(((clazz ushr 8) and 0xff).toByte()); out.add((clazz and 0xff).toByte())
        return out.toByteArray()
    }

    @Test
    fun anAnnouncementCarriesTheFourRecordsOfTheService() {
        val bytes = DnsSdMessages.announcement(service())
        assertEquals(0x84, bytes[2].toInt() and 0xff) // an announcement is a response, authoritative
        assertEquals(0, bytes[3].toInt())
        assertEquals(4, bytes[7].toInt()) // four answers
        // PTR, SRV, TXT and AAAA type codes all appear in the message.
        listOf(12, 33, 16, 28).forEach { type ->
            val high = (type ushr 8) and 0xff
            val low = type and 0xff
            assertTrue(
                "type $type is missing from the announcement",
                (0 until bytes.size - 1).any { bytes[it].toInt() and 0xff == high && bytes[it + 1].toInt() and 0xff == low },
            )
        }
    }

    @Test
    fun aQuestionIsReadBackWithItsNameTypeAndUnicastWish() {
        val questions = DnsSdMessages.questions(query("_airplay._tcp.local", DnsSdRecords.TYPE_PTR, unicast = true))
        assertEquals(1, questions.size)
        assertEquals("_airplay._tcp.local", questions[0].name)
        assertEquals(DnsSdRecords.TYPE_PTR, questions[0].type)
        assertTrue(questions[0].unicastResponse)
    }

    @Test
    fun aMalformedOrShortMessageAsksNothing() {
        assertEquals(0, DnsSdMessages.questions(ByteArray(4)).size)
        assertEquals(0, DnsSdMessages.questions(ByteArray(12)).size)
    }

    @Test
    fun aPtrQuestionIsAnsweredWithAllFourRecords() {
        val bytes = DnsSdMessages.answers(
            service(),
            DnsSdMessages.questions(query("_airplay._tcp.local", DnsSdRecords.TYPE_PTR)),
        )
        assertNotNull(bytes)
        assertEquals(4, bytes!![7].toInt())
    }

    @Test
    fun aTxtQuestionForTheInstanceIsAnsweredAndAnUnrelatedNameIsNot() {
        val asked = DnsSdMessages.questions(query("DiPlay._airplay._tcp.local", DnsSdRecords.TYPE_TXT))
        assertNotNull(DnsSdMessages.answers(service(), asked))
        val unrelated = DnsSdMessages.questions(query("_raop._tcp.local", DnsSdRecords.TYPE_PTR))
        assertNull(DnsSdMessages.answers(service(), unrelated))
    }

    @Test
    fun anInstanceHasToLiveUnderTheAirPlayServiceType() {
        val bad = runCatching {
            DnsSdMessages.Service("DiPlay._raop._tcp.local", "DiPlay.local", 7100, address, emptyMap())
        }
        assertTrue(bad.isFailure)
    }
}
