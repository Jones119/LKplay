# lwIP + the CS11 userspace NCM bridge glue (docs/_digest/58 §7).
#
# lwIP is vendored under cpp/lwip (BSD-3-Clause, see its COPYING) and configured by bridge/lwipopts.h.
# This module is included from src/main/jni/Android.mk, which is the project's ndk-build entry point
# (it also builds local_hotspot_radio and legacy_usb).
#
# -Werror is deliberately NOT set: lwIP itself warns, and doc 58 §7.5 records that as a known cost of
# vendoring it. Only our own glue has to stay warning-clean.
#
# NOTE: this module is not referenced by Gradle yet (shared/build.gradle still has the ndk-build block
# commented out) and the three bridge/*.c files land in the next commit, so nothing here builds today.
LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)
LOCAL_MODULE := lwip_bridge
LOCAL_C_INCLUDES := \
    $(LOCAL_PATH)/lwip/src/include \
    $(LOCAL_PATH)/bridge
LOCAL_SRC_FILES := \
    $(patsubst $(LOCAL_PATH)/%,%,$(wildcard $(LOCAL_PATH)/lwip/src/core/*.c)) \
    $(patsubst $(LOCAL_PATH)/%,%,$(wildcard $(LOCAL_PATH)/lwip/src/core/ipv6/*.c)) \
    $(patsubst $(LOCAL_PATH)/%,%,$(wildcard $(LOCAL_PATH)/lwip/src/api/*.c)) \
    lwip/src/netif/ethernet.c \
    bridge/sys_arch.c \
    bridge/cs11_netif.c \
    bridge/cs11_lwip_jni.c
LOCAL_CFLAGS := -Wall -Wextra -Wno-unused-parameter -Wno-sign-compare -Wno-format
LOCAL_LDLIBS := -llog
include $(BUILD_SHARED_LIBRARY)
