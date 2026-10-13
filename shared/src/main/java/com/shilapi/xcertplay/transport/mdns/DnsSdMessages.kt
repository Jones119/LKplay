package com.shilapi.xcertplay.transport.mdns

/**
 * The DNS-SD responder logic that decides *what* to advertise and *what* to answer, with no socket in it.
 *
 * Splitting it this way is deliberate: the wire format is in [DnsSdRecords], the decisions are here, and
 * only the UDP part has to be checked on the car. Everything in this file is unit-tested, which matters
 * because the failure it prevents is invisible - an iPhone that never hears `_airplay._tcp` simply never
 * opens the AirPlay stream (docs/_digest/64).
 */
internal object DnsSdMessages {
    /**
     * One advertised AirPlay receiver.
     *
     * [host] is the DNS name the address is published under, [address] its 16 link-local bytes, and [txt]
     * the same key/value set `/info` reports, so the two never drift apart.
     */
    data class Service(
        val instance: String,
        val host: String,
        val port: Int,
        val address: ByteArray,
        val txt: Map<String, String>,
    ) {
        init {
            require(instance.endsWith(".$SERVICE_SUFFIX")) {
                "an instance has to live under $SERVICE_SUFFIX, got '$instance'"
            }
            require(host.length in 1..255) { "host must not be empty" }
            require(port in 1..0xffff) { "port must be in 1..65535" }
            require(address.size == 16) { "a link-local address needs 16 bytes, got ${address.size}" }
        }

        /** The address record's owner name; the same host the SRV points at. */
        val addressName: String get() = host
    }

    private const val SERVICE_SUFFIX = "_airplay._tcp.local"

    /** The four records a receiver publishes, in the order iOS expects to see them. */
    fun announcement(service: Service): ByteArray = DnsSdRecords.encode(records(service))

    private fun records(service: Service): List<DnsSdRecords.Record> = listOf(
        DnsSdRecords.ptr(service.instance),
        DnsSdRecords.srv(service.instance, service.port, service.host),
        DnsSdRecords.txt(service.instance, service.txt),
        DnsSdRecords.aaaa(service.addressName, service.address),
    )

    /** One question out of an incoming query. */
    data class Question(val name: String, val type: Int, val unicastResponse: Boolean)

    /**
     * Reads the questions of [message]; an empty list means the message asked nothing this responder can
     * act on. Only the question section is read, and a malformed length simply ends the walk - a stray
     * packet on the multicast group must never take the link down.
     */
    fun questions(message: ByteArray): List<Question> {
        if (message.size < 12) return emptyList()
        val count = u16(message, 4)
        val out = ArrayList<Question>(count)
        var offset = 12
        repeat(count) {
            val name = readName(message, offset) ?: return out
            offset = name.second
            if (offset + 4 > message.size) return out
            val type = u16(message, offset)
            val clazz = u16(message, offset + 2)
            offset += 4
            out.add(Question(name.first, type, clazz and 0x8000 != 0))
        }
        return out
    }

    /**
     * The answers a query deserves, or null when none of its questions concerns this service.
     *
     * A `PTR` question is answered with all four records, because that is the point where an AirPlay
     * client learns the port, the target, the features and the address in one packet. A question for any
     * of the other records brings the PTR along, so the client can place what it received.
     */
    fun answers(service: Service, questions: List<Question>): ByteArray? {
        val all = records(service)
        val wanted = LinkedHashSet<DnsSdRecords.Record>()
        questions.forEach { question ->
            val knownInstance = question.name == service.instance
            when {
                question.type == DnsSdRecords.TYPE_PTR && question.name == DnsSdRecords.SERVICE -> {
                    all.forEach { wanted.add(it) }
                }
                question.type == DnsSdRecords.TYPE_SRV && knownInstance -> {
                    all.forEach { wanted.add(it) }
                }
                question.type == DnsSdRecords.TYPE_TXT && knownInstance -> {
                    all.forEach { wanted.add(it) }
                }
                question.type == DnsSdRecords.TYPE_AAAA && question.name == service.addressName -> {
                    all.forEach { wanted.add(it) }
                }
            }
        }
        if (wanted.isEmpty()) return null
        return DnsSdRecords.encode(wanted.toList())
    }

    private fun u16(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xff) shl 8) or (bytes[offset + 1].toInt() and 0xff)

    /** Returns the dotted name at [offset] and the offset just past it, or null when it is malformed. */
    private fun readName(bytes: ByteArray, start: Int): Pair<String, Int>? {
        val labels = ArrayList<String>(4)
        var offset = start
        while (true) {
            if (offset >= bytes.size) return null
            val length = bytes[offset].toInt() and 0xff
            offset++
            if (length == 0) break
            if (length > 63 || offset + length > bytes.size) return null
            labels.add(String(bytes, offset, length, Charsets.UTF_8))
            offset += length
        }
        return labels.joinToString(".") to offset
    }
}
