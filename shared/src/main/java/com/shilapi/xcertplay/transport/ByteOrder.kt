package com.shilapi.xcertplay.transport

/**
 * Big- and little-endian reads and writes, in one place.
 *
 * These exist because the same six helpers had been re-declared privately in **thirteen** files, and the
 * names lied about half of them: `readU16` meant big-endian in `EthernetIpv6Codec` / `Iap2UsbMuxHost` /
 * `UsbMuxFrameBuffer` and little-endian in `NcmNegotiation` / `NcmUsbBridge` / `Ntb16Codec`. `readU32` had a
 * third meaning on top of that - `LockdownPlistChannel` returned a `Long` on purpose, to survive a length
 * field with the top bit set. None of that was visible at a call site, so copying a helper from an NCM file
 * into a USB file produced a silent byte-order bug with no compiler and no test pointing at it.
 *
 * Nothing here is on a per-frame hot path that the JIT cannot inline: every function is a leaf with constant
 * offsets, and the callers used to hold the same three lines inline. The trade is one megamorphic-free static
 * call for naming that cannot be misread.
 *
 * [readU32BeLong] is the 64-bit variant and is deliberately separate rather than a widening of
 * [readU32Be]: a caller that needs it is reading a length that may set bit 31, and forcing every other caller
 * through a `Long` would mean a narrowing cast at each of them.
 */
internal fun readU16Be(source: ByteArray, offset: Int): Int =
    ((source[offset].toInt() and 0xff) shl 8) or (source[offset + 1].toInt() and 0xff)

internal fun readU16Le(source: ByteArray, offset: Int): Int =
    (source[offset].toInt() and 0xff) or ((source[offset + 1].toInt() and 0xff) shl 8)

internal fun readU32Be(source: ByteArray, offset: Int): Int =
    ((source[offset].toInt() and 0xff) shl 24) or
        ((source[offset + 1].toInt() and 0xff) shl 16) or
        ((source[offset + 2].toInt() and 0xff) shl 8) or
        (source[offset + 3].toInt() and 0xff)

internal fun readU32Le(source: ByteArray, offset: Int): Int =
    (source[offset].toInt() and 0xff) or
        ((source[offset + 1].toInt() and 0xff) shl 8) or
        ((source[offset + 2].toInt() and 0xff) shl 16) or
        ((source[offset + 3].toInt() and 0xff) shl 24)

/** Reads a 32-bit big-endian field without the sign: for lengths that may exceed [Int.MAX_VALUE]. */
internal fun readU32BeLong(source: ByteArray, offset: Int): Long =
    ((source[offset].toLong() and 0xff) shl 24) or
        ((source[offset + 1].toLong() and 0xff) shl 16) or
        ((source[offset + 2].toLong() and 0xff) shl 8) or
        (source[offset + 3].toLong() and 0xff)

/** The little-endian form of [readU32BeLong]; NCM length fields use it. */
internal fun readU32LeLong(source: ByteArray, offset: Int): Long =
    (source[offset].toLong() and 0xff) or
    ((source[offset + 1].toLong() and 0xff) shl 8) or
    ((source[offset + 2].toLong() and 0xff) shl 16) or
    ((source[offset + 3].toLong() and 0xff) shl 24)

internal fun writeU16Be(target: ByteArray, offset: Int, value: Int) {
    target[offset] = (value ushr 8).toByte()
    target[offset + 1] = value.toByte()
    }

internal fun writeU16Le(target: ByteArray, offset: Int, value: Int) {
    target[offset] = value.toByte()
    target[offset + 1] = (value ushr 8).toByte()
    }

internal fun writeU32Be(target: ByteArray, offset: Int, value: Int) {
    target[offset] = (value ushr 24).toByte()
    target[offset + 1] = (value ushr 16).toByte()
    target[offset + 2] = (value ushr 8).toByte()
    target[offset + 3] = value.toByte()
    }

internal fun writeU32Be(target: ByteArray, offset: Int, value: Long) {
    target[offset] = (value ushr 24).toByte()
    target[offset + 1] = (value ushr 16).toByte()
    target[offset + 2] = (value ushr 8).toByte()
    target[offset + 3] = value.toByte()
    }

internal fun writeU32Le(target: ByteArray, offset: Int, value: Int) {
    target[offset] = value.toByte()
    target[offset + 1] = (value ushr 8).toByte()
    target[offset + 2] = (value ushr 16).toByte()
    target[offset + 3] = (value ushr 24).toByte()
    }
