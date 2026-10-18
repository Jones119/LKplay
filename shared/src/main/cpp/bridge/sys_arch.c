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

/* ── mutexes ──────────────────────────────────────────────────────────────────────────────────── */

err_t sys_mutex_new(sys_mutex_t *mutex) {
    pthread_mutex_t *m = (pthread_mutex_t *)mem_malloc(sizeof(pthread_mutex_t));
    if (m == NULL) {
        SYS_STATS_INC_USED(mutex);
        return ERR_MEM;
    }
    if (pthread_mutex_init(m, NULL) != 0) {
        mem_free(m);
        return ERR_VAL;
    }
    *mutex = m;
    SYS_STATS_INC_USED(mutex);
    return ERR_OK;
}

void sys_mutex_free(sys_mutex_t *mutex) {
    pthread_mutex_destroy(*mutex);
    mem_free(*mutex);
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
    sys_sem_impl *s = (sys_sem_impl *)mem_malloc(sizeof(sys_sem_impl));
    if (s == NULL) {
        SYS_STATS_INC(sem.err);
        return ERR_MEM;
    }
    if (pthread_mutex_init(&s->mutex, NULL) != 0 || pthread_cond_init(&s->cond, NULL) != 0) {
        mem_free(s);
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
    mem_free(s);
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

u32_t sys_arch_sem_wait(sys_sem_t *sem, u32_t timeout) {
    sys_sem_impl *s = *sem;
    u32_t waited = 0;
    pthread_mutex_lock(&s->mutex);
    while (s->count == 0) {
        if (timeout == 0) {
            pthread_cond_wait(&s->cond, &s->mutex);
        } else {
            struct timespec deadline;
            clock_gettime(CLOCK_REALTIME, &deadline);
            u32_t seconds = (timeout + waited) / 1000u;
            u32_t nanos = ((timeout + waited) % 1000u) * 1000000u;
            deadline.tv_sec += (time_t)seconds;
            deadline.tv_nsec += (long)nanos;
            if (deadline.tv_nsec >= 1000000000L) {
                deadline.tv_sec += 1;
                deadline.tv_nsec -= 1000000000L;
            }
            if (pthread_cond_timedwait(&s->cond, &s->mutex, &deadline) == ETIMEDOUT) {
                pthread_mutex_unlock(&s->mutex);
                return SYS_ARCH_TIMEOUT;
            }
        }
        waited = timeout; /* the timed wait has already consumed the whole budget */
    }
    s->count--;
    pthread_mutex_unlock(&s->mutex);
    return waited;
}

int sys_arch_sem_valid_helper_unused; /* sys_sem_valid/set_invalid come from arch/sys_arch.h */

/* ── mailboxes ────────────────────────────────────────────────────────────────────────────────── */

typedef struct {
    pthread_mutex_t mutex;
    pthread_cond_t not_empty;
    pthread_cond_t not_full;
    void **slots;
    size_t capacity;
    size_t head;
    size_t count;
} sys_mbox_impl;
err_t sys_mbox_new(sys_mbox_t *mbox, int size) {
    if (size <= 0) {
        size = 32;
    }
    sys_mbox_impl *m = (sys_mbox_impl *)mem_malloc(sizeof(sys_mbox_impl));
    if (m == NULL) {
        SYS_STATS_INC(mbox.err);
        return ERR_MEM;
    }
    m->slots = (void **)mem_malloc(sizeof(void *) * (size_t)size);
    if (m->slots == NULL) {
        mem_free(m);
        SYS_STATS_INC(mbox.err);
        return ERR_MEM;
    }
    memset(m->slots, 0, sizeof(void *) * (size_t)size);
    if (pthread_mutex_init(&m->mutex, NULL) != 0 ||
        pthread_cond_init(&m->not_empty, NULL) != 0 ||
        pthread_cond_init(&m->not_full, NULL) != 0) {
        mem_free(m->slots);
        mem_free(m);
        return ERR_VAL;
    }
    m->capacity = (size_t)size;
    m->head = 0;
    m->count = 0;
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
     * Wake anyone blocked on this mailbox first. A thread parked in sys_mbox_post() on `not_full` (or in
     * sys_arch_mbox_fetch on `not_empty`) resumes inside a condition variable and mutex that this function is
     * about to destroy, which is undefined behaviour - and the teardown path does free mailboxes while pump
     * threads are still running. */
    pthread_mutex_lock(&m->mutex);
    pthread_cond_broadcast(&m->not_empty);
    pthread_cond_broadcast(&m->not_full);
    pthread_mutex_unlock(&m->mutex);
    pthread_mutex_destroy(&m->mutex);
    pthread_cond_destroy(&m->not_empty);
    pthread_cond_destroy(&m->not_full);
    mem_free(m->slots);
    mem_free(m);
    *mbox = NULL;
    SYS_STATS_DEC(mbox.used);
}

void sys_mbox_post(sys_mbox_t *mbox, void *msg) {
    sys_mbox_impl *m = *mbox;
    pthread_mutex_lock(&m->mutex);
    while (m->count == m->capacity) {
        pthread_cond_wait(&m->not_full, &m->mutex);
    }
    m->slots[(m->head + m->count) % m->capacity] = msg;
    m->count++;
    pthread_cond_signal(&m->not_empty);
    pthread_mutex_unlock(&m->mutex);
}

err_t sys_mbox_trypost(sys_mbox_t *mbox, void *msg) {
    sys_mbox_impl *m = *mbox;
    pthread_mutex_lock(&m->mutex);
    if (m->count == m->capacity) {
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
    pthread_mutex_lock(&m->mutex);
    while (m->count == 0) {
        if (timeout == 0) {
            pthread_cond_wait(&m->not_empty, &m->mutex);
        } else {
            struct timespec deadline;
            clock_gettime(CLOCK_REALTIME, &deadline);
            u32_t seconds = (timeout + waited) / 1000u;
            u32_t nanos = ((timeout + waited) % 1000u) * 1000000u;
            deadline.tv_sec += (time_t)seconds;
            deadline.tv_nsec += (long)nanos;
            if (deadline.tv_nsec >= 1000000000L) {
                deadline.tv_sec += 1;
                deadline.tv_nsec -= 1000000000L;
            }
            if (pthread_cond_timedwait(&m->not_empty, &m->mutex, &deadline) == ETIMEDOUT) {
                pthread_mutex_unlock(&m->mutex);
                if (msg != NULL) {
                    *msg = NULL;
                }
                return SYS_ARCH_TIMEOUT;
            }
        }
        waited = timeout;
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
    if (m->count == 0) {
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

static void *cs11_thread_entry(void *raw) {
    struct cs11_thread_arg arg = *(struct cs11_thread_arg *)raw;
    mem_free(raw);
    arg.function(arg.argument);
    return NULL;
}

sys_thread_t sys_thread_new(const char *name, lwip_thread_fn thread, void *arg, int stacksize, int prio) {
    (void)stacksize;
    (void)prio;
    struct cs11_thread_arg *boxed = (struct cs11_thread_arg *)mem_malloc(sizeof(struct cs11_thread_arg));
    if (boxed == NULL) {
        return 0;
    }
    boxed->function = thread;
    boxed->argument = arg;

    pthread_t handle;
    if (pthread_create(&handle, NULL, cs11_thread_entry, boxed) != 0) {
        mem_free(boxed);
        return 0;
    }
    if (name != NULL) {
        /* Best effort: Android shows this in /proc/<pid>/task/<tid>/comm like any other thread. */
        pthread_setname_np(handle, name);
    }
    return (sys_thread_t)handle;
}
