/*
 * Host test configuration for the CS11 lwIP bridge.
 *
 * The shipped `lwipopts.h` is included first, then narrowed so a bug is observable on a desktop:
 *
 * - The pools become fixed arrays instead of malloc. `pbuf_alloc` then fails predictably once the pool is
 *   empty, which is what makes "a refused packet must free its pbuf" a deterministic assertion instead of a
 *   memory-pressure coincidence.
 * - The counters are enabled so a test can read how many pool entries are in use before and after.
 * - The heap is small on purpose: an unbounded leak trips it quickly.
 *
 * Nothing here reaches the APK; the Android build includes the real `lwipopts.h`.
 */
#ifndef CS11_NATIVE_TEST_LWIPOPTS_H
#define CS11_NATIVE_TEST_LWIPOPTS_H

#include "../shared/src/main/cpp/bridge/lwipopts.h"

#undef MEM_LIBC_MALLOC
#define MEM_LIBC_MALLOC 0
#undef MEMP_MEM_MALLOC
#define MEMP_MEM_MALLOC 0

#undef MEM_SIZE
#define MEM_SIZE (64 * 1024)
#undef PBUF_POOL_SIZE
#define PBUF_POOL_SIZE 16
#undef MEMP_NUM_TCP_PCB
#define MEMP_NUM_TCP_PCB 8
#undef MEMP_NUM_TCP_SEG
#define MEMP_NUM_TCP_SEG 64
#undef MEMP_NUM_TCPIP_MSG_INPKT
#define MEMP_NUM_TCPIP_MSG_INPKT 16

#undef LWIP_STATS
#define LWIP_STATS 1
#undef MEMP_STATS
#define MEMP_STATS 1
#undef MEM_STATS
#define MEM_STATS 1
#undef SYS_STATS
#define SYS_STATS 1

#undef LWIP_DEBUG
#define LWIP_DEBUG 0

#endif /* CS11_NATIVE_TEST_LWIPOPTS_H */
