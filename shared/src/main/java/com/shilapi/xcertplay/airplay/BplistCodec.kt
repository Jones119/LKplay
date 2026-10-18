package com.shilapi.xcertplay.airplay

/**
 * Minimal Apple binary property list (bplist00) codec for the CarPlay control channel.
 *
 * It covers the subset the stack emits and reads: dictionaries, arrays, ASCII and UTF-16
 * strings, raw data, non-negative integers, 32/64-bit reals, and booleans. Dictionary keys are
 * serialized in insertion order, matching the reference implementation this stack targets.
 */
object BplistCodec {
    private val magic = "bplist00".toByteArray(Charsets.US_ASCII)

    fun decode(bytes: ByteArray): Any? {
        require(bytes.size >= 40) { "bplist: too short" }
        require(bytes.copyOfRange(0, magic.size).contentEquals(magic)) { "bplist: bad magic" }

        val trailer = bytes.size - 32
        val offsetSize = bytes[trailer + 6].toInt() and 0xff
        val refSize = bytes[trailer + 7].toInt() and 0xff
        val numObjects = readBigEndianLong(bytes, (trailer + 8).toLong(), 8).toInt()
        val topObject = readBigEndianLong(bytes, (trailer + 16).toLong(), 8).toInt()
        val offsetTable = readBigEndianLong(bytes, (trailer + 24).toLong(), 8)

        val offsets = LongArray(numObjects)
        for (index in offsets.indices) {
            offsets[index] = readBigEndianLong(bytes, offsetTable + index.toLong() * offsetSize, offsetSize)
        }
        return readObject(bytes, offsets, refSize, topObject)
    }

    fun encode(root: Any?): ByteArray {
        val nodes = ArrayList<Node>()

        fun add(value: Any?): Int {
            val index = nodes.size
            nodes.add(Leaf(ByteArray(0)))
            when (value) {
                is Boolean -> nodes[index] = Leaf(byteArrayOf(if (value) 0x09 else 0x08))
                is java.math.BigInteger -> {
                    require(value.signum() >= 0) { "bplist: negative integers are not supported" }
                    require(value.bitLength() <= 64) { "bplist: integer exceeds 64 bits" }
                    nodes[index] = Leaf(encodeUnsignedInt(value))
                }
                is Byte, is Short, is Int, is Long -> {
                    val number = (value as Number).toLong()
                    nodes[index] = if (number >= 0) {
                        Leaf(encodeInt(number))
                    } else {
                        Leaf(byteArrayOf(0x23) + bigEndian(number.toDouble().toRawBits(), 8))
                    }
                }
                is Float, is Double -> {
                    val number = (value as Number).toDouble()
                    nodes[index] = Leaf(byteArrayOf(0x23) + bigEndian(number.toRawBits(), 8))
                }
                is String -> nodes[index] = Leaf(encodeString(value))
                is ByteArray -> nodes[index] = Leaf(marker(0x4, value.size) + value)
                is List<*> -> {
                    val refs = value.map { add(it) }.toIntArray()
                    nodes[index] = Container(marker(0xa, value.size), refs)
                }
                is Map<*, *> -> {
                    val entries = value.entries.toList()
                    val keyRefs = entries.map { add(it.key.toString()) }.toIntArray()
                    val valueRefs = entries.map { add(it.value) }.toIntArray()
                    nodes[index] = Container(marker(0xd, entries.size), keyRefs + valueRefs)
                }
                else -> throw IllegalArgumentException("bplist: unsupported value $value")
            }
            return index
        }

        val topIndex = add(root)
        val refSize = when {
            nodes.size > 0xffff -> 4
            nodes.size > 0xff -> 2
            else -> 1
        }

        val parts = ArrayList<ByteArray>(nodes.size + 2)
        parts.add(magic)
        var cursor = magic.size
        val offsets = ArrayList<Int>(nodes.size)
        for (node in nodes) {
            offsets.add(cursor)
            val serialized = when (node) {
                is Leaf -> node.body
                is Container -> node.head + refBytes(node.refs, refSize)
            }
            parts.add(serialized)
            cursor += serialized.size
        }

        val offsetTableOffset = cursor
        val offsetSize = when {
            cursor > 0xffff -> 4
            cursor > 0xff -> 2
            else -> 1
        }
        for (offset in offsets) parts.add(bigEndian(offset.toLong(), offsetSize))

        val trailer = ByteArray(32)
        trailer[6] = offsetSize.toByte()
        trailer[7] = refSize.toByte()
        writeBigEndian(trailer, 8, nodes.size.toLong(), 8)
        writeBigEndian(trailer, 16, topIndex.toLong(), 8)
        writeBigEndian(trailer, 24, offsetTableOffset.toLong(), 8)
        parts.add(trailer)
        return concatBytes(*parts.toTypedArray())
    }

    /**
     * The `hidSendReport` command body, written from a fixed layout instead of through [encode].
     *
     * Why (docs/_digest/69 P0-4): a drag emits 60-120 touch states per second, and [encode] built a
     * `Node` plus several temporary lists and arrays for every string and value — roughly 60 objects
     * per report, by far the largest per-event allocation in the app. The shape is completely fixed:
     * one 3-entry dictionary (uuid / type / hidReport) whose keys and whose `type` value are
     * constants, so only the uid string and the report bytes vary.
     *
     * **The output is byte-identical to `encode(command)`** for that map; `BplistHidReportTest`
     * asserts it across uid values and report lengths, and the earlier touch-equivalence test
     * (docs/_digest/54) set the precedent for pinning this against the generic encoder.
     */
    fun encodeHidReport(uid: Int, report: ByteArray): ByteArray {
        val uidString = uid.toString(16)
        val uidBody = encodeString(uidString)
        val reportBody = marker(0x4, report.size) + report

        // Node order is the order encode() adds them: the root map, then **all keys**, then **all
        // values** — encode() walks `entries.map { key }` and `entries.map { value }` as two separate
        // passes, so the keys are not interleaved with their values.
        // The two key strings repeat because add() does not deduplicate.
        // Offsets are locals: this object is a singleton shared by the touch and control threads, so
        // anything stored in a field here would race.
        val offsetRoot = magic.size
        val offsetTypeKey = offsetRoot + ROOT_MAP_BODY
        val offsetUuidKey = offsetTypeKey + TYPE_KEY_BODY
        val offsetHidReportKey = offsetUuidKey + UUID_KEY_BODY
        val offsetTypeValue = offsetHidReportKey + HID_REPORT_KEY_BODY
        val offsetUidValue = offsetTypeValue + TYPE_VALUE_BODY
        val offsetReport = offsetUidValue + uidBody.size
        val offsets = intArrayOf(
            offsetRoot,
            offsetTypeKey,
            offsetUuidKey,
            offsetHidReportKey,
            offsetTypeValue,
            offsetUidValue,
            offsetReport,
        )

        val parts = ArrayList<ByteArray>(magic.size + offsets.size + 1)
        parts.add(magic)
        parts.add(ROOT_MAP_BODY_BYTES)
        parts.add(TYPE_KEY_BODY_BYTES)
        parts.add(UUID_KEY_BODY_BYTES)
        parts.add(HID_REPORT_KEY_BODY_BYTES)
        parts.add(TYPE_VALUE_BODY_BYTES)
        parts.add(uidBody)
        parts.add(reportBody)

        val offsetTableOffset = offsetReport + reportBody.size
        val total = offsetTableOffset + offsets.size + BPLIST_TRAILER_BYTES
        val offsetSize = when {
            total > 0xffff -> 4
            total > 0xff -> 2
            else -> 1
        }
        for (offset in offsets) parts.add(bigEndian(offset.toLong(), offsetSize))

        val trailer = ByteArray(32)
        trailer[6] = offsetSize.toByte()
        // refSize: a 1-byte reference addresses all seven nodes.
        trailer[7] = REF_SIZE.toByte()
        writeBigEndian(trailer, 8, offsets.size.toLong(), 8)
        writeBigEndian(trailer, 16, 0L, 8) // topObject: the root map is node 0
        writeBigEndian(trailer, 24, offsetTableOffset.toLong(), 8)
        parts.add(trailer)
        return concatBytes(*parts.toTypedArray())
    }

    // Precomputed constant bodies. The sizes are pinned by BplistHidReportTest against the generic
    // encoder, so a wrong marker byte cannot ship silently.
    private const val ROOT_MAP_BODY = 7 // 0xD3 + three 1-byte refs
    private const val UUID_KEY_BODY = 5 // 0x54 "uuid"
    private const val TYPE_KEY_BODY = 5 // 0x54 "type"
    private const val TYPE_VALUE_BODY = 14 // 0x5D "hidSendReport"
    private const val HID_REPORT_KEY_BODY = 10 // 0x59 "hidReport"
    private const val REF_SIZE = 1
    private const val BPLIST_TRAILER_BYTES = 32

    private val ROOT_MAP_BODY_BYTES = byteArrayOf(0xd3.toByte(), 1.toByte(), 2.toByte(), 3.toByte(), 4.toByte(), 5.toByte(), 6.toByte())
    private val UUID_KEY_BODY_BYTES = byteArrayOf(0x54) + "uuid".toByteArray(Charsets.US_ASCII)
    private val TYPE_KEY_BODY_BYTES = byteArrayOf(0x54) + "type".toByteArray(Charsets.US_ASCII)
    private val TYPE_VALUE_BODY_BYTES =
        byteArrayOf(0x5d) + "hidSendReport".toByteArray(Charsets.US_ASCII)
    private val HID_REPORT_KEY_BODY_BYTES =
        byteArrayOf(0x59) + "hidReport".toByteArray(Charsets.US_ASCII)

    private fun readObject(bytes: ByteArray, offsets: LongArray, refSize: Int, index: Int): Any? {
        var position = offsets[index].toInt()
        val markerByte = bytes[position].toInt() and 0xff
        val type = markerByte ushr 4
        val nibble = markerByte and 0x0f
        position++

        fun readCount(): Int {
            if (nibble != 0x0f) return nibble
            val sizeMarker = bytes[position].toInt() and 0xff
            position++
            val intBytes = 1 shl (sizeMarker and 0x0f)
            val count = readBigEndianLong(bytes, position.toLong(), intBytes).toInt()
            position += intBytes
            return count
        }

        return when (type) {
            0x0 -> when (nibble) {
                0x00 -> null
                0x08 -> false
                0x09 -> true
                else -> throw IllegalArgumentException("bplist: unsupported primitive 0x0$nibble")
            }
            0x1 -> {
                val size = 1 shl nibble
                readBigEndianLong(bytes, position.toLong(), size)
            }
            0x2 -> {
                val size = 1 shl nibble
                when (size) {
                    4 -> Float.fromBits(readBigEndianLong(bytes, position.toLong(), size).toInt())
                    8 -> Double.fromBits(readBigEndianLong(bytes, position.toLong(), size))
                    else -> throw IllegalArgumentException("bplist: unsupported real size $size")
                }
            }
            // Date: 8-byte real, seconds since 2001-01-01 UTC.
            0x3 -> java.util.Date(
                Math.round((Double.fromBits(readBigEndianLong(bytes, position.toLong(), 8)) + APPLE_EPOCH_SECONDS) * 1000),
            )
            0x4 -> {
                val count = readCount()
                bytes.copyOfRange(position, position + count)
            }
            // UID (keyed archives): its integer value.
            0x8 -> readBigEndianLong(bytes, position.toLong(), nibble + 1)
            0x5 -> {
                val count = readCount()
                String(bytes, position, count, Charsets.US_ASCII)
            }
            0x6 -> {
                val count = readCount()
                String(bytes, position, count * 2, Charsets.UTF_16BE)
            }
            0xa, 0xc -> {
                val count = readCount()
                val array = ArrayList<Any?>(count)
                for (i in 0 until count) {
                    val reference = readBigEndianLong(bytes, position + i.toLong() * refSize, refSize).toInt()
                    array.add(readObject(bytes, offsets, refSize, reference))
                }
                array
            }
            0xd -> {
                val count = readCount()
                val dict = LinkedHashMap<String, Any?>(count)
                for (i in 0 until count) {
                    val keyReference = readBigEndianLong(bytes, position + i.toLong() * refSize, refSize).toInt()
                    val valueReference =
                        readBigEndianLong(bytes, position + (count + i).toLong() * refSize, refSize).toInt()
                    dict[readObject(bytes, offsets, refSize, keyReference).toString()] =
                        readObject(bytes, offsets, refSize, valueReference)
                }
                dict
            }
            else -> throw IllegalArgumentException("bplist: unsupported object type 0x${type.toString(16)}")
        }
    }

    private fun encodeString(value: String): ByteArray {
        val ascii = value.all { it.code <= 0x7f }
        return if (ascii) {
            marker(0x5, value.length) + value.toByteArray(Charsets.US_ASCII)
        } else {
            marker(0x6, value.length) + value.toByteArray(Charsets.UTF_16BE)
        }
    }

    private fun encodeInt(number: Long): ByteArray {
        val size = when {
            number > 0xffffffffL -> 8
            number > 0xffffL -> 4
            number > 0xffL -> 2
            else -> 1
        }
        val log = when (size) {
            8 -> 3
            4 -> 2
            2 -> 1
            else -> 0
        }
        return byteArrayOf((0x10 or log).toByte()) + bigEndian(number, size)
    }

    private fun encodeUnsignedInt(number: java.math.BigInteger): ByteArray {
        val size = when {
            number.bitLength() > 32 -> 8
            number.bitLength() > 16 -> 4
            number.bitLength() > 8 -> 2
            else -> 1
        }
        val log = when (size) {
            8 -> 3
            4 -> 2
            2 -> 1
            else -> 0
        }
        val raw = number.toByteArray()
        return byteArrayOf((0x10 or log).toByte()) + raw.copyOfRange(raw.size - size, raw.size)
    }

    private fun marker(type: Int, count: Int): ByteArray {
        if (count < 0x0f) return byteArrayOf(((type shl 4) or count).toByte())
        val size = when {
            count > 0xffff -> 4
            count > 0xff -> 2
            else -> 1
        }
        val log = when (size) {
            4 -> 2
            2 -> 1
            else -> 0
        }
        return byteArrayOf(((type shl 4) or 0x0f).toByte(), (0x10 or log).toByte()) +
            bigEndian(count.toLong(), size)
    }

    private fun refBytes(refs: IntArray, refSize: Int): ByteArray {
        val output = ByteArray(refs.size * refSize)
        for (index in refs.indices) writeBigEndian(output, index * refSize, refs[index].toLong(), refSize)
        return output
    }

    private fun bigEndian(value: Long, size: Int): ByteArray {
        val output = ByteArray(size)
        writeBigEndian(output, 0, value, size)
        return output
    }

    private fun writeBigEndian(target: ByteArray, offset: Int, value: Long, size: Int) {
        var current = value
        for (index in size - 1 downTo 0) {
            target[offset + index] = (current and 0xff).toByte()
            current = current ushr 8
        }
    }

    private fun readBigEndianLong(bytes: ByteArray, offset: Long, size: Int): Long {
        var value = 0L
        var base = offset.toInt()
        for (index in 0 until size) {
            value = (value shl 8) or (bytes[base + index].toLong() and 0xffL)
        }
        return value
    }

    private sealed class Node
    private class Leaf(val body: ByteArray) : Node()
    private class Container(val head: ByteArray, val refs: IntArray) : Node()

    private const val APPLE_EPOCH_SECONDS = 978_307_200.0
}
