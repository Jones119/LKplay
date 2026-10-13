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
    fun nativeStart(hostMac: ByteArray?, mtu: Int): Int
    fun nativeInjectFrame(frame: ByteArray, length: Int): Int
    /** Returns the length of the next outbound frame copied into [out], or 0 when the ring is empty. */
    fun nativePollTxFrame(out: ByteArray): Int
    fun nativeTxPending(): Int
    fun nativeStats(): IntArray

    fun nativeSocket(type: Int): Int
    fun nativeBind(fd: Int, address: ByteArray, port: Int): Int
    fun nativeListen(fd: Int, backlog: Int): Int
    fun nativeAccept(fd: Int): Int
    fun nativeRecv(fd: Int, buffer: ByteArray, offset: Int, length: Int): Int
    fun nativeSend(fd: Int, buffer: ByteArray, offset: Int, length: Int): Int
    fun nativeSendTo(fd: Int, buffer: ByteArray, offset: Int, length: Int, address: ByteArray, port: Int): Int
    fun nativeRecvFrom(fd: Int, buffer: ByteArray, offset: Int, length: Int, fromAddress: ByteArray, fromPort: IntArray): Int
    fun nativeClose(fd: Int): Int
    fun nativeSetSockOptInt(fd: Int, level: Int, option: Int, value: Int): Int
    fun nativeSetSockOptLinger(fd: Int, on: Boolean, seconds: Int): Int
    fun nativeJoinGroupV6(fd: Int, group: ByteArray, interfaceIndex: Int): Int
    fun nativeLocalName(fd: Int, address: ByteArray, port: IntArray): Int
    fun nativePeerName(fd: Int, address: ByteArray, port: IntArray): Int
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

    external override fun nativeStart(hostMac: ByteArray?, mtu: Int): Int
    external override fun nativeInjectFrame(frame: ByteArray, length: Int): Int
    external override fun nativePollTxFrame(out: ByteArray): Int
    external override fun nativeTxPending(): Int
    external override fun nativeStats(): IntArray
    external override fun nativeSocket(type: Int): Int
    external override fun nativeBind(fd: Int, address: ByteArray, port: Int): Int
    external override fun nativeListen(fd: Int, backlog: Int): Int
    external override fun nativeAccept(fd: Int): Int
    external override fun nativeRecv(fd: Int, buffer: ByteArray, offset: Int, length: Int): Int
    external override fun nativeSend(fd: Int, buffer: ByteArray, offset: Int, length: Int): Int
    external override fun nativeSendTo(
        fd: Int,
        buffer: ByteArray,
        offset: Int,
        length: Int,
        address: ByteArray,
        port: Int,
    ): Int
    external override fun nativeRecvFrom(
        fd: Int,
        buffer: ByteArray,
        offset: Int,
        length: Int,
        fromAddress: ByteArray,
        fromPort: IntArray,
    ): Int
    external override fun nativeClose(fd: Int): Int
    external override fun nativeSetSockOptInt(fd: Int, level: Int, option: Int, value: Int): Int
    external override fun nativeSetSockOptLinger(fd: Int, on: Boolean, seconds: Int): Int
    external override fun nativeJoinGroupV6(fd: Int, group: ByteArray, interfaceIndex: Int): Int
    external override fun nativeLocalName(fd: Int, address: ByteArray, port: IntArray): Int
    external override fun nativePeerName(fd: Int, address: ByteArray, port: IntArray): Int
}
