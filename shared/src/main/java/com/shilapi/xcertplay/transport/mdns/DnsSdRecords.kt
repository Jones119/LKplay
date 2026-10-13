package com.shilapi.xcertplay.transport.mdns

/**
 * The DNS-SD records the userspace link has to advertise so an iPhone can find its AirPlay receiver.
 *
 * Why this exists: the app's Bonjour side is JmDNS, which binds the platform's network stack, and on the
 * userspace NCM path the advertised address (`fe80::2`) lives **only inside lwIP** - so nothing publishes
 * `_airplay._tcp` on that link and the iPhone never opens the AirPlay stream (measured on the car: full
 * iAP2 control, `carplay-start-session` sent, zero inbound connections; docs/_digest/64).
 *
 * This half of the work is deliberately pure: it turns records into wire bytes, so it is unit-tested
 * without a device. The UDP socket that sends them over lwIP is the next step.
 *
 * No name compression: every record carries its full name. That costs bytes on a link that has plenty,
 * and it keeps the encoder small enough to be obviously correct.
 */
internal object DnsSdRecords {
    /** The service instance type an AirPlay receiver publishes. */
    const val SERVICE = "_airplay._tcp.local"

    /** mDNS is fixed to port 5353 and, on the link-local scope, to this group. */
    const val PORT = 5353
    const val GROUP_V6 = "ff02::fb"

    /** The 75-minute TTL mDNS recommends for records that are announced repeatedly. */
    const val TTL = 4500L

    const val TYPE_PTR = 12
    const val TYPE_TXT = 16
    const val TYPE_AAAA = 28
    const val TYPE_SRV = 33
    const val CLASS_IN = 1

    /** Cache-flush, as mDNS uses for the records only this responder is authoritative for. */
    const val CLASS_IN_FLUSH = 0x8001

    data class Record(val type: Int, val name: String, val rdata: ByteArray, val flush: Boolean = true)

    /** Encodes a dotted name as DNS labels terminated by a zero length octet. */
    fun name(value: String): ByteArray {
        // The root name is a single zero octet, and it is the one input that has no label at all.
        val trimmed = value.trim('.')
        if (trimmed.isEmpty()) return byteArrayOf(0)
        val out = ArrayList<Byte>(value.length + 2)
        trimmed.split('.').forEach { label ->
            val bytes = label.toByteArray(Charsets.UTF_8)
            require(bytes.size in 1..63) { "DNS label '$label' must be 1..63 bytes" }
            out.add(bytes.size.toByte())
            bytes.forEach { out.add(it) }
        }
        out.add(0)
        return out.toByteArray()
    }

    /**
     * The PTR that maps the service type to an instance. It is sent without cache-flush because the
     * service type is shared: several receivers may publish under it.
     */
    fun ptr(instance: String): Record = Record(TYPE_PTR, SERVICE, name(instance), flush = false)

    /** SRV carries priority, weight, port and target host; nothing here uses a non-zero priority. */
    fun srv(instance: String, port: Int, target: String): Record {
        require(port in 1..0xffff) { "SRV port must be in 1..65535" }
        return Record(TYPE_SRV, instance, u16(0) + u16(0) + u16(port) + name(target))
    }

    /**
     * TXT carries the service's key/value pairs. An empty value is written as a bare key, which is how
     * DNS-SD represents a valueless attribute (and what `/info`'s `deviceid`-style flags look like).
     */
    fun txt(instance: String, entries: Map<String, String>): Record {
        val out = ArrayList<Byte>()
        entries.forEach { (key, value) ->
            val text = if (value.isEmpty()) key else "$key=$value"
            val bytes = text.toByteArray(Charsets.UTF_8)
            require(bytes.size <= 255) { "TXT entry '$key' must be at most 255 bytes" }
            out.add(bytes.size.toByte())
            bytes.forEach { out.add(it) }
        }
        // A TXT record must contain at least one string, even when there is nothing to say.
        if (out.isEmpty()) out.add(0)
        return Record(TYPE_TXT, instance, out.toByteArray())
    }

    /** The address the iPhone should connect to; link-local, so it has to travel with its scope id. */
    fun aaaa(host: String, address: ByteArray): Record {
        require(address.size == 16) { "an AAAA record needs 16 address bytes, got ${address.size}" }
        return Record(TYPE_AAAA, host, address)
    }

    /**
     * A DNS-SD message carrying [records] as answers. Multicast DNS uses an id of zero and the response
     * bit set; the same layout serves a query, which carries its questions where this carries answers.
     */
    fun encode(records: List<Record>, id: Int = 0, response: Boolean = true): ByteArray {
        require(id in 0..0xffff) { "DNS id must fit in u16" }
        val out = ArrayList<Byte>(64 + records.size * 32)
        u16(id).forEach { out.add(it) }
        u16(if (response) 0x8400 else 0x0000).forEach { out.add(it) }
        u16(0).forEach { out.add(it) } // questions
        u16(records.size).forEach { out.add(it) } // answers
        u16(0).forEach { out.add(it) } // authority
        u16(0).forEach { out.add(it) } // additional
        records.forEach { record ->
            name(record.name).forEach { out.add(it) }
            u16(record.type).forEach { out.add(it) }
            u16(if (record.flush) CLASS_IN_FLUSH else CLASS_IN).forEach { out.add(it) }
            u32(TTL).forEach { out.add(it) }
            u16(record.rdata.size).forEach { out.add(it) }
            record.rdata.forEach { out.add(it) }
        }
        return out.toByteArray()
    }

    private fun u16(value: Int): ByteArray =
        byteArrayOf(((value ushr 8) and 0xff).toByte(), (value and 0xff).toByte())

    private fun u32(value: Long): ByteArray = byteArrayOf(
        ((value ushr 24) and 0xff).toByte(),
        ((value ushr 16) and 0xff).toByte(),
        ((value ushr 8) and 0xff).toByte(),
        (value and 0xff).toByte(),
    )
}
