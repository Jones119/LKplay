// Native USB alternate-setting control for the API-19 userspace NCM bridge.
//
// `UsbDeviceConnection.setInterface()` is API 21+, and on API 19 the kernel only updates an
// interface's alternate setting through the usbfs ioctl, which neither Android's Java API nor
// java.net exposes. EasyPlay's legacy build does the same call in native code
// (`LegacyUsbNative.setInterface`, see docs/_digest/58 §5 item 4); a plain
// `controlTransfer(SET_INTERFACE)` is not equivalent, which is why this file exists.
//
// Return value: 0 on success, otherwise a negative errno. The caller keeps the bridge alive and
// retries, so no exception is thrown across the JNI boundary.
#include <jni.h>
#include <errno.h>
#include <string.h>
#include <sys/ioctl.h>
#include <linux/usbdevice_fs.h>

JNIEXPORT jint JNICALL Java_com_shilapi_xcertplay_transport_LegacyUsbNative_setInterface(
        JNIEnv *env, jobject self, jint file_descriptor, jint interface_number, jint alternate_setting) {
    (void) env;
    (void) self;
    if (file_descriptor < 0 || interface_number < 0 || alternate_setting < 0) return -EINVAL;

    struct usbdevfs_setinterface select;
    memset(&select, 0, sizeof(select));
    select.interface = (unsigned int) interface_number;
    select.altsetting = (unsigned int) alternate_setting;
    if (ioctl(file_descriptor, USBDEVFS_SETINTERFACE, &select) == 0) return 0;
    const int select_errno = errno;

    // Some vendor kernels only honour the control-transfer form; keep it as a fallback instead of
    // failing the bring-up on the ioctl alone.
    struct usbdevfs_ctrltransfer control;
    memset(&control, 0, sizeof(control));
    control.bRequestType = 0x01; // host to device, standard request, interface recipient
    control.bRequest = 0x0b;     // SET_INTERFACE
    control.wValue = (__u16) alternate_setting;
    control.wIndex = (__u16) interface_number;
    control.wLength = 0;
    control.timeout = 1000;
    control.data = NULL;
    if (ioctl(file_descriptor, USBDEVFS_CONTROL, &control) == 0) return 0;
    const int control_errno = errno;

    // Both errnos travel back, encoded as ioctl * 1000 + control, so the car log can tell a driver
    // holding the interface (EBUSY) from the phone having re-enumerated mid-sequence (ENODEV/EPIPE):
    // the two need different fixes, and the first car run could only say "could not park".
    return (jint) -(select_errno * 1000 + (control_errno != 0 ? control_errno : 1));
}

// Moves the device to another configuration. Used by the wired teardown to put the iPhone back on a
// non-CarPlay configuration, which is what makes the next connect re-select configuration 6 instead
// of reusing it; the phone only re-arms CarPlay after that transition (docs/_digest, tablet bench
// 2026-10-19). API 21+ has UsbDeviceConnection.setConfiguration(); API 19 has only this ioctl.
JNIEXPORT jint JNICALL Java_com_shilapi_xcertplay_transport_LegacyUsbNative_setConfiguration(
        JNIEnv *env, jobject self, jint file_descriptor, jint configuration) {
    (void) env;
    (void) self;
    if (file_descriptor < 0 || configuration <= 0) return -EINVAL;
    unsigned int value = (unsigned int) configuration;
    if (ioctl(file_descriptor, USBDEVFS_SETCONFIGURATION, &value) == 0) return 0;
    return -errno;
}

// Proves the toolchain and the library on the car before any of it carries traffic.
JNIEXPORT jstring JNICALL Java_com_shilapi_xcertplay_transport_LegacyUsbNative_version(
        JNIEnv *env, jobject self) {
    (void) self;
    return (*env)->NewStringUTF(
            env, "legacy_usb 2 (USBDEVFS_SETINTERFACE + USBDEVFS_CONTROL + USBDEVFS_SETCONFIGURATION)");
}
