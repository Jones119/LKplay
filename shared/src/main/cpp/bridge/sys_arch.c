/*
 * lwIP's OS abstraction on pthreads (docs/_digest/58 §7).
 *
 * lwIP runs with NO_SYS=0, so it needs semaphores, mailboxes and threads. Android has pthreads but no
 * lwIP port, and the contrib port is not vendored, so the four primitives are implemented here. Mailboxes
 * are a mutex plus two condition variables so that a blocked fetch can be woken by a post and a blocked
 * post can be woken by a fetch (lwIP needs both directions once a mailbox is full).
 *
 * Called only from lwIP's own thread (tcpip_thread) and from the JVM threads that enter through the
 * sockets API, which is what LWIP_NETCONN expects.
 */
#include "lwip/opt.h"
#include "lwip/sys.h"
#include "lwip/mem.h"
#include "lwip/stats.h"
#include "lwip/tcpip.h"

#include <errno.h>
#include <pthread.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>
#ifdef __ANDROID__
/* Android-only: the native-tests host build (MSYS2/UCRT) has neither header. */
#include <ctype.h>
#include <strings.h>
#include <sys/resource.h>
#include <sys/system_properties.h>
#endif

/* ── time ─────────────────────────────────────────────────────────────────────────────────────── */

u32_t sys_now(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (u32_t)((u64_t)ts.tv_sec * 1000u + (u64_t)ts.tv_nsec / 1000000u);
}

u32_t sys_jiffies(void) {
    return sys_now();
}

void sys_init(void) {
}

/* ── lightweight protection ──────────────────────────────────────────────────────────────────── */

/*
 * The critical sections lwIP wraps around memp/mem (SYS_LIGHTWEIGHT_PROT). The RX pump allocates a
 * tcpip message from the same pool the tcpip thread frees from, so without this the pool's free list is
 * shared unguarded; the host test (native-tests) reproduced the corruption on its first storm run.
 *
 * Recursive on purpose: lwIP documents that SYS_ARCH_PROTECT may be entered while already protected.
 */
static pthread_mutex_t g_protect_mutex;
static pthread_once_t g_protect_once = PTHREAD_ONCE_INIT;

static void create_protect_mutex(void) {
    pthread_mutexattr_t attributes;
    pthread_mutexattr_init(&attributes);
    pthread_mutexattr_settype(&attributes, PTHREAD_MUTEX_RECURSIVE);
    pthread_mutex_init(&g_protect_mutex, &attributes);
    pthread_mutexattr_destroy(&attributes);
}

sys_prot_t sys_arch_protect(void) {
    pthread_once(&g_protect_once, create_protect_mutex);
    pthread_mutex_lock(&g_protect_mutex);
    return 0;
}

void sys_arch_unprotect(sys_prot_t pval) {
    (void)pval;
    pthread_mutex_unlock(&g_protect_mutex);
}

/* ── mutexes ──────────────────────────────────────────────────────────────────────────────────── */

/*
 * These primitives allocate with the C library, not with `mem_malloc`.
 *
 * lwIP's own heap is initialised by `mem_init()`, which creates `mem_mutex` through this very layer, and
 * `mem_malloc()` locks that mutex. Allocating the primitives from that heap is therefore circular: with a
 * real lwIP heap (MEM_LIBC_MALLOC=0, as the host tests use) the first `sys_mutex_new()` locks a mutex that
 * does not exist yet. The APK happens to mask it because MEM_LIBC_MALLOC=1 makes `mem_malloc` plain
 * malloc, but the port should not depend on that.
 */
err_t sys_mutex_new(sys_mutex_t *mutex) {
    pthread_mutex_t *m = (pthread_mutex_t *)malloc(sizeof(pthread_mutex_t));
    if (m == NULL) {
        SYS_STATS_INC_USED(mutex);
        return ERR_MEM;
    }
    if (pthread_mutex_init(m, NULL) != 0) {
        free(m);
        return ERR_VAL;
    }
    *mutex = m;
    SYS_STATS_INC_USED(mutex);
    return ERR_OK;
}

void sys_mutex_free(sys_mutex_t *mutex) {
    pthread_mutex_destroy(*mutex);
    free(*mutex);
    *mutex = NULL;
    SYS_STATS_DEC(mutex.used);
}

void sys_mutex_lock(sys_mutex_t *mutex) {
    pthread_mutex_lock(*mutex);
}

void sys_mutex_unlock(sys_mutex_t *mutex) {
    pthread_mutex_unlock(*mutex);
}

/* ── semaphores ───────────────────────────────────────────────────────────────────────────────── */

typedef struct {
    pthread_mutex_t mutex;
    pthread_cond_t cond;
    int count;
} sys_sem_impl;

err_t sys_sem_new(sys_sem_t *sem, u8_t count) {
    sys_sem_impl *s = (sys_sem_impl *)malloc(sizeof(sys_sem_impl));
    if (s == NULL) {
        SYS_STATS_INC(sem.err);
        return ERR_MEM;
    }
    if (pthread_mutex_init(&s->mutex, NULL) != 0 || pthread_cond_init(&s->cond, NULL) != 0) {
        free(s);
        return ERR_VAL;
    }
    s->count = count;
    *sem = s;
    SYS_STATS_INC_USED(sem);
    return ERR_OK;
}

void sys_sem_free(sys_sem_t *sem) {
    sys_sem_impl *s = *sem;
    if (s == NULL) {
        return;
    }
    pthread_cond_destroy(&s->cond);
    pthread_mutex_destroy(&s->mutex);
    free(s);
    *sem = NULL;
    SYS_STATS_DEC(sem.used);
}

void sys_sem_signal(sys_sem_t *sem) {
    sys_sem_impl *s = *sem;
    pthread_mutex_lock(&s->mutex);
    s->count++;
    pthread_cond_signal(&s->cond);
    pthread_mutex_unlock(&s->mutex);
}

/* ── per-thread API semaphore (LWIP_NETCONN_SEM_PER_THREAD) ──────────────────────────────────── */

/*
 * One API-completion semaphore per thread instead of one per netconn. Two threads that use the same
 * socket then wait on separate semaphores, so neither can consume the other's signal and return while
 * its stack-allocated tcpip_msg is still queued - the corruption caught on 2026-10-20
 * (docs/_digest/101).
 *
 * The slot lives in thread-local storage; its destructor frees the semaphore when the thread exits.
 */
static pthread_key_t g_thread_sem_key;
static pthread_once_t g_thread_sem_once = PTHREAD_ONCE_INIT;

static void destroy_thread_sem(void *value) {
    sys_sem_t *slot = (sys_sem_t *)value;
    if (slot != NULL) {
        if (*slot != NULL) {
            sys_sem_t sem = *slot;
            sys_sem_free(&sem);
        }
        free(slot);
    }
}

static void create_thread_sem_key(void) {
    (void)pthread_key_create(&g_thread_sem_key, destroy_thread_sem);
}

sys_sem_t *sys_thread_sem_get(void) {
    pthread_once(&g_thread_sem_once, create_thread_sem_key);
    sys_sem_t *slot = (sys_sem_t *)pthread_getspecific(g_thread_sem_key);
    if (slot != NULL) {
        return slot;
    }
    slot = (sys_sem_t *)calloc(1, sizeof(sys_sem_t));
    if (slot == NULL) {
        return NULL;
    }
    sys_sem_t sem = NULL;
    if (sys_sem_new(&sem, 0) != ERR_OK) {
        free(slot);
        return NULL;
    }
    *slot = sem;
    if (pthread_setspecific(g_thread_sem_key, slot) != 0) {
        sys_sem_t owned = sem;
        sys_sem_free(&owned);
        free(slot);
        return NULL;
    }
    return slot;
}

u32_t sys_arch_sem_wait(sys_sem_t *sem, u32_t timeout) {
    sys_sem_impl *s = *sem;
    u32_t waited = 0;
    struct timespec deadline;
    struct timespec started;
    int deadline_set = 0;
    pthread_mutex_lock(&s->mutex);
    while (s->count == 0) {
        if (timeout == 0) {
            pthread_cond_wait(&s->cond, &s->mutex);
        } else {
            /* One absolute deadline for the whole call. Recomputing it from `waited` after a spurious
             * wake-up used to double the budget (2x, then 3x), so a declared 100 ms wait could block
             * for 300 ms and lwIP's timeouts ran long. */
            if (!deadline_set) {
                clock_gettime(CLOCK_MONOTONIC, &started);
                clock_gettime(CLOCK_REALTIME, &deadline);
                deadline.tv_sec += (time_t)(timeout / 1000u);
                deadline.tv_nsec += (long)(timeout % 1000u) * 1000000L;
                if (deadline.tv_nsec >= 1000000000L) {
                    deadline.tv_sec += 1;
                    deadline.tv_nsec -= 1000000000L;
                }
                deadline_set = 1;
            }
            if (pthread_cond_timedwait(&s->cond, &s->mutex, &deadline) == ETIMEDOUT) {
                pthread_mutex_unlock(&s->mutex);
                return SYS_ARCH_TIMEOUT;
            }
        }
    }
    s->count--;
    if (deadline_set) {
        struct timespec now;
        clock_gettime(CLOCK_MONOTONIC, &now);
        long long elapsed = (long long)(now.tv_sec - started.tv_sec) * 1000LL +
                            (now.tv_nsec - started.tv_nsec) / 1000000L;
        waited = elapsed > 0 ? (u32_t)elapsed : 0;
        if (waited > timeout) waited = timeout;
    }
    pthread_mutex_unlock(&s->mutex);
    return waited;
}

int sys_arch_sem_valid_helper_unused; /* sys_sem_valid/set_invalid come from arch/sys_arch.h */

/* ── mailboxes ────────────────────────────────────────────────────────────────────────────────── */

typedef struct {
    pthread_mutex_t mutex;
    pthread_cond_t not_empty;
    pthread_cond_t not_full;
    /** Signalled whenever a parked waiter leaves, so sys_mbox_free can wait for that before destroying. */
    pthread_cond_t drained;
    void **slots;
    size_t capacity;
    size_t head;
    size_t count;
    /** Set by sys_mbox_free: a waiter that wakes must leave instead of waiting again. */
    int closing;
    /** Threads currently parked in pthread_cond_wait on this mailbox. */
    int waiters;
} sys_mbox_impl;
err_t sys_mbox_new(sys_mbox_t *mbox, int size) {
    if (size <= 0) {
        size = 32;
    }
    sys_mbox_impl *m = (sys_mbox_impl *)malloc(sizeof(sys_mbox_impl));
    if (m == NULL) {
        SYS_STATS_INC(mbox.err);
        return ERR_MEM;
    }
    m->slots = (void **)malloc(sizeof(void *) * (size_t)size);
    if (m->slots == NULL) {
        free(m);
        SYS_STATS_INC(mbox.err);
        return ERR_MEM;
    }
    memset(m->slots, 0, sizeof(void *) * (size_t)size);
    if (pthread_mutex_init(&m->mutex, NULL) != 0 ||
        pthread_cond_init(&m->not_empty, NULL) != 0 ||
        pthread_cond_init(&m->not_full, NULL) != 0 ||
        pthread_cond_init(&m->drained, NULL) != 0) {
        free(m->slots);
        free(m);
        return ERR_VAL;
    }
    m->capacity = (size_t)size;
    m->head = 0;
    m->count = 0;
    m->closing = 0;
    m->waiters = 0;
    *mbox = m;
    SYS_STATS_INC_USED(mbox);
    return ERR_OK;
}

void sys_mbox_free(sys_mbox_t *mbox) {
    sys_mbox_impl *m = *mbox;
    if (m == NULL) {
        return;
    }
    /* lwIP requires an empty mailbox here; a non-empty one is a leak in its own bookkeeping.
     *
     * A wake-up alone is not enough: a thread parked in pthread_cond_wait resumes only after it has
     * re-acquired the mutex, and this function is about to destroy that mutex and the condition
     * variables. So the mailbox is marked closing, every waiter is woken, and free waits until they
     * have actually left (they see `closing` and stop waiting instead of parking again). Destroying
     * while someone is still inside was undefined behaviour, and the teardown path does free mailboxes
     * (per-netconn ones) while pump threads are still posting. */
    pthread_mutex_lock(&m->mutex);
    m->closing = 1;
    pthread_cond_broadcast(&m->not_empty);
    pthread_cond_broadcast(&m->not_full);
    while (m->waiters > 0) {
        pthread_cond_wait(&m->drained, &m->mutex);
    }
    pthread_mutex_unlock(&m->mutex);
    pthread_mutex_destroy(&m->mutex);
    pthread_cond_destroy(&m->not_empty);
    pthread_cond_destroy(&m->not_full);
    pthread_cond_destroy(&m->drained);
    free(m->slots);
    free(m);
    *mbox = NULL;
    SYS_STATS_DEC(mbox.used);
}

void sys_mbox_post(sys_mbox_t *mbox, void *msg) {
    sys_mbox_impl *m = *mbox;
    pthread_mutex_lock(&m->mutex);
    while (m->count == m->capacity && !m->closing) {
        m->waiters++;
        pthread_cond_wait(&m->not_full, &m->mutex);
        m->waiters--;
        if (m->waiters == 0) {
            pthread_cond_signal(&m->drained);
        }
    }
    if (m->closing) {
        pthread_mutex_unlock(&m->mutex);
        return;
    }
    m->slots[(m->head + m->count) % m->capacity] = msg;
    m->count++;
    pthread_cond_signal(&m->not_empty);
    pthread_mutex_unlock(&m->mutex);
}

err_t sys_mbox_trypost(sys_mbox_t *mbox, void *msg) {
    sys_mbox_impl *m = *mbox;
    pthread_mutex_lock(&m->mutex);
    if (m->closing || m->count == m->capacity) {
        pthread_mutex_unlock(&m->mutex);
        SYS_STATS_INC(mbox.err);
        return ERR_MEM;
    }
    m->slots[(m->head + m->count) % m->capacity] = msg;
    m->count++;
    pthread_cond_signal(&m->not_empty);
    pthread_mutex_unlock(&m->mutex);
    return ERR_OK;
}

err_t sys_mbox_trypost_fromisr(sys_mbox_t *mbox, void *msg) {
    return sys_mbox_trypost(mbox, msg);
}

u32_t sys_arch_mbox_fetch(sys_mbox_t *mbox, void **msg, u32_t timeout) {
    sys_mbox_impl *m = *mbox;
    u32_t waited = 0;
    struct timespec deadline;
    struct timespec started;
    int deadline_set = 0;
    pthread_mutex_lock(&m->mutex);
    while (m->count == 0 && !m->closing) {
        if (timeout == 0) {
            m->waiters++;
            pthread_cond_wait(&m->not_empty, &m->mutex);
            m->waiters--;
            if (m->waiters == 0) {
                pthread_cond_signal(&m->drained);
            }
        } else {
            /* Fixed absolute deadline, same reason as sys_arch_sem_wait: a spurious wake-up must not
             * extend the caller's budget. */
            if (!deadline_set) {
                clock_gettime(CLOCK_MONOTONIC, &started);
                clock_gettime(CLOCK_REALTIME, &deadline);
                deadline.tv_sec += (time_t)(timeout / 1000u);
                deadline.tv_nsec += (long)(timeout % 1000u) * 1000000L;
                if (deadline.tv_nsec >= 1000000000L) {
                    deadline.tv_sec += 1;
                    deadline.tv_nsec -= 1000000000L;
                }
                deadline_set = 1;
            }
            m->waiters++;
            const int timed = pthread_cond_timedwait(&m->not_empty, &m->mutex, &deadline);
            m->waiters--;
            if (m->waiters == 0) {
                pthread_cond_signal(&m->drained);
            }
            if (timed == ETIMEDOUT) {
                pthread_mutex_unlock(&m->mutex);
                if (msg != NULL) {
                    *msg = NULL;
                }
                return SYS_ARCH_TIMEOUT;
            }
        }
    }
    if (m->closing) {
        pthread_mutex_unlock(&m->mutex);
        if (msg != NULL) {
            *msg = NULL;
        }
        return SYS_ARCH_TIMEOUT;
    }
    if (deadline_set) {
        struct timespec now;
        clock_gettime(CLOCK_MONOTONIC, &now);
        long long elapsed = (long long)(now.tv_sec - started.tv_sec) * 1000LL +
                            (now.tv_nsec - started.tv_nsec) / 1000000L;
        waited = elapsed > 0 ? (u32_t)elapsed : 0;
        if (waited > timeout) waited = timeout;
    }
    void *value = m->slots[m->head];
    m->head = (m->head + 1) % m->capacity;
    m->count--;
    pthread_cond_signal(&m->not_full);
    pthread_mutex_unlock(&m->mutex);
    if (msg != NULL) {
        *msg = value;
    }
    return waited;
}

u32_t sys_arch_mbox_tryfetch(sys_mbox_t *mbox, void **msg) {
    sys_mbox_impl *m = *mbox;
    pthread_mutex_lock(&m->mutex);
    if (m->closing || m->count == 0) {
        pthread_mutex_unlock(&m->mutex);
        return SYS_MBOX_EMPTY;
    }
    void *value = m->slots[m->head];
    m->head = (m->head + 1) % m->capacity;
    m->count--;
    pthread_cond_signal(&m->not_full);
    pthread_mutex_unlock(&m->mutex);
    if (msg != NULL) {
        *msg = value;
    }
    return 0;
}

/* sys_mbox_valid/set_invalid come from arch/sys_arch.h */

/* ── threads ──────────────────────────────────────────────────────────────────────────────────── */

struct cs11_thread_arg {
    lwip_thread_fn function;
    void *argument;
};

/*
 * Nice value for lwIP's threads (only tcpip_thread exists).
 *
 * sys_thread_new used to ignore `prio`, so lwip-tcpip ran at nice 0 - below the video threads (-8) and
 * the audio threads (-19) that consume what it delivers, even though every inbound video/audio packet
 * and every outbound ACK and touch report passes through it. Under the 60 fps load (doc 102) that is a
 * priority inversion in the middle of the wired path. Default -16 (ANDROID_PRIORITY_AUDIO); the same
 * property as the Kotlin pumps (TransportThreadPriority) selects -8 or 0 for an A/B without a rebuild.
 */
#ifdef __ANDROID__
static int cs11_transport_nice(void) {
    int nice = -16;
    char value[PROP_VALUE_MAX];
    if (__system_property_get("debug.diplay.transport_priority", value) > 0) {
        /* Same parsing as TransportThreadPriority.sanitize: surrounding blanks and case are ignored. */
        char *start = value;
        while (*start != '\0' && isspace((unsigned char)*start)) {
            start++;
        }
        char *end = start + strlen(start);
        while (end > start && isspace((unsigned char)end[-1])) {
            *--end = '\0';
        }
        if (strcasecmp(start, "video") == 0 || strcmp(start, "-8") == 0) {
            nice = -8;
        } else if (strcasecmp(start, "normal") == 0 || strcmp(start, "0") == 0) {
            nice = 0;
        }
    }
    return nice;
}
#endif

static void *cs11_thread_entry(void *raw) {
    struct cs11_thread_arg arg = *(struct cs11_thread_arg *)raw;
    free(raw);
#ifdef __ANDROID__
    /* Linux nice is per thread; Android's init grants apps RLIMIT_NICE down to -20 (as for audio). */
    (void)setpriority(PRIO_PROCESS, (id_t)gettid(), cs11_transport_nice());
#endif
    arg.function(arg.argument);
    return NULL;
}

sys_thread_t sys_thread_new(const char *name, lwip_thread_fn thread, void *arg, int stacksize, int prio) {
    (void)stacksize;
    (void)prio;
    struct cs11_thread_arg *boxed = (struct cs11_thread_arg *)malloc(sizeof(struct cs11_thread_arg));
    if (boxed == NULL) {
        return 0;
    }
    boxed->function = thread;
    boxed->argument = arg;

    pthread_t handle;
    if (pthread_create(&handle, NULL, cs11_thread_entry, boxed) != 0) {
        free(boxed);
        return 0;
    }
    if (name != NULL) {
        /* Best effort: Android shows this in /proc/<pid>/task/<tid>/comm like any other thread. */
        pthread_setname_np(handle, name);
    }
    return (sys_thread_t)handle;
}
