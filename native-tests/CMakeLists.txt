# Host build of the CS11 userspace lwIP bridge tests.
#
# The same bridge sources the APK builds, plus the vendored lwIP, compiled with the host clang and ASan.
# `native-tests/lwipopts.h` narrows the pools and turns the counters on; `native-tests/shims` stands in
# for <android/log.h>. Nothing here is part of the APK.
cmake_minimum_required(VERSION 3.20)
project(cs11_lwip_host_tests C)

set(REPO_ROOT ${CMAKE_CURRENT_SOURCE_DIR}/..)
set(LWIP_ROOT ${REPO_ROOT}/shared/src/main/cpp/lwip/src)
set(BRIDGE_ROOT ${REPO_ROOT}/shared/src/main/cpp/bridge)

file(GLOB_RECURSE LWIP_SOURCES CONFIGURE_DEPENDS ${LWIP_ROOT}/*.c)
# The apps (httpd, snmp, mqtt, ...) are not compiled into the APK either.
list(FILTER LWIP_SOURCES EXCLUDE REGEX "/apps/")
# SLIP needs a serial port implementation (contrib/ports/*/sio.c) that the APK does not ship either.
list(FILTER LWIP_SOURCES EXCLUDE REGEX "/netif/slipif\\.c$")

add_executable(cs11_lwip_tests
    tests.c
    ${BRIDGE_ROOT}/sys_arch.c
    ${BRIDGE_ROOT}/cs11_netif.c
    ${LWIP_SOURCES}
)

target_include_directories(cs11_lwip_tests PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}          # host lwipopts.h first
    ${CMAKE_CURRENT_SOURCE_DIR}/shims    # android/log.h stand-in
    ${BRIDGE_ROOT}                       # arch/cc.h, arch/sys_arch.h
    ${LWIP_ROOT}/include
)

target_compile_options(cs11_lwip_tests PRIVATE
    -g -O1
    -fsanitize=address -fno-omit-frame-pointer
    -pthread
    -Wall
    # POSIX typedefs MSYS2/UCRT lacks but the NDK provides; see shims/host_prelude.h.
    -include ${CMAKE_CURRENT_SOURCE_DIR}/shims/host_prelude.h
    # lwIP itself is vendored; its warnings are not this project's to fix.
    -Wno-unused-variable -Wno-unused-but-set-variable -Wno-unused-function
)

target_link_options(cs11_lwip_tests PRIVATE
    -fsanitize=address -pthread
)
