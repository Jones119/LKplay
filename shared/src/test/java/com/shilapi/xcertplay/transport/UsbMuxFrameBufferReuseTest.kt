package com.shilapi.xcertplay.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The USBMUX framer used to allocate a fresh copy of its whole backlog on every USB read and again for
 * every frame it returned (docs/_digest/51 §12). These tests pin the reuse: consumed bytes are
 * reclaimed in place, the buffer only grows when a frame genuinely needs it, and splitting a frame
 * across any number of reads still yields exactly that frame.
 *
 * The framing semantics themselves (padding recovery and its rejections) are covered by
 * `UsbMuxFrameBufferTest`; this file only pins allocation behaviour that must not change them.
 */
class UsbMuxFrameBufferReuseTest {
    @Test
    fun repeatedFramesAreFramedFromOneBufferWithoutGrowing() {
        val buffer = UsbMuxFrameBuffer()
        val initialCapacity = buffer.capacityBytes
        val payload = ByteArray(1200) { (it % 251).toByte() }
        repeat(200) { round ->
            buffer.append(mux(protocol = 7, payload = payload, sequence = round))
            val frame = buffer.takeFrame()
            assertNotNull("round=$round", frame)
            assertEquals(1200, frame!!.payload.size)
            assertEquals(round, frame.sequence)
            assertNull("only one frame per append", buffer.takeFrame())
            assertEquals("all bytes consumed", 0, buffer.bufferedBytes)
        }
        assertEquals("capacity must not grow when frames are consumed", initialCapacity, buffer.capacityBytes)
    }

    @Test
    fun byteAtATimeReadsStillProduceExactlyOneFrameAndLeaveNoBacklog() {
        val buffer = UsbMuxFrameBuffer()
        val payload = ByteArray(64) { it.toByte() }
        val wire = mux(protocol = 7, payload = payload, sequence = 9)
        val frames = mutableListOf<UsbMuxFrame>()
        for (byte in wire) {
            buffer.append(byteArrayOf(byte))
            buffer.takeFrame()?.let { frames.add(it) }
        }
        assertEquals(1, frames.size)
        assertEquals(9, frames.single().sequence)
        assertEquals(payload.toList(), frames.single().payload.toList())
        assertEquals(0, buffer.bufferedBytes)
    }

    @Test
    fun aMaximumSizeFrameFitsTheInitialBufferWithoutGrowing() {
        val buffer = UsbMuxFrameBuffer()
        val initialCapacity = buffer.capacityBytes
        // MAX_FRAME_BYTES is the total frame length including the 16-byte MUX header.
        val payload = ByteArray(MAX_FRAME_BYTES - 16) { (it and 0xff).toByte() }
        val wire = mux(protocol = 7, payload = payload)
        assertEquals(MAX_FRAME_BYTES, wire.size)
        // Feed it in 16 KiB reads, the largest USB request this ROM allows (docs/_digest/44 §1.7).
        var offset = 0
        var frame: UsbMuxFrame? = null
        while (offset < wire.size) {
            val count = minOf(16 * 1024, wire.size - offset)
            buffer.append(wire.copyOfRange(offset, offset + count))
            offset += count
            if (frame == null) frame = buffer.takeFrame()
        }
        assertNotNull(frame)
        assertEquals(payload.size, frame!!.payload.size)
        assertEquals(payload.toList(), frame.payload.toList())
        assertEquals(0, buffer.bufferedBytes)
        assertEquals("a maximum frame must fit the initial capacity", initialCapacity, buffer.capacityBytes)
    }

    @Test
    fun twoMaximumFramesInOneReadGrowTheBufferExactlyOnce() {
        val buffer = UsbMuxFrameBuffer()
        val initialCapacity = buffer.capacityBytes
        val payload = ByteArray(MAX_FRAME_BYTES - 16) { (it and 0xff).toByte() }
        buffer.append(mux(7, payload, 1) + mux(7, payload, 2))
        val frames = generateSequence { buffer.takeFrame() }.toList()
        assertEquals(2, frames.size)
        assertEquals(listOf(1, 2), frames.map { it.sequence })
        assertEquals(payload.size, frames[0].payload.size)
        assertEquals(payload.toList(), frames[1].payload.toList())
        assertEquals(0, buffer.bufferedBytes)
        assertEquals("both frames fit, so one doubling is enough", initialCapacity * 2, buffer.capacityBytes)
    }

    @Test
    fun severalFramesInOneReadAreAllFramedAndTheBufferEmpties() {
        val buffer = UsbMuxFrameBuffer()
        val initialCapacity = buffer.capacityBytes
        val coalesced = mux(7, ByteArray(300), 1) + mux(7, ByteArray(700), 2) + mux(7, ByteArray(100), 3)
        buffer.append(coalesced)
        val frames = generateSequence { buffer.takeFrame() }.toList()
        assertEquals(3, frames.size)
        assertEquals(listOf(1, 2, 3), frames.map { it.sequence })
        assertEquals(listOf(300, 700, 100), frames.map { it.payload.size })
        assertEquals(0, buffer.bufferedBytes)
        assertEquals(initialCapacity, buffer.capacityBytes)
    }

    private fun mux(protocol: Int, payload: ByteArray, sequence: Int = 0): ByteArray =
        ByteArray(16 + payload.size).also { frame ->
            putU32(frame, 0, protocol)
            putU32(frame, 4, frame.size)
            putU32(frame, 8, 0xfaceface.toInt())
            frame[12] = ((sequence ushr 8) and 0xff).toByte()
            frame[13] = (sequence and 0xff).toByte()
            payload.copyInto(frame, 16)
        }

    private fun putU32(bytes: ByteArray, offset: Int, value: Int) {
        for (index in 0..3) bytes[offset + index] = (value ushr (24 - index * 8)).toByte()
    }

    private companion object {
        /** Mirrors UsbMuxFrameBuffer's own ceiling: the declared length includes the 16-byte header. */
        const val MAX_FRAME_BYTES = 65_536
    }
}
