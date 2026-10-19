/*
 * Host stand-in for <android/log.h>.
 *
 * The shipped `arch/cc.h` sends lwIP diagnostics to logcat. The host tests compile the same bridge code
 * on the PC, so this shim is put ahead of any real Android header on the include path and writes to
 * stderr instead. Nothing here reaches the APK.
 */
#ifndef CS11_NATIVE_TEST_ANDROID_LOG_H
#define CS11_NATIVE_TEST_ANDROID_LOG_H

#include <stdarg.h>
#include <stdio.h>

typedef enum {
    ANDROID_LOG_UNKNOWN = 0,
    ANDROID_LOG_DEFAULT,
    ANDROID_LOG_VERBOSE,
    ANDROID_LOG_DEBUG,
    ANDROID_LOG_INFO,
    ANDROID_LOG_WARN,
    ANDROID_LOG_ERROR,
    ANDROID_LOG_FATAL,
    ANDROID_LOG_SILENT
} android_LogPriority;

static inline int __android_log_vprint(int prio, const char *tag, const char *fmt, va_list ap) {
    (void)prio;
    fprintf(stderr, "[%s] ", tag);
    vfprintf(stderr, fmt, ap);
    fputc('\n', stderr);
    return 0;
}

static inline int __android_log_print(int prio, const char *tag, const char *fmt, ...) {
    va_list ap;
    va_start(ap, fmt);
    const int result = __android_log_vprint(prio, tag, fmt, ap);
    va_end(ap);
    return result;
}

#endif /* CS11_NATIVE_TEST_ANDROID_LOG_H */
