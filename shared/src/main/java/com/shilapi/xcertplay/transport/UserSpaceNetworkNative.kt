package com.shilapi.xcertplay.transport

import android.util.Log

/**
 * The JNI half of the userspace NCM bridge - one call per symbol in bridge/cs11_lwip_jni.c.
 *
 * It is an interface so [UserSpaceSessionNetwork] can be driven by a fake in JVM tests: the library only
 * loads on the car, and the code above it (address marshalling, socket options, error mapping) is worth
 * testing without one. Every call returns a negative errno on failure; nothing throws across JNI.
 *
 * Addresses are always 16 raw bytes of IPv6 in network order, because the NCM link is IPv6-only - there
 * is no sockaddr marshalling anywhere in this path (docs/_digest/58 §7.4).
 */
internal interface UserSpaceNetworkJni {
    fun start(hostMac: ByteArray?, mtu: Int): Int
    fun injectFrame(frame: ByteArray, length: Int): Int
    /** Returns the length of the next outbound frame copied into [out], or 0 when the ring is empty. */
    fun pollTxFrame(out: ByteArray): Int
    fun txPending(): Int
    fun stats(): IntArray

    fun socket(type: Int): Int
    fun bind(fd: Int, address: ByteArray, port: Int): Int
    fun listen(fd: Int, backlog: Int): Int
    fun accept(fd: Int): Int
    fun recv(fd: Int, buffer: ByteArray, offset: Int, length: Int): Int
    fun send(fd: Int, buffer: ByteArray, offset: Int, length: Int): Int
    fun sendTo(fd: Int, buffer: ByteArray, offset: Int, length: Int, address: ByteArray, port: Int): Int
    fun recvFrom(fd: Int, buffer: ByteArray, offset: Int, length: Int, fromAddress: ByteArray, fromPort: IntArray): Int
    fun close(fd: Int): Int
    fun setSockOptInt(fd: Int, level: Int, option: Int, value: Int): Int
    fun setSockOptLinger(fd: Int, on: Boolean, seconds: Int): Int
    fun localName(fd: Int, address: ByteArray, port: IntArray): Int
    fun peerName(fd: Int, address: ByteArray, port: IntArray): Int
}

/**
 * The shipped implementation: `liblwip_bridge.so`, built by shared/src/main/cpp/CMakeLists.txt.
 *
 * [available] is false on a build without the library (or on an ABI it was not built for), which is what
 * `UserspaceNcmTransport.readiness` reads before letting the wired path prefer this transport.
 */
internal object UserSpaceNetworkNative : UserSpaceNetworkJni {
    private const val TAG = "xcertplay-usb"
    private const val LIBRARY = "lwip_bridge"
    private val loaded: Boolean = runCatching { System.loadLibrary(LIBRARY) }.isSuccess

    val available: Boolean get() = loaded

    init {
        if (!loaded) {
            Log.i(TAG, "userspace NCM bridge: $LIBRARY could not be loaded; the kernel netdev stays in use")
        }
    }

    external override fun start(hostMac: ByteArray?, mtu: Int): Int
    external override fun injectFrame(frame: ByteArray, length: Int): Int
    external override fun pollTxFrame(out: ByteArray): Int
    external override fun txPending(): Int
    external override fun stats(): IntArray
    external override fun socket(type: Int): Int
    external override fun bind(fd: Int, address: ByteArray, port: Int): Int
    external override fun listen(fd: Int, backlog: Int): Int
    external override fun accept(fd: Int): Int
    external override fun recv(fd: Int, buffer: ByteArray, offset: Int, length: Int): Int
    external override fun send(fd: Int, buffer: ByteArray, offset: Int, length: Int): Int
    external override fun sendTo(
        fd: Int,
        buffer: ByteArray,
        offset: Int,
        length: Int,
        address: ByteArray,
        port: Int,
    ): Int
    external override fun recvFrom(
        fd: Int,
        buffer: ByteArray,
        offset: Int,
        length: Int,
        fromAddress: ByteArray,
        fromPort: IntArray,
    ): Int
    external override fun close(fd: Int): Int
    external override fun setSockOptInt(fd: Int, level: Int, option: Int, value: Int): Int
    external override fun setSockOptLinger(fd: Int, on: Boolean, seconds: Int): Int
    external override fun localName(fd: Int, address: ByteArray, port: IntArray): Int
    external override fun peerName(fd: Int, address: ByteArray, port: IntArray): Int
}
