# Native build for the CS11 head unit (docs/_digest/58 §7).
#
# Three shared libraries come out of here:
#   lwip_bridge          - vendored lwIP 2.2.0 plus our pthreads port, netif and JNI glue
#   local_hotspot_radio  - the AP interface reader the wireless path uses
#   legacy_usb           - the usbfs altsetting ioctl the API-19 userspace NCM bridge needs
#
# CMake rather than ndk-build because AGP passes APP_BUILD_SCRIPT without NDK_PROJECT_PATH, which
# ndk-build r25 refuses to configure ("Could not find application project directory"), and AGP's
# ndkBuild block has no way to add it. The NDK is r25c (the newest whose platforms.json still lists
# API 19); shared/build.gradle pins both versions.
cmake_minimum_required(VERSION 3.22.1)
project(diplay_native C)

set(LWIP_DIR ${CMAKE_CURRENT_SOURCE_DIR}/lwip)
set(BRIDGE_DIR ${CMAKE_CURRENT_SOURCE_DIR}/bridge)
set(JNI_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../jni)

# Vendored lwIP: globbed because it is a snapshot tree we do not hand-maintain (CONFIGURE_DEPENDS keeps
# the build honest if a file is ever added). IPv4 sources are deliberately absent - lwipopts.h compiles
# that half out and the link is IPv6-only.
file(GLOB LWIP_CORE_SOURCES CONFIGURE_DEPENDS
    ${LWIP_DIR}/src/core/*.c
    ${LWIP_DIR}/src/core/ipv6/*.c
    ${LWIP_DIR}/src/api/*.c)
list(APPEND LWIP_CORE_SOURCES ${LWIP_DIR}/src/netif/ethernet.c)

add_library(lwip_bridge SHARED
    ${LWIP_CORE_SOURCES}
    ${BRIDGE_DIR}/sys_arch.c
    ${BRIDGE_DIR}/cs11_netif.c
    ${BRIDGE_DIR}/cs11_lwip_jni.c)
target_include_directories(lwip_bridge PRIVATE ${LWIP_DIR}/src/include ${BRIDGE_DIR})
# -Werror is intentionally absent: lwIP warns, and doc 58 §7.5 accepts that as the cost of vendoring it.
target_compile_options(lwip_bridge PRIVATE
    -Wall -Wextra -Wno-unused-parameter -Wno-sign-compare -Wno-format -Wno-format-security)
target_link_libraries(lwip_bridge log)

add_library(local_hotspot_radio SHARED ${JNI_DIR}/local_hotspot_radio.c)
target_compile_options(local_hotspot_radio PRIVATE -Wall -Wextra -Werror)

add_library(legacy_usb SHARED ${JNI_DIR}/legacy_usb.c)
target_compile_options(legacy_usb PRIVATE -Wall -Wextra -Werror)
