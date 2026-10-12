LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)
LOCAL_MODULE := local_hotspot_radio
LOCAL_SRC_FILES := local_hotspot_radio.c
LOCAL_CFLAGS := -Wall -Wextra -Werror
include $(BUILD_SHARED_LIBRARY)

# API-19 userspace NCM bridge: `UsbDeviceConnection.setInterface()` is API 21+ and nothing in the
# Java API performs the usbfs altsetting ioctl (docs/_digest/58 §5 item 4).
include $(CLEAR_VARS)
LOCAL_MODULE := legacy_usb
LOCAL_SRC_FILES := legacy_usb.c
LOCAL_CFLAGS := -Wall -Wextra -Werror
include $(BUILD_SHARED_LIBRARY)
