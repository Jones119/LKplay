/*
 * lwIP configuration for the CS11 userspace NCM bridge (docs/_digest/58 §7).
 *
 * The car link is IPv6-only over Ethernet II frames carried in NCM NTB16 blocks, and the session needs
 * exactly what AirPlay uses: a control TCP connection, a video TCP connection, audio/mic/NTP UDP, and an
 * iAP2 tunnel TCP connection. There is no router, no DHCP and no DNS on this link - the iPhone is one
 * hop away over a point-to-point segment - so all of that is compiled out.
 */
#ifndef CS11_LWIPOPTS_H
#define CS11_LWIPOPTS_H

/* ── OS / API surface ─────────────────────────────────────────────────────────────────────────── */
#define NO_SYS                          0
#define LWIP_NETCONN                    1
#define LWIP_SOCKET                     1
/* Call lwip_* explicitly: the platform headers already own socket(), bind(), recv() ... */
#define LWIP_COMPAT_SOCKETS             0
#define LWIP_POSIX_SOCKETS_IO_NAMES     0
#define LWIP_SOCKET_OFFSET              0

/* ── Protocols ────────────────────────────────────────────────────────────────────────────────── */
#define LWIP_IPV4                       0  /* the NCM link is IPv6-only (fe80::2 <-> the iPhone) */
#define LWIP_IPV6                       1
#define LWIP_IPV6_ND                    1  /* neighbour discovery is how the iPhone is reached */
#define LWIP_IPV6_AUTOCONFIG            0  /* we pin fe80::2, so no SLAAC */
#define LWIP_IPV6_DHCP6                 0
#define LWIP_IPV6_SEND_ROUTER_SOLICIT   0
#define LWIP_ICMP6                      1  /* ND6 needs it */
#define LWIP_IPV6_MLD                   0
#define LWIP_TCP                        1
#define LWIP_UDP                        1
#define LWIP_RAW                        0
#define LWIP_DNS                        0
#define LWIP_DHCP                       0
#define LWIP_ARP                        0
#define LWIP_ETHERNET                   1  /* the netif speaks Ethernet II frames, as NCM does */
#define LWIP_NETIF_HOSTNAME             0
#define LWIP_NETIF_STATUS_CALLBACK      1
#define LWIP_NETIF_LINK_CALLBACK        1
#define LWIP_IGMP                       0
#define LWIP_NETBIOS_RESPOND_NAME_QUERY 0

/* ── Memory ───────────────────────────────────────────────────────────────────────────────────── */
/* Use the platform allocator: Android's malloc is already tuned and the app must stay small. */
#define MEM_LIBC_MALLOC                 1
#define MEMP_MEM_MALLOC                 1
#define MEM_ALIGNMENT                   4
#define MEM_SIZE                        (256 * 1024)
#define PBUF_POOL_SIZE                  64
#define PBUF_POOL_BUFSIZE               1600
/* One pbuf per frame: the TX path hands whole Ethernet frames to the NCM framer. */
#define LWIP_NETIF_TX_SINGLE_PBUF       1
#define MEMP_NUM_SYS_TIMEOUT            16

/* ── TCP tuned for one video stream plus control channels over a 16 KiB NTB input ─────────────── */
#define TCP_MSS                         1400
/*
 * The receive window is the throttle on the phone's video sender, and the kernel path was tuned to
 * exactly this size on the car: a 128 KiB window let the phone dump multi-megabit bursts into the NCM
 * link and evict audio, while 16 KiB paced the sender (docs/_digest/51). Without window scaling TCP_WND
 * must also fit an u16_t.
 */
#define TCP_WND                         (16 * 1024)
#define TCP_SND_BUF                     (16 * 1024)
#define TCP_SND_QUEUELEN                ((8 * TCP_SND_BUF) / TCP_MSS)
#define TCP_QUEUE_OOSEQ                 1
#define MEMP_NUM_TCP_PCB                16
#define MEMP_NUM_TCP_PCB_LISTEN         8
#define MEMP_NUM_TCP_SEG                512
#define MEMP_NUM_UDP_PCB                16
#define DEFAULT_TCP_RECVMBOX_SIZE       256
#define DEFAULT_UDP_RECVMBOX_SIZE       64
#define DEFAULT_ACCEPTMBOX_SIZE         8
#define TCP_LISTEN_BACKLOG              1
#define TCP_DEFAULT_LISTEN_BACKLOG      8

/* ── Socket options the AirPlay channels set through our seam ─────────────────────────────────── */
#define LWIP_SO_RCVBUF                  1
#define LWIP_SO_RCVTIMEO                1
#define LWIP_SO_SNDTIMEO                0
#define LWIP_SO_LINGER                  1
#define SO_REUSE                        1
#define LWIP_TCP_KEEPALIVE              1
#define LWIP_TCP_TIMESTAMPS             0
/* Android's libc already provides socklen_t and struct timeval; lwIP would redefine both. */
#define SOCKLEN_T_DEFINED               1
#define LWIP_TIMEVAL_PRIVATE            0

/* ── Threading (tcpip_thread + mailboxes; sys_arch is pthreads, see bridge/sys_arch.c) ────────── */
#define TCPIP_THREAD_NAME               "lwip-tcpip"
#define TCPIP_THREAD_STACKSIZE          (8 * 1024)
#define TCPIP_THREAD_PRIO               2
#define TCPIP_MBOX_SIZE                 256
#define DEFAULT_THREAD_STACKSIZE        (4 * 1024)
#define DEFAULT_THREAD_PRIO             1
#define LWIP_TCPIP_CORE_LOCKING         0
#define LWIP_NETCONN_FULLDUPLEX         0
#define LWIP_NETCONN_SEM_PER_THREAD     0
#define SYS_LIGHTWEIGHT_PROT            0

/* Diagnostics stay off in the shipping build; flip to 1 locally to trace lwIP itself. */
#define LWIP_STATS                      0
#define LWIP_DEBUG                      0
#define LWIP_NOASSERT                   0

#endif /* CS11_LWIPOPTS_H */
