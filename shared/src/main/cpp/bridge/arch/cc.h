/*
 * lwIP compiler/architecture header (docs/_digest/58 §7).
 *
 * lwIP expects its port to provide arch/cc.h; the contrib port is not vendored, so the types, the packing
 * attributes and the diagnostics live here. lwIP's own debug output goes to logcat instead of stdout,
 * because a head-unit process has no usable stdout.
 */
#ifndef CS11_ARCH_CC_H
#define CS11_ARCH_CC_H

#include <android/log.h>
#include <endian.h>
#include <errno.h>
#include <stdarg.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define CS11_LWIP_LOG_TAG "lwip"

typedef uint8_t u8_t;
typedef int8_t s8_t;
typedef uint16_t u16_t;
typedef int16_t s16_t;
typedef uint32_t u32_t;
typedef int32_t s32_t;
typedef uint64_t u64_t;
typedef int64_t s64_t;
typedef uintptr_t mem_ptr_t;

#define U16_F "hu"
#define S16_F "hd"
#define X16_F "hx"
#define U32_F "u"
#define S32_F "d"
#define X32_F "x"
#define SZT_F "zu"

#define PACK_STRUCT_STRUCT __attribute__((packed))
#define PACK_STRUCT_BEGIN
#define PACK_STRUCT_END
#define PACK_STRUCT_FIELD(x) x

#ifndef BYTE_ORDER
#define BYTE_ORDER LITTLE_ENDIAN
#endif

/*
 * lwIP calls these as `LWIP_PLATFORM_DIAG(("fmt", args))` - the whole printf argument list is the macro
 * argument - so the helper has to be variadic and the macro must expand to `helper x`.
 */
static inline void cs11_lwip_diag(const char *format, ...) {
    va_list args;
    va_start(args, format);
    __android_log_vprint(ANDROID_LOG_INFO, CS11_LWIP_LOG_TAG, format, args);
    va_end(args);
}

static inline void cs11_lwip_fatal(const char *format, ...) {
    va_list args;
    va_start(args, format);
    __android_log_vprint(ANDROID_LOG_FATAL, CS11_LWIP_LOG_TAG, format, args);
    va_end(args);
}

#define LWIP_PLATFORM_DIAG(x) do { cs11_lwip_diag x; } while (0)

#define LWIP_PLATFORM_ASSERT(x)                                          \
    do {                                                                 \
        cs11_lwip_fatal("assert \"%s\" failed at line %d in %s", x, __LINE__, __FILE__); \
        abort();                                                         \
    } while (0)

/* lwIP 2.x uses the platform's errno so that socket calls report the same values as the kernel stack. */
#define LWIP_ERRNO_STDINCLUDE 1

#endif /* CS11_ARCH_CC_H */
