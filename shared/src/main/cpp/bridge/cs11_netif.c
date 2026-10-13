/*
 * The one lwIP netif that carries CarPlay over the NCM link (docs/_digest/58 §7).
 *
 * Frames in: the USB bridge's reader thread hands a whole Ethernet II frame to cs11_netif_input(), which
 * copies it into a pbuf and passes it to lwIP's tcpip_input() (safe from any thread, because core locking
 * is off). Frames out: lwIP's linkoutput copies the frame into a small ring that the Kotlin side polls with
 * nativePollTxFrame(); polling instead of a JNI upcall keeps the JNI out of lwIP's thread and lets the USB
 * writer own its own pacing, which is how the NTB framer already works.
 *
 * The link-local address is pinned to fe80::2 rather than derived from the interface MAC: the iPhone is
 * told this address through iAP2's CarPlayStartSession, so every existing bind and advertisement keeps
 * working untouched (docs/_digest/58 §5 item 9).
 */
#include "lwip/opt.h"
#include "lwip/def.h"
#include "lwip/ip6_addr.h"
#include "lwip/mem.h"
#include "lwip/netif.h"
#include "lwip/pbuf.h"
#include "lwip/tcpip.h"
#include "lwip/ethip6.h"
#include "netif/ethernet.h"

#include <pthread.h>
#include <string.h>

#define CS11_TX_SLOTS 32
#define CS11_TX_SLOT_BYTES 1600

struct cs11_netif_state {
    struct netif netif;
    struct {
        pthread_mutex_t mutex;
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
    pthread_mutex_unlock(&state->tx.mutex);
    return ERR_OK;
}

static err_t cs11_netif_init(struct netif *netif) {
    netif->name[0] = 'c';
    netif->name[1] = 's';
    netif->mtu = 1500;
    netif->hwaddr_len = 6;
    netif->flags = NETIF_FLAG_BROADCAST | NETIF_FLAG_ETHERNET | NETIF_FLAG_UP | NETIF_FLAG_LINK_UP;
    netif->linkoutput = cs11_netif_linkoutput;
    /* IPv4 is compiled out; only the IPv6 output path is used. */
    netif->output_ip6 = ethip6_output;
    return ERR_OK;
}

/** Creates the netif and pins fe80::2 on it. Call once, before any JVM thread uses a socket. */
int cs11_netif_start(const u8_t *host_mac, int mtu) {
    memset(&g_cs11, 0, sizeof(g_cs11));
    pthread_mutex_init(&g_cs11.tx.mutex, NULL);

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

    /* IP6_ADDR takes four 32-bit words in network order (lwip/ip6_addr.h). */
    ip6_addr_t linklocal;
    IP6_ADDR(&linklocal, PP_HTONL(0xfe800000UL), PP_HTONL(0x00000000UL),
             PP_HTONL(0x00000000UL), PP_HTONL(0x00000002UL));
    netif_ip6_addr_set(netif, 0, &linklocal);
    netif_ip6_addr_set_state(netif, 0, IP6_ADDR_PREFERRED);

    netif_set_up(netif);
    netif_set_link_up(netif);
    return 0;
}

/** Injects one Ethernet II frame (from the USB reader thread). Returns 0 when it was handed to lwIP. */
int cs11_netif_input(const u8_t *frame, size_t length) {
    if (frame == NULL || length == 0 || length > 0xffff) {
        return -1;
    }
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
    if (g_cs11.netif.input(p, &g_cs11.netif) != ERR_OK) {
        /* tcpip_input already freed the pbuf on failure. */
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

int cs11_netif_tx_pending(void) {
    pthread_mutex_lock(&g_cs11.tx.mutex);
    int pending = (int)g_cs11.tx.count;
    pthread_mutex_unlock(&g_cs11.tx.mutex);
    return pending;
}

/** rx frames, rx bytes, tx frames, tx bytes, tx dropped - for one diagnostic line. */
void cs11_netif_stats(u32_t *out) {
    out[0] = g_cs11.rx_frames;
    out[1] = g_cs11.rx_bytes;
    out[2] = g_cs11.tx_frames;
    out[3] = g_cs11.tx_bytes;
    out[4] = g_cs11.tx_dropped;
}

/* The netif this port owns. lwIP's socket-level IPV6_JOIN_GROUP resolves a netif *index* and answered
 * EADDRNOTAVAIL for every value the Java side could guess (measured on the car: errno 99), so the join goes
 * through mld6_joingroup_netif() with this handle instead (docs/_digest/64 §9). */
struct netif *cs11_netif_handle(void) {
    return &g_cs11.netif;
}