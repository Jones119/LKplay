/*
 * Host stand-in for <endian.h>.
 *
 * `arch/cc.h` asks for the platform endianness header; the Android NDK has it, MSYS2/UCRT does not.
 * The host is little-endian, and `cc.h` only needs the constants.
 */
#ifndef CS11_NATIVE_TEST_ENDIAN_H
#define CS11_NATIVE_TEST_ENDIAN_H

#ifndef LITTLE_ENDIAN
#define LITTLE_ENDIAN 1234
#endif
#ifndef BIG_ENDIAN
#define BIG_ENDIAN 4321
#endif
#ifndef BYTE_ORDER
#define BYTE_ORDER LITTLE_ENDIAN
#endif

#define __LITTLE_ENDIAN LITTLE_ENDIAN
#define __BIG_ENDIAN BIG_ENDIAN
#define __BYTE_ORDER BYTE_ORDER

#endif /* CS11_NATIVE_TEST_ENDIAN_H */
