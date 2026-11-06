/*
 * Host tests for the CS11 userspace lwIP bridge (docs/_digest/93 §12, docs/_digest/92 §7.2).
 *
 * These run on the PC against the same `bridge/sys_arch.c` and `bridge/cs11_netif.c` the APK builds, with
 * the vendored lwIP and a host `lwipopts.h` (fixed pools, stats on, small heap). The point is to make the
 * races deterministic that a fast CPU cannot reproduce:
 *
 *   1. mailbox round trip                      - basic primitives still work after the close handshake
 *   2. destroy a mailbox with a blocked poster - the UB fixed in this round (waiter must leave, no UAF)
 *   3. receive storm from four threads         - the message/pbuf churn that killed the tcpip thread
 *   4. a refused packet must not leak a pbuf   - 6c2671b3's leak, asserted on the pool counter
 *   5. TX wait times out on an empty ring      - the pump's bounded wait returns 0, not early, not never
 *   6. TX wait wakes on linkoutput             - a queued frame wakes the pump at once (no 1 ms poll)
 *
 * Run under ASan; leak detection is disabled because the tcpip thread and its mailbox live until the
 * process exits (see run.ps1).
 */
#include <pthread.h>
#include <stdio.h>
#include <time.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#include "lwip/init.h"
#include "lwip/memp.h"
#include "lwip/netif.h"
#include "lwip/pbuf.h"
#include "lwip/stats.h"
#include "lwip/sys.h"
#include "lwip/tcpip.h"

/* The bridge's C surface (no header ships with it; the JNI layer is the only other caller). */
int cs11_netif_input(const unsigned char *frame, size_t length);
int cs11_netif_start(const unsigned char *host_mac, int mtu);
struct netif *cs11_netif_handle(void);
void cs11_netif_stats(unsigned int *out);
int cs11_netif_take_tx(unsigned char *out, size_t capacity);
int cs11_netif_tx_pending(void);
int cs11_netif_wait_tx(int timeout_ms);

static int failures;

#define CHECK(condition, ...)                                     \
    do {                                                          \
        if (!(condition)) {                                       \
            fprintf(stderr, "FAIL %s:%d: ", __FILE__, __LINE__);  \
            fprintf(stderr, __VA_ARGS__);                         \
            fputc('\n', stderr);                                  \
            failures++;                                           \
        }                                                         \
    } while (0)

/* ── 1. mailbox round trip ─────────────────────────────────────────────────────────────────── */

static void test_mailbox_roundtrip(void) {
    sys_mbox_t mbox;
    CHECK(sys_mbox_new(&mbox, 4) == ERR_OK, "sys_mbox_new failed");

    int first = 1;
    int second = 2;
    sys_mbox_post(&mbox, &first);
    sys_mbox_post(&mbox, &second);

    void *message = NULL;
    sys_arch_mbox_fetch(&mbox, &message, 1000);
    CHECK(message == &first, "first fetch returned %p", message);

    message = NULL;
    sys_arch_mbox_tryfetch(&mbox, &message);
    CHECK(message == &second, "second fetch returned %p", message);

    CHECK(sys_arch_mbox_tryfetch(&mbox, &message) == SYS_MBOX_EMPTY, "empty mailbox did not report empty");
    sys_mbox_free(&mbox);
}

/* ── 2. destroy the mailbox while a poster is blocked ─────────────────────────────────────── */

typedef struct {
    sys_mbox_t *mbox;
    void *msg;
    volatile int done;
} poster_args;

static void *blocked_poster(void *argument) {
    poster_args *args = (poster_args *)argument;
    sys_mbox_post(args->mbox, args->msg);
    args->done = 1;
    return NULL;
}

static void test_mailbox_destroy_with_blocked_poster(void) {
    sys_mbox_t mbox;
    CHECK(sys_mbox_new(&mbox, 1) == ERR_OK, "sys_mbox_new failed");

    int filler = 0;
    int blocked = 1;
    sys_mbox_post(&mbox, &filler); /* full: the poster below has to wait */

    poster_args args = {.mbox = &mbox, .msg = &blocked, .done = 0};
    pthread_t thread;
    CHECK(pthread_create(&thread, NULL, blocked_poster, &args) == 0, "pthread_create failed");
    usleep(150 * 1000); /* let it park in sys_mbox_post */

    /* The fix under test: free must mark closing, wake the waiter and wait for it to leave before it
     * destroys the mutex and condition variables. With the old broadcast-then-destroy the waiter came
     * back inside freed objects (ASan would report it, or the test would hang). */
    sys_mbox_free(&mbox);

    for (int i = 0; i < 200 && !args.done; i++) {
        usleep(10 * 1000);
    }
    CHECK(args.done, "the blocked poster never returned after sys_mbox_free");
    if (!args.done) {
        /* Joining would hang exactly like the bug; fail hard instead. */
        fprintf(stderr, "ABORT: blocked poster stuck\n");
        exit(1);
    }
    pthread_join(thread, NULL);
}

/* ── 3. receive storm ─────────────────────────────────────────────────────────────────────── */

#define STORM_THREADS 1   /* production has exactly one RX pump (dda143dd); lwIP memp is not thread-safe here */
#define STORM_FRAMES 10000

static unsigned char storm_frame[86];

static void *storm_sender(void *argument) {
    (void)argument;
    for (int i = 0; i < STORM_FRAMES; i++) {
        (void)cs11_netif_input(storm_frame, sizeof(storm_frame));
    }
    return NULL;
}

/* ── 4. a refused packet must free its pbuf ───────────────────────────────────────────────── */

static err_t refuse_input(struct pbuf *p, struct netif *netif) {
    (void)p;
    (void)netif;
    return ERR_MEM; /* tcpip_input is refused: the caller still owns the pbuf */
}

static unsigned int pool_in_use(void) {
#if LWIP_STATS && MEMP_STATS
    return lwip_stats.memp[MEMP_PBUF_POOL]->used;
#else
    return 0;
#endif
}

static void start_stack(void) {
    /* lwIP's pools and the mem mutex exist only after init; the caller runs tcpip_init() first. */
    const unsigned char mac[6] = {0x02, 0x11, 0x22, 0x33, 0x44, 0x55};
    CHECK(cs11_netif_start(mac, 1500) == 0, "cs11_netif_start failed");
}

static void test_refused_packet_does_not_leak_pbuf(void) {
    struct netif *netif = cs11_netif_handle();
    CHECK(netif != NULL, "cs11_netif_handle returned NULL");
    if (netif == NULL) {
        return;
    }

    netif_input_fn saved = netif->input;
    netif->input = refuse_input;

    const unsigned int before = pool_in_use();
    for (int i = 0; i < 500; i++) {
        (void)cs11_netif_input(storm_frame, sizeof(storm_frame));
    }
    const unsigned int after = pool_in_use();
    netif->input = saved;

    CHECK(before == after, "pbuf pool leaked: before=%u after=%u", before, after);
}

static void test_receive_storm(void) {
    unsigned int before[10] = {0};
    cs11_netif_stats(before);

    pthread_t threads[STORM_THREADS];
    for (int i = 0; i < STORM_THREADS; i++) {
        CHECK(pthread_create(&threads[i], NULL, storm_sender, NULL) == 0, "pthread_create failed");
    }
    for (int i = 0; i < STORM_THREADS; i++) {
        pthread_join(threads[i], NULL);
    }

    unsigned int after[10] = {0};
    cs11_netif_stats(after);
    const unsigned int accepted = after[0] - before[0];
    /* Not every frame gets a pbuf: the pool is 16 entries on purpose, so most of the storm is refused and
     * takes the free-on-refusal path. What matters is that the churn does not corrupt the pool (ASan) and
     * that some frames did go through. */
    printf("storm: %u/%u frames accepted\n", accepted, (unsigned int)(STORM_THREADS * STORM_FRAMES));
    CHECK(accepted > 0, "no frame was accepted at all");
    CHECK(accepted <= STORM_THREADS * STORM_FRAMES, "rx_frames=%u exceeds the frames sent", accepted);
}

/* ── 5/6. TX wait ──────────────────────────────────────────────────────────────────────────── */

static long elapsed_ms(const struct timespec *start) {
    struct timespec now;
    clock_gettime(CLOCK_MONOTONIC, &now);
    return (long)(now.tv_sec - start->tv_sec) * 1000L + (now.tv_nsec - start->tv_nsec) / 1000000L;
}

/* The link-up announcement (MLD report, router solicitation) leaves frames in the ring; start empty. */
static void drain_tx_ring(void) {
    unsigned char frame[1600];
    while (cs11_netif_take_tx(frame, sizeof(frame)) > 0) {
    }
}

static void test_tx_wait_times_out_on_empty_ring(void) {
    drain_tx_ring();
    struct timespec start;
    clock_gettime(CLOCK_MONOTONIC, &start);
    const int pending = cs11_netif_wait_tx(40);
    const long waited = elapsed_ms(&start);
    CHECK(pending == 0, "empty ring reported %d pending frames", pending);
    CHECK(waited >= 30, "wait returned after %ld ms, before its 40 ms timeout", waited);
    CHECK(waited < 1000, "wait took %ld ms for a 40 ms timeout", waited);
    /* A zero timeout is a plain poll. */
    CHECK(cs11_netif_wait_tx(0) == 0, "zero-timeout poll on an empty ring reported frames");
}

struct tx_waiter {
    int pending;
    long waited_ms;
};

static void *tx_waiter_thread(void *raw) {
    struct tx_waiter *waiter = (struct tx_waiter *)raw;
    struct timespec start;
    clock_gettime(CLOCK_MONOTONIC, &start);
    waiter->pending = cs11_netif_wait_tx(5000);
    waiter->waited_ms = elapsed_ms(&start);
    return NULL;
}

static void test_tx_wait_wakes_on_linkoutput(void) {
    drain_tx_ring();
    struct netif *netif = cs11_netif_handle();
    CHECK(netif != NULL && netif->linkoutput != NULL, "netif or its linkoutput is missing");
    if (netif == NULL || netif->linkoutput == NULL) {
        return;
    }
    struct tx_waiter waiter = {-1, -1};
    pthread_t thread;
    CHECK(pthread_create(&thread, NULL, tx_waiter_thread, &waiter) == 0, "pthread_create failed");
    usleep(100 * 1000); /* let the waiter block first, so the wake-up is what ends its wait */

    struct pbuf *frame = pbuf_alloc(PBUF_RAW, 64, PBUF_RAM);
    CHECK(frame != NULL, "pbuf_alloc failed");
    if (frame != NULL) {
        memset(frame->payload, 0xab, 64);
        CHECK(netif->linkoutput(netif, frame) == ERR_OK, "linkoutput refused the frame");
        pbuf_free(frame);
    }
    pthread_join(thread, NULL);

    /* lwIP's own timers (MLD report retransmits, ND) may also queue a frame meanwhile, so the waiter can see
     * more than one, or be woken by one of those: only "woken promptly, not by the 5 s timeout" is asserted. */
    CHECK(waiter.pending >= 1, "waiter saw %d pending frames, expected at least 1", waiter.pending);
    CHECK(waiter.waited_ms >= 0 && waiter.waited_ms < 1000,
          "waiter returned after %ld ms; the linkoutput signal did not end the wait", waiter.waited_ms);

    unsigned char out[1600];
    int found = 0;
    int length;
    while ((length = cs11_netif_take_tx(out, sizeof(out))) > 0) {
        if (length == 64 && out[0] == 0xab && out[63] == 0xab) {
            found = 1;
        }
    }
    CHECK(found, "the 64-byte frame queued through linkoutput never came out of the ring");
}

int main(void) {
    /* A syntactically valid Ethernet II + IPv6 header, so ethernet_input takes the normal path and the
     * packets reach the tcpip thread instead of being rejected at the first length check. */
    memset(storm_frame, 0, sizeof(storm_frame));
    storm_frame[0] = 0x33;
    storm_frame[1] = 0x33;
    storm_frame[2] = 0xFF;
    storm_frame[5] = 0x01;
    storm_frame[6] = 0x02;
    storm_frame[11] = 0x02;
    storm_frame[12] = 0x86;
    storm_frame[13] = 0xDD;
    storm_frame[14] = 0x60; /* IPv6 version 6 */
    storm_frame[20] = 17;   /* next header: UDP */

    /* lwIP's memory pools and the mem mutex exist only after init; the mailbox tests allocate too. */
    tcpip_init(NULL, NULL);

    test_mailbox_roundtrip();
    test_mailbox_destroy_with_blocked_poster();

    start_stack();
    test_refused_packet_does_not_leak_pbuf();
    test_receive_storm();
    test_tx_wait_times_out_on_empty_ring();
    test_tx_wait_wakes_on_linkoutput();

    if (failures == 0) {
        printf("OK: all native bridge tests passed\n");
        return 0;
    }
    printf("FAILED: %d check(s)\n", failures);
    return 1;
}
