/*
 * JNI surface of the userspace NCM bridge (docs/_digest/58 §7.4).
 *
 * The Kotlin side implements DatagramTransport / DuplexServer / DuplexSocket on top of these calls, so
 * this file only has to be a faithful, thin wrapper: no buffering, no retries, no policy. Every call
 * returns a negative errno-style value on failure and lets Kotlin decide what to do (the channels already
 * tolerate exceptions, which is how the kernel implementation behaves too).
 *
 * Addresses cross as 16 raw bytes in network order - the link is IPv6-only - so there is no sockaddr
 * marshalling to get wrong. Byte arrays are copied with GetByteArrayElements rather than
 * GetPrimitiveArrayCritical: a critical section blocks ART's GC thread flip on this SoC, which the USB
 * path already had to learn (see NcmUsbBridge's read buffer comments).
 */
#include <android/log.h>
#include <jni.h>
#include <errno.h>
#include <pthread.h>
#include "lwip/mld6.h"
#include "lwip/netif.h"
#include <string.h>
#include <unistd.h>

/*
 * Deliberately NOT <sys/socket.h>/<netinet/in.h>: the platform headers and lwIP's each define sockaddr,
 * sockaddr_in6, in6_addr, iovec, msghdr, linger and the SO_* constants, and including both is a
 * redefinition war. lwIP's sockets.h has everything this file needs (AF_INET6, struct sockaddr_in6,
 * socklen_t, lwip_htons).
 */
#include "lwip/init.h"
#include "lwip/ip6_addr.h"
#include "lwip/sockets.h"
#include "lwip/tcp.h"

#define CS11_JNI(ret) JNIEXPORT ret JNICALL

int cs11_netif_start(const unsigned char *host_mac, int mtu);
int cs11_netif_input(const unsigned char *frame, unsigned long length);
int cs11_netif_take_tx(unsigned char *out, unsigned long capacity);
int cs11_netif_tx_pending(void);
int cs11_netif_wait_tx(int timeout_ms);
void cs11_netif_stats(unsigned int *out);

static int to_sockaddr6(JNIEnv *env, jbyteArray address, jint port, struct sockaddr_in6 *out) {
    if (address == NULL || (*env)->GetArrayLength(env, address) < 16) {
        return -EINVAL;
    }
    jbyte bytes[16];
    (*env)->GetByteArrayRegion(env, address, 0, 16, bytes);
    if ((*env)->ExceptionCheck(env)) {
        return -EINVAL;
    }
    memset(out, 0, sizeof(*out));
    out->sin6_family = AF_INET6;
    out->sin6_port = lwip_htons((u16_t)port);
    memcpy(&out->sin6_addr, bytes, 16);
    return 0;
}

CS11_JNI(jint) Java_com_shilapi_xcertplay_transport_UserSpaceNetworkNative_nativeStart(
        JNIEnv *env, jobject self, jbyteArray hostMac, jint mtu) {
    (void)self;
    unsigned char mac[6];
    const unsigned char *macPtr = NULL;
    if (hostMac != NULL && (*env)->GetArrayLength(env, hostMac) >= 6) {
        (*env)->GetByteArrayRegion(env, hostMac, 0, 6, (jbyte *)mac);
        macPtr = mac;
    }
    /* The netconn/socket API (lwip_socket, lwip_send, ...) runs on lwIP's tcpip thread and posts every
     * call to tcpip_mbox, so the stack has to be brought up with tcpip_init() rather than lwip_init():
     * lwip_init() alone leaves that mailbox unset, and the first lwip_socket then crashed inside
     * sys_mbox_post - measured on the car as SIGSEGV at fault addr 0x10 on the session's own pool thread.
     * tcpip_init() calls lwip_init() itself. Guarded because a second bring-up would start a second
     * tcpip thread on the same mailbox; the mutex makes that guard hold when two JNI threads race
     * (probe and connect are separate callers). */
    static pthread_mutex_t stack_lock = PTHREAD_MUTEX_INITIALIZER;
    static int stack_started = 0;
    pthread_mutex_lock(&stack_lock);
    if (!stack_started) {
        tcpip_init(NULL, NULL);
        stack_started = 1;
    }
    pthread_mutex_unlock(&stack_lock);
    return cs11_netif_start(macPtr, mtu);
}

/*
 * CS11: bounds-checked array access.
 *
 * None of these entry points used to look at `GetArrayLength`; the whole buffer+offset+length contract was
 * the caller's, and lwIP copied straight out of the Java array. On 2026-10-18 the car crashed inside that
 * copy:
 *
 *   pid: 13159, tid: 13232, name: lwip-tcpip
 *   signal 11 (SIGSEGV), code 2 (SEGV_ACCERR), fault addr 86d92000
 *   #00  pc 00022170  /system/lib/libc.so (memcpy+172)
 *   #01  pc 0001f521  liblwip_bridge.so (tcp_write+716)
 *
 * The source pointer was 0x86d91fe4 and the copy asked for 0x84 bytes, so it ran off the end of the array
 * and over a page that is not mapped. A length that does not fit its array is a caller bug, but it must
 * surface as -EINVAL, never as a read past the buffer.
 */
static jint cs11_check_bounds(JNIEnv *env, jbyteArray array, jint offset, jint length,
                             const char *what) {
    if (array == NULL || offset < 0 || length < 0) {
        __android_log_print(ANDROID_LOG_WARN, "cs11-lwip",
                            "%s rejected offset=%d length=%d (null array or negative)", what, offset, length);
        return -EINVAL;
    }
    jsize size = (*env)->GetArrayLength(env, array);
    if ((jlong)offset + (jlong)length > (jlong)size) {
        __android_log_print(ANDROID_LOG_WARN, "cs11-lwip",
                            "%s rejected offset=%d length=%d size=%d", what, offset, length, size);
        return -EINVAL;
    }
    return 0;
}

CS11_JNI(jint) Java_com_shilapi_xcertplay_transport_UserSpaceNetworkNative_nativeInjectFrame(
        JNIEnv *env, jobject self, jbyteArray frame, jint length) {
    (void)self;
    if (length <= 0) {
        return -EINVAL;
    }
    jint bounds = cs11_check_bounds(env, frame, 0, length, "inject");
    if (bounds != 0) {
        return bounds;
    }
    jbyte *bytes = (*env)->GetByteArrayElements(env, frame, NULL);
    if (bytes == NULL) {
        return -ENOMEM;
    }
    int result = cs11_netif_input((const unsigned char *)bytes, (unsigned long)length);
    (*env)->ReleaseByteArrayElements(env, frame, bytes, JNI_ABORT);
    return result;
}

CS11_JNI(jint) Java_com_shilapi_xcertplay_transport_UserSpaceNetworkNative_nativePollTxFrame(
        JNIEnv *env, jobject self, jbyteArray out) {
    (void)self;
    if (out == NULL) {
        return -EINVAL;
    }
    jsize capacity = (*env)->GetArrayLength(env, out);
    jbyte *bytes = (*env)->GetByteArrayElements(env, out, NULL);
    if (bytes == NULL) {
        return -ENOMEM;
    }
    int length = cs11_netif_take_tx((unsigned char *)bytes, (unsigned long)capacity);
    (*env)->ReleaseByteArrayElements(env, out, bytes, length > 0 ? 0 : JNI_ABORT);
    return length;
}

/* Blocks the TX pump until linkoutput queues a frame (or the timeout passes); see cs11_netif_wait_tx. */
CS11_JNI(jint) Java_com_shilapi_xcertplay_transport_UserSpaceNetworkNative_nativeWaitTx(
        JNIEnv *env, jobject self, jint timeoutMillis) {
    (void)env;
    (void)self;
    return cs11_netif_wait_tx((int)timeoutMillis);
}

CS11_JNI(jint) Java_com_shilapi_xcertplay_transport_UserSpaceNetworkNative_nativeTxPending(
        JNIEnv *env, jobject self) {
    (void)env;
    (void)self;
    return cs11_netif_tx_pending();
}

CS11_JNI(jintArray) Java_com_shilapi_xcertplay_transport_UserSpaceNetworkNative_nativeStats(
        JNIEnv *env, jobject self) {
    (void)self;
    /* Ten slots: the five link counters plus the IPv6 protocol histogram and the port the phone last dialled
     * (cs11_netif_stats fills exactly these). This array and the C side have to agree - writing past five
     * here is what killed the process once already. */
    unsigned int counters[11] = {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0};
    cs11_netif_stats(counters);
    jint values[11];
    for (int i = 0; i < 11; i++) {
        values[i] = (jint)counters[i];
    }
    jintArray result = (*env)->NewIntArray(env, 11);
    if (result != NULL) {
        (*env)->SetIntArrayRegion(env, result, 0, 11, values);
    }
    return result;
}

CS11_JNI(jint) Java_com_shilapi_xcertplay_transport_UserSpaceNetworkNative_nativeSocket(
        JNIEnv *env, jobject self, jint type) {
    (void)env;
    (void)self;
    int fd = lwip_socket(AF_INET6, type, 0);
    return fd < 0 ? -errno : fd;
}

CS11_JNI(jint) Java_com_shilapi_xcertplay_transport_UserSpaceNetworkNative_nativeBind(
        JNIEnv *env, jobject self, jint fd, jbyteArray address, jint port) {
    (void)self;
    struct sockaddr_in6 addr;
    if (to_sockaddr6(env, address, port, &addr) != 0) {
        return -EINVAL;
    }
    return lwip_bind(fd, (struct sockaddr *)&addr, sizeof(addr)) == 0 ? 0 : -errno;
}

CS11_JNI(jint) Java_com_shilapi_xcertplay_transport_UserSpaceNetworkNative_nativeListen(
        JNIEnv *env, jobject self, jint fd, jint backlog) {
    (void)env;
    (void)self;
    return lwip_listen(fd, backlog) == 0 ? 0 : -errno;
}

CS11_JNI(jint) Java_com_shilapi_xcertplay_transport_UserSpaceNetworkNative_nativeAccept(
        JNIEnv *env, jobject self, jint fd) {
    (void)env;
    (void)self;
    struct sockaddr_in6 peer;
    socklen_t length = sizeof(peer);
    int accepted = lwip_accept(fd, (struct sockaddr *)&peer, &length);
    return accepted < 0 ? -errno : accepted;
}

CS11_JNI(jint) Java_com_shilapi_xcertplay_transport_UserSpaceNetworkNative_nativeRecv(
        JNIEnv *env, jobject self, jint fd, jbyteArray buffer, jint offset, jint length) {
    (void)self;
    if (length <= 0) {
        return -EINVAL;
    }
    jint bounds = cs11_check_bounds(env, buffer, offset, length, "recv");
    if (bounds != 0) {
        return bounds;
    }
    jbyte *bytes = (*env)->GetByteArrayElements(env, buffer, NULL);
    if (bytes == NULL) {
        return -ENOMEM;
    }
    int received = lwip_recv(fd, bytes + offset, (size_t)length, 0);
    (*env)->ReleaseByteArrayElements(env, buffer, bytes, received > 0 ? 0 : JNI_ABORT);
    return received < 0 ? -errno : received;
}

CS11_JNI(jint) Java_com_shilapi_xcertplay_transport_UserSpaceNetworkNative_nativeSend(
        JNIEnv *env, jobject self, jint fd, jbyteArray buffer, jint offset, jint length) {
    (void)self;
    jint bounds = cs11_check_bounds(env, buffer, offset, length, "send");
    if (bounds != 0) {
        return bounds;
    }
    jbyte *bytes = (*env)->GetByteArrayElements(env, buffer, NULL);
    if (bytes == NULL) {
        return -ENOMEM;
    }
    int sent = lwip_send(fd, bytes + offset, (size_t)length, 0);
    (*env)->ReleaseByteArrayElements(env, buffer, bytes, JNI_ABORT);
    return sent < 0 ? -errno : sent;
}

CS11_JNI(jint) Java_com_shilapi_xcertplay_transport_UserSpaceNetworkNative_nativeSendTo(
        JNIEnv *env, jobject self, jint fd, jbyteArray buffer, jint offset, jint length,
        jbyteArray address, jint port) {
    (void)self;
    jint bounds = cs11_check_bounds(env, buffer, offset, length, "sendto");
    if (bounds != 0) {
        return bounds;
    }
    struct sockaddr_in6 addr;
    if (to_sockaddr6(env, address, port, &addr) != 0) {
        return -EINVAL;
    }
    jbyte *bytes = (*env)->GetByteArrayElements(env, buffer, NULL);
    if (bytes == NULL) {
        return -ENOMEM;
    }
    int sent = lwip_sendto(fd, bytes + offset, (size_t)length, 0, (struct sockaddr *)&addr, sizeof(addr));
    (*env)->ReleaseByteArrayElements(env, buffer, bytes, JNI_ABORT);
    return sent < 0 ? -errno : sent;
}

CS11_JNI(jint) Java_com_shilapi_xcertplay_transport_UserSpaceNetworkNative_nativeRecvFrom(
        JNIEnv *env, jobject self, jint fd, jbyteArray buffer, jint offset, jint length,
        jbyteArray fromAddress, jintArray fromPort) {
    (void)self;
    /* lwIP writes into this array, so an offset/length that does not fit is an out-of-bounds WRITE -
     * worse than the read that started this whole audit. The commit that added the other checks
     * claimed recvfrom was covered; it was not (found by the 2026-10-19 re-audit). */
    jint bounds = cs11_check_bounds(env, buffer, offset, length, "recvfrom");
    if (bounds != 0) {
        return bounds;
    }
    struct sockaddr_in6 peer;
    socklen_t peerLength = sizeof(peer);
    memset(&peer, 0, sizeof(peer));
    jbyte *bytes = (*env)->GetByteArrayElements(env, buffer, NULL);
    if (bytes == NULL) {
        return -ENOMEM;
    }
    int received = lwip_recvfrom(
        fd, bytes + offset, (size_t)length, 0, (struct sockaddr *)&peer, &peerLength);
    (*env)->ReleaseByteArrayElements(env, buffer, bytes, received > 0 ? 0 : JNI_ABORT);
    if (received < 0) {
        return -errno;
    }
    if (fromAddress != NULL && (*env)->GetArrayLength(env, fromAddress) >= 16) {
        (*env)->SetByteArrayRegion(env, fromAddress, 0, 16, (const jbyte *)&peer.sin6_addr);
    }
    if (fromPort != NULL && (*env)->GetArrayLength(env, fromPort) >= 1) {
        jint value = (jint)lwip_ntohs(peer.sin6_port);
        (*env)->SetIntArrayRegion(env, fromPort, 0, 1, &value);
    }
    return received;
}

CS11_JNI(jint) Java_com_shilapi_xcertplay_transport_UserSpaceNetworkNative_nativeClose(
        JNIEnv *env, jobject self, jint fd) {
    (void)env;
    (void)self;
    return lwip_close(fd) == 0 ? 0 : -errno;
}

CS11_JNI(jint) Java_com_shilapi_xcertplay_transport_UserSpaceNetworkNative_nativeSetSockOptInt(
        JNIEnv *env, jobject self, jint fd, jint level, jint option, jint value) {
    (void)env;
    (void)self;
    return lwip_setsockopt(fd, level, option, &value, sizeof(value)) == 0 ? 0 : -errno;
}

CS11_JNI(jint) Java_com_shilapi_xcertplay_transport_UserSpaceNetworkNative_nativeSetSockOptLinger(
        JNIEnv *env, jobject self, jint fd, jboolean on, jint seconds) {
    (void)env;
    (void)self;
    struct linger value;
    value.l_onoff = on ? 1 : 0;
    value.l_linger = (int)seconds;
    return lwip_setsockopt(fd, SOL_SOCKET, SO_LINGER, &value, sizeof(value)) == 0 ? 0 : -errno;
}

static jint bound_name(JNIEnv *env, jint fd, jboolean peer, jbyteArray address, jintArray port) {
    struct sockaddr_in6 addr;
    socklen_t length = sizeof(addr);
    memset(&addr, 0, sizeof(addr));
    int result = peer ? lwip_getpeername(fd, (struct sockaddr *)&addr, &length)
                      : lwip_getsockname(fd, (struct sockaddr *)&addr, &length);
    if (result != 0) {
        return -errno;
    }
    if (address != NULL && (*env)->GetArrayLength(env, address) >= 16) {
        (*env)->SetByteArrayRegion(env, address, 0, 16, (const jbyte *)&addr.sin6_addr);
    }
    if (port != NULL && (*env)->GetArrayLength(env, port) >= 1) {
        jint value = (jint)lwip_ntohs(addr.sin6_port);
        (*env)->SetIntArrayRegion(env, port, 0, 1, &value);
    }
    return 0;
}

CS11_JNI(jint) Java_com_shilapi_xcertplay_transport_UserSpaceNetworkNative_nativeLocalName(
        JNIEnv *env, jobject self, jint fd, jbyteArray address, jintArray port) {
    (void)self;
    return bound_name(env, fd, JNI_FALSE, address, port);
}

CS11_JNI(jint) Java_com_shilapi_xcertplay_transport_UserSpaceNetworkNative_nativePeerName(
        JNIEnv *env, jobject self, jint fd, jbyteArray address, jintArray port) {
    (void)self;
    return bound_name(env, fd, JNI_TRUE, address, port);
}

/* Joins an IPv6 multicast group on one of our lwIP sockets - what answering the iPhone's mDNS queries needs
 * (the announcement itself needs no membership). lwIP handles this option only with LWIP_IPV6_MLD on, which
 * 50b1e132 enabled; the interface index selects our netif, since a link-local group is meaningless without
 * one. Note the constants are lwIP's, not the platform's (see 1fba7a21 for the same trap). */
CS11_JNI(jint) Java_com_shilapi_xcertplay_transport_UserSpaceNetworkNative_nativeJoinGroupV6(
        JNIEnv *env, jobject self, jint fd, jbyteArray group, jint interface_index) {
    (void) self;
    if (fd < 0 || group == NULL || (*env)->GetArrayLength(env, group) != 16) return -EINVAL;
    struct ipv6_mreq request;
    memset(&request, 0, sizeof(request));
    (*env)->GetByteArrayRegion(env, group, 0, 16, (jbyte *) &request.ipv6mr_multiaddr);
    request.ipv6mr_interface = (unsigned int) interface_index;
    (void) fd;
    /* Straight to lwIP's own group join with the netif this port owns. The socket option resolves the
     * interface through an index and answered EADDRNOTAVAIL for every value the Java side could supply
     * (errno 99 on the car), while this call takes the netif itself and updates the same group list the
     * UDP input path checks membership against. */
    extern struct netif *cs11_netif_handle(void);
    const err_t joined = mld6_joingroup_netif(cs11_netif_handle(), ip_2_ip6((ip_addr_t *) &request.ipv6mr_multiaddr));
    if (joined == ERR_OK) return 0;
    return (jint) -joined;
}