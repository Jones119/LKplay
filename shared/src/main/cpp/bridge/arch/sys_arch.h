/*
 * lwIP's OS type definitions for the pthreads port in bridge/sys_arch.c (docs/_digest/58 §7).
 *
 * Semaphores and mailboxes are pointers to opaque structures owned by sys_arch.c, so the NULL sentinels
 * lwIP's sys.h tests against are plain NULL. Threads are pthread_t.
 */
#ifndef CS11_ARCH_SYS_ARCH_H
#define CS11_ARCH_SYS_ARCH_H

#include <pthread.h>

typedef void *sys_sem_t;
typedef void *sys_mbox_t;
typedef pthread_mutex_t *sys_mutex_t;
typedef pthread_t sys_thread_t;

#define SYS_MBOX_NULL NULL
#define SYS_SEM_NULL NULL

/* lwIP's sys.h defines the valid/set_invalid helpers from these sentinels, so sys_arch.c must not
 * define functions with those names. */
#define sys_sem_valid(sem) (((sem) != NULL) && (*(sem) != SYS_SEM_NULL))
#define sys_sem_set_invalid(sem) do { if ((sem) != NULL) { *(sem) = SYS_SEM_NULL; } } while (0)
#define sys_mbox_valid(mbox) (((mbox) != NULL) && (*(mbox) != SYS_MBOX_NULL))
#define sys_mbox_set_invalid(mbox) do { if ((mbox) != NULL) { *(mbox) = SYS_MBOX_NULL; } } while (0)

/*
 * Lightweight protection (SYS_LIGHTWEIGHT_PROT=1): the critical sections lwIP puts around memp/mem.
 *
 * lwIP calls these from whichever thread enters it - our RX pump calls tcpip_input(), which allocates a
 * MEMP_TCPIP_MSG_INPKT while the tcpip thread concurrently frees messages from the same pool. With the
 * protection compiled out that free-list is shared without synchronisation; the host test reproduced the
 * resulting corruption (do_memp_malloc_pool reading a poisoned next pointer) on the first storm run.
 * The sections are tiny and lwIP documents that they must be nestable, so this is a recursive mutex
 * rather than a spinlock.
 */
/*
 * Per-thread API semaphore for LWIP_NETCONN_SEM_PER_THREAD (docs/_digest/101).
 *
 * Without it every socket API call waits on the netconn's single op_completed semaphore. Two threads
 * using one socket then consume each other's signal, a caller returns while its stack-allocated
 * tcpip_msg is still queued, and the tcpip thread later calls whatever overwrote that stack frame.
 * That is the crash the canary caught (posted_by=lwip_netconn_do_write, message content 0x02).
 */
sys_sem_t *sys_thread_sem_get(void);
#define LWIP_NETCONN_THREAD_SEM_GET()   sys_thread_sem_get()
#define LWIP_NETCONN_THREAD_SEM_ALLOC() ((void)sys_thread_sem_get())
#define LWIP_NETCONN_THREAD_SEM_FREE()  ((void)0)

typedef unsigned int sys_prot_t;
sys_prot_t sys_arch_protect(void);
void sys_arch_unprotect(sys_prot_t pval);

#endif /* CS11_ARCH_SYS_ARCH_H */
