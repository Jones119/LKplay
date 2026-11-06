/*
 * The one lwIP netif that carries CarPlay over the NCM link (docs/_digest/58 §7).
 *
 * Frames in: the USB bridge's reader thread hands a whole Ethernet II frame to cs11_netif_input(), which
 * copies it into a pbuf and passes it to lwIP's tcpip_input() (safe from any thread, because core locking
 * is off). Frames out: lwIP's linkoutput copies the frame into a small ring that the Kotlin side polls with
 * nativePollTxFrame(); polling instead of a JNI upcall keeps the JNI out of lwIP's thread and lets the USB
 * writer own its own pacing, which is how the NTB framer already works.
 *
 * The link-local address is derived from the interface MAC (EUI-64), and the same derivation is what iAP2's
 * CarPlayStartSession advertises: the iPhone only dials the address it was told, and pinning fe80::2 produced
 * zero inbound TCP (docs/_digest/65 §2 item 3). See the comment on the derivation below for the measurement.
 */
#include "lwip/opt.h"
#include "lwip/def.h"
#include "lwip/ip6_addr.h"
#include "lwip/mem.h"
#include "lwip/netif.h"
#include "lwip/pbuf.h"
#include "lwip/tcpip.h"
#include "lwip/ethip6.h"
#if LWIP_IPV6_MLD
#include "lwip/mld6.h"
#endif
#include "netif/ethernet.h"

#include <errno.h>
#include <pthread.h>
#include <string.h>
#include <time.h>

#define CS11_TX_SLOTS 32
#define CS11_TX_SLOT_BYTES 1600

/*
 * Link-up announcement (docs/_digest/100).
 *
 * The iPhone starts its CarPlay discovery as soon as the NCM link reports up: EasyPlay's kernel NCM +
 * VPN stack, on this same phone and cable, receives the phone's mDNS query 6 ms after attaching. Our
 * userspace link used to sit silent for 6-12 s, and the phone sent nothing (not even a Router
 * Solicitation) until its own retry. The one thing the kernel stack does at attach and we did not is
 * speak IPv6 first: an MLD report for the mDNS group and an ICMPv6 Router Solicitation. Both are cheap
 * and harmless if the phone ignores them, so the link now sends them right after netif_set_up.
 */
static u32_t cs11_cksum_add(const void *data, size_t length, u32_t sum) {
    const u8_t *bytes = (const u8_t *)data;
    while (length > 1) {
        sum += ((u32_t)bytes[0] << 8) | bytes[1];
        bytes += 2;
        length -= 2;
    }
    if (length != 0) {
        sum += (u32_t)bytes[0] << 8;
    }
    return sum;
}

/*
 * Built as a complete Ethernet frame and handed straight to linkoutput.
 *
 * ip6_output_if() needs pbuf header room for the IPv6 and link headers and answered ERR_BUF/ERR_RTE in
 * the host tests, so this path does the 40-byte IPv6 header itself: fewer moving parts, and the frame
 * that reaches the phone is exactly the one measured here (62 bytes, the same shape the EasyPlay
 * reference's stack emits, docs/_digest/100).
 */
static void cs11_send_router_solicitation(struct netif *netif) {
    u8_t frame[14 + 40 + 8];
    const ip6_addr_t *source = netif_ip6_addr(netif, 0);
    struct pbuf *packet;
    u32_t sum;
    u16_t checksum;
    err_t err;

    if (source == NULL || ip6_addr_isinvalid(source)) {
        LWIP_PLATFORM_DIAG(("cs11 netif: no link-local, router solicitation skipped"));
        return;
    }
    memset(frame, 0, sizeof(frame));

    /* Ethernet: all-routers multicast 33:33:00:00:00:02, IPv6 ethertype. */
    frame[0] = 0x33;
    frame[1] = 0x33;
    frame[5] = 0x02;
    memcpy(frame + 6, netif->hwaddr, 6);
    frame[12] = 0x86;
    frame[13] = 0xdd;

    /* IPv6 header: version 6, 8-byte payload, ICMPv6, hop limit 255. */
    frame[14] = 0x60;
    frame[18] = 0x00;
    frame[19] = 0x08;
    frame[20] = 58;
    frame[21] = 255;
    memcpy(frame + 22, source->addr, 16);
    frame[38] = 0xff; /* ff02::2 */
    frame[39] = 0x02;
    frame[53] = 0x02;

    /* ICMPv6 Router Solicitation, checksum over the pseudo-header. */
    frame[54] = 133;
    sum = cs11_cksum_add(frame + 22, 32, 0); /* source and destination are contiguous */
    sum += 8;                                /* upper-layer length */
    sum += 58;                               /* next header: ICMPv6 */
    sum = cs11_cksum_add(frame + 54, 8, sum);
    while ((sum >> 16) != 0) {
        sum = (sum & 0xffffU) + (sum >> 16);
    }
    checksum = (u16_t)(~sum & 0xffffU);
    frame[56] = (u8_t)(checksum >> 8);
    frame[57] = (u8_t)(checksum & 0xff);

    packet = pbuf_alloc(PBUF_RAW, sizeof(frame), PBUF_RAM);
    if (packet == NULL) {
        LWIP_PLATFORM_DIAG(("cs11 netif: router solicitation pbuf failed"));
        return;
    }
    memcpy(packet->payload, frame, sizeof(frame));
    err = netif->linkoutput(netif, packet);
    pbuf_free(packet);
    LWIP_PLATFORM_DIAG(("cs11 netif: router solicitation sent err=%d", (int)err));
}

static void cs11_announce_link_up(struct netif *netif) {
#if LWIP_IPV6_MLD
    ip6_addr_t mdns;
    IP6_ADDR(&mdns, 0xff020000U, 0, 0, 0x000000fbU);
    err_t joined = mld6_joingroup_netif(netif, &mdns);
    LWIP_PLATFORM_DIAG(("cs11 netif: mld6 join ff02::fb err=%d", (int)joined));
#endif
#if LWIP_ICMP6
    cs11_send_router_solicitation(netif);
#endif
}

/* Runs on the tcpip thread: mld6_joingroup_netif and ip6_output_if both walk lwIP state. */
static void cs11_announce_link_up_cb(void *context) {
    cs11_announce_link_up((struct netif *)context);
}

struct cs11_netif_state {
    struct netif netif;
    struct {
        pthread_mutex_t mutex;
        /* Signalled by linkoutput whenever a frame is queued, so the Kotlin TX pump can block in
         * cs11_netif_wait_tx() instead of waking every millisecond to ask (doc 102 §4: ncm-us-tx was
         * ~6% of the app's CPU while it mostly found the ring empty). */
        pthread_cond_t ready;
        u16_t length[CS11_TX_SLOTS];
        u8_t frame[CS11_TX_SLOTS][CS11_TX_SLOT_BYTES];
        u16_t head;
        u16_t tail;
        u16_t count;
    } tx;
    u32_t rx_frames;
    u32_t rx_bytes;
    u32_t tx_frames;
    u32_t tx_bytes;
    u32_t tx_dropped;
    /* IPv6 protocol histogram for the diagnostic line. These were declared by the JNI side but never
     * filled, so `ipv6Rx/tcpRx/udpRx/icmp6Rx` were printed as zero and hid the one signal needed to
     * tell "the phone stopped sending" from "lwIP dropped it" (measured on the tablet 2026-10-19). */
    u32_t ipv6_rx;
    u32_t tcp_rx;
    u32_t udp_rx;
    u32_t icmp6_rx;
    u32_t last_tcp_dst;
};

static struct cs11_netif_state g_cs11;

static err_t cs11_netif_linkoutput(struct netif *netif, struct pbuf *p) {
    struct cs11_netif_state *state = (struct cs11_netif_state *)netif->state;
    if (p->tot_len > CS11_TX_SLOT_BYTES) {
        state->tx_dropped++;
        return ERR_MEM;
    }
    pthread_mutex_lock(&state->tx.mutex);
    if (state->tx.count == CS11_TX_SLOTS) {
        state->tx_dropped++;
        pthread_mutex_unlock(&state->tx.mutex);
        return ERR_MEM;
    }
    u16_t slot = state->tx.head;
    u16_t copied = pbuf_copy_partial(p, state->tx.frame[slot], p->tot_len, 0);
    state->tx.length[slot] = copied;
    state->tx.head = (u16_t)((slot + 1) % CS11_TX_SLOTS);
    state->tx.count++;
    state->tx_frames++;
    state->tx_bytes += copied;
    pthread_cond_signal(&state->tx.ready);
    pthread_mutex_unlock(&state->tx.mutex);
    return ERR_OK;
}

static err_t cs11_netif_init(struct netif *netif) {
    netif->name[0] = 'c';
    netif->name[1] = 's';
    netif->mtu = 1500;
    netif->hwaddr_len = 6;
    /* NETIF_FLAG_MLD6 matters here: the iPhone's neighbour solicitations are sent to the solicited-node
     * multicast address (33:33:ff:.., measured on the car), and lwIP's ethernet_input only passes IPv6
     * multicast frames up when the netif carries this flag. Without it every NS was dropped, no neighbour
     * advertisement ever went out (txFrames stayed at the four frames of the mDNS announcement), and the
     * phone could never resolve us - which is why it never opened the AirPlay connection. */
    netif->flags = NETIF_FLAG_BROADCAST | NETIF_FLAG_ETHERNET | NETIF_FLAG_UP | NETIF_FLAG_LINK_UP |
                   NETIF_FLAG_MLD6;
    netif->linkoutput = cs11_netif_linkoutput;
    /* IPv4 is compiled out; only the IPv6 output path is used. */
    netif->output_ip6 = ethip6_output;
    return ERR_OK;
}

/** Creates the netif and pins its MAC-derived link-local on it. Safe to call again: a reconnect re-arms
 *  the existing netif instead of rebuilding it. */
int cs11_netif_start(const u8_t *host_mac, int mtu) {
    static int netif_created = 0;

    if (netif_created) {
        /* Why this branch exists, measured on the car on 2026-10-13 (the "it will not connect again"
         * defect): every reconnect used to run the body below again, which memsets this struct and calls
         * netif_add() on it a second time. lwIP still holds this very struct in netif_list, so the
         * second call zeroed a live netif (next/input/linkoutput NULL) and inserted the same pointer
         * into the list again, while the TX ring and its mutex - which the Kotlin pump thread is using -
         * were reset underneath it. The symptoms were a wedged pipe after any in-process reconnect:
         * rxFrames frozen while txDropped climbed, and the iPhone never starting the AirPlay transport.
         * The Java side derives the same address from the same MAC, so re-arming is all a reconnect
         * needs. */
        struct netif *netif = &g_cs11.netif;
        if (mtu > 0) {
            netif->mtu = (u16_t)mtu;
        }
        if (host_mac != NULL) {
            memcpy(netif->hwaddr, host_mac, 6);
        }
        netif_create_ip6_linklocal_address(netif, 1);
        netif_ip6_addr_set_state(netif, 0, IP6_ADDR_PREFERRED);
        netif_set_up(netif);
        netif_set_link_up(netif);
        tcpip_callback(cs11_announce_link_up_cb, netif);
        return 0;
    }

    memset(&g_cs11, 0, sizeof(g_cs11));
    pthread_mutex_init(&g_cs11.tx.mutex, NULL);
    pthread_cond_init(&g_cs11.tx.ready, NULL);

    /* With IPv4 compiled out, netif_add takes (netif, state, init, input) - lwip/netif.h narrows the
     * signature under #if LWIP_IPV4, so asking for the IPv4 form would not compile. */
#if LWIP_IPV4
    struct netif *netif = netif_add(
        &g_cs11.netif, NULL, NULL, NULL, &g_cs11, cs11_netif_init, tcpip_input);
#else
    struct netif *netif = netif_add(&g_cs11.netif, &g_cs11, cs11_netif_init, tcpip_input);
#endif
    if (netif == NULL) {
        return -1;
    }
    if (mtu > 0) {
        netif->mtu = (u16_t)mtu;
    }
    if (host_mac != NULL) {
        memcpy(netif->hwaddr, host_mac, 6);
    } else {
        /* Locally administered, unicast: the host side picks its own MAC in NCM. */
        netif->hwaddr[0] = 0x02;
        netif->hwaddr[1] = 0x11;
        netif->hwaddr[2] = 0x22;
        netif->hwaddr[3] = 0x33;
        netif->hwaddr[4] = 0x44;
        netif->hwaddr[5] = 0x55;
    }

    /* The link-local comes from the MAC, the way the reference implementation's does: the phone dials the
     * address CarPlayStartSession named, and it never dialled the fe80::2 this used to pin - measured as zero
     * TCP frames from the phone, while the reference (AirPlay SETUP rtsp://fe80::9c92:4fff:fe09:bf8b) streams
     * megabytes. The Java side derives the same address from the same MAC (LinkLocalAddress), so the netif and
     * the advertisement agree. PREFERRED right away: on a point-to-point NCM link there is no DAD to wait for,
     * and a tentative address answers no neighbour solicitation. */
    netif_create_ip6_linklocal_address(netif, 1);
    netif_ip6_addr_set_state(netif, 0, IP6_ADDR_PREFERRED);

    netif_set_up(netif);
    netif_set_link_up(netif);
    tcpip_callback(cs11_announce_link_up_cb, netif);
    netif_created = 1;
    return 0;
}

/** Counts the IPv6 protocol histogram straight off the Ethernet frame we were handed.
 *
 *  Counting here (rather than inside lwIP) keeps the numbers independent of which input hook lwIP
 *  chose, and it is the only place that still sees a frame lwIP later drops (a neighbour solicitation
 *  for an address we do not own, say) - which is exactly the difference we need on the diagnostic line. */
static void count_received(const u8_t *frame, size_t length) {
    /* Ethernet II header (14) + fixed IPv6 header (40). */
    if (length < 54) {
        return;
    }
    if (frame[12] != 0x86 || frame[13] != 0xdd) {
        return;
    }
    g_cs11.ipv6_rx++;
    /* IPv6 "next header" sits at 14 + 6. Extension headers are not expected on the NCM link. */
    u8_t next = frame[20];
    if (next == 6) {
        g_cs11.tcp_rx++;
        if (length >= 58) {
            /* TCP destination port is the second 16-bit field of the TCP header (14 + 40 + 2). */
            g_cs11.last_tcp_dst = ((u32_t)frame[56] << 8) | (u32_t)frame[57];
        }
    } else if (next == 17) {
        g_cs11.udp_rx++;
    } else if (next == 58) {
        g_cs11.icmp6_rx++;
    }
}

/** Injects one Ethernet II frame (from the USB reader thread). Returns 0 when it was handed to lwIP. */
int cs11_netif_input(const u8_t *frame, size_t length) {
    if (frame == NULL || length == 0 || length > 0xffff) {
        return -1;
    }
    count_received(frame, length);
    struct pbuf *p = pbuf_alloc(PBUF_RAW, (u16_t)length, PBUF_POOL);
    if (p == NULL) {
        return -2;
    }
    if (pbuf_take(p, frame, (u16_t)length) != ERR_OK) {
        pbuf_free(p);
        return -3;
    }
    g_cs11.rx_frames++;
    g_cs11.rx_bytes += (u32_t)length;
    /* The input function must be checked for NULL: `netif_add` sets it, and a netif that was rebuilt or
     * zeroed would leave it empty, which is a jump to address zero. */
    if (g_cs11.netif.input == NULL) {
        pbuf_free(p);
        return -5;
    }
    if (g_cs11.netif.input(p, &g_cs11.netif) != ERR_OK) {
        /* The pbuf is ours to free.
         *
         * This used to claim "tcpip_input already freed the pbuf on failure", which is wrong: lwIP 2.2.0's
         * tcpip_inpkt() returns ERR_MEM on both of its failure paths - a failed memp_malloc and a failed
         * sys_mbox_trypost - and in neither of them does it touch `p`. Only the message is freed. Leaving the
         * claim in place leaked a pool buffer for every packet the mailbox refused, which is exactly the load
         * a burst of swipes produces. */
        pbuf_free(p);
        return -4;
    }
    return 0;
}

/** Copies the oldest queued outbound frame into [out]; returns its length, or 0 when the ring is empty. */
int cs11_netif_take_tx(u8_t *out, size_t capacity) {
    pthread_mutex_lock(&g_cs11.tx.mutex);
    if (g_cs11.tx.count == 0) {
        pthread_mutex_unlock(&g_cs11.tx.mutex);
        return 0;
    }
    u16_t slot = g_cs11.tx.tail;
    u16_t length = g_cs11.tx.length[slot];
    if (length > capacity) {
        /* The caller's buffer is smaller than a frame: drop it rather than splitting a frame. */
        g_cs11.tx.tail = (u16_t)((slot + 1) % CS11_TX_SLOTS);
        g_cs11.tx.count--;
        g_cs11.tx_dropped++;
        pthread_mutex_unlock(&g_cs11.tx.mutex);
        return -1;
    }
    memcpy(out, g_cs11.tx.frame[slot], length);
    g_cs11.tx.tail = (u16_t)((slot + 1) % CS11_TX_SLOTS);
    g_cs11.tx.count--;
    pthread_mutex_unlock(&g_cs11.tx.mutex);
    return (int)length;
}

/**
 * Blocks until the TX ring holds at least one frame or [timeout_ms] passes; returns the pending count
 * (0 on timeout). The wait is a condition variable that linkoutput signals, so an ACK, a window update
 * or a touch report leaves for the USB writer as soon as lwIP produces it instead of after the next
 * 1 ms poll. CLOCK_REALTIME matches sys_arch.c's waits; the timeout only bounds how long a stopping
 * pump takes to notice, because every queued frame wakes the waiter directly.
 */
int cs11_netif_wait_tx(int timeout_ms) {
    pthread_mutex_lock(&g_cs11.tx.mutex);
    if (g_cs11.tx.count == 0 && timeout_ms > 0) {
        struct timespec deadline;
        clock_gettime(CLOCK_REALTIME, &deadline);
        deadline.tv_sec += timeout_ms / 1000;
        deadline.tv_nsec += (long)(timeout_ms % 1000) * 1000000L;
        if (deadline.tv_nsec >= 1000000000L) {
            deadline.tv_sec += 1;
            deadline.tv_nsec -= 1000000000L;
        }
        while (g_cs11.tx.count == 0) {
            if (pthread_cond_timedwait(&g_cs11.tx.ready, &g_cs11.tx.mutex, &deadline) == ETIMEDOUT) {
                break;
            }
        }
    }
    int pending = (int)g_cs11.tx.count;
    pthread_mutex_unlock(&g_cs11.tx.mutex);
    return pending;
}

int cs11_netif_tx_pending(void) {
    pthread_mutex_lock(&g_cs11.tx.mutex);
    int pending = (int)g_cs11.tx.count;
    pthread_mutex_unlock(&g_cs11.tx.mutex);
    return pending;
}

/** rx frames, rx bytes, tx frames, tx bytes, tx dropped, then the IPv6 histogram:
 *  ipv6, tcp, udp, icmp6, and the last TCP destination port - for one diagnostic line. */
void cs11_netif_stats(u32_t *out) {
    out[0] = g_cs11.rx_frames;
    out[1] = g_cs11.rx_bytes;
    out[2] = g_cs11.tx_frames;
    out[3] = g_cs11.tx_bytes;
    out[4] = g_cs11.tx_dropped;
    out[5] = g_cs11.ipv6_rx;
    out[6] = g_cs11.tcp_rx;
    out[7] = g_cs11.udp_rx;
    out[8] = g_cs11.icmp6_rx;
    out[9] = g_cs11.last_tcp_dst;
}

/* The netif this port owns. lwIP's socket-level IPV6_JOIN_GROUP resolves a netif *index* and answered
 * EADDRNOTAVAIL for every value the Java side could guess (measured on the car: errno 99), so the join goes
 * through mld6_joingroup_netif() with this handle instead (docs/_digest/64 §9). */
struct netif *cs11_netif_handle(void) {
    return &g_cs11.netif;
}