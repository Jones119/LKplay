/*
 * Forced-include prelude for the host build (see CMakeLists.txt).
 *
 * MSYS2/UCRT does not give lwIP the POSIX typedefs its sockets header expects, while the Android NDK
 * does. Rather than edit the vendored tree, the few types it needs are supplied here before anything
 * else is compiled.
 */
#ifndef CS11_HOST_PRELUDE_H
#define CS11_HOST_PRELUDE_H

#include <stddef.h>
#include <stdint.h>

#if !defined(_SSIZE_T_DEFINED) && !defined(ssize_t)
#define _SSIZE_T_DEFINED 1
typedef long ssize_t;
#endif

/* lwIP defines its own socklen_t unless one of these is set; a duplicate typedef is not portable, so
 * tell it the type already exists. */
#ifndef socklen_t
#define SOCKLEN_T_DEFINED 1
typedef unsigned int socklen_t;
#endif

#endif /* CS11_HOST_PRELUDE_H */
