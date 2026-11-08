#include <jni.h>
#include <errno.h>
#include <string.h>
#include <sys/ioctl.h>
#include <unistd.h>
#include <linux/usbdevice_fs.h>
#include "geely_usb_configuration.h"

// Diagnostic startup-only OUT transfer. Copy one short packet before waiting, never pin a Java
// array across ioctl. Caller owns this duplicated fd until ioctl returns. No internal replay.
JNIEXPORT jint JNICALL Java_com_shilapi_xcertplay_compat_LegacyUsbNative_writeStartupPacket(
        JNIEnv *env, jobject self, jint fd, jint endpoint, jbyteArray bytes,
        jint offset, jint length, jint timeout_ms) {
    (void) self;
    if (fd < 0) return -EBADF;
    if (bytes == NULL || endpoint < 1 || endpoint > 15 || offset < 0 ||
            length < 1 || length > 511 || timeout_ms < 1 || timeout_ms > 2000) return -EINVAL;
    jsize array_length = (*env)->GetArrayLength(env, bytes);
    if (offset > array_length || length > array_length - offset) return -EINVAL;
    unsigned char packet[511];
    (*env)->GetByteArrayRegion(env, bytes, offset, length, (jbyte *) packet);
    if ((*env)->ExceptionCheck(env)) return -EFAULT;
    struct usbdevfs_bulktransfer request;
    memset(&request, 0, sizeof(request));
    request.ep = (unsigned int) endpoint;
    request.len = (unsigned int) length;
    request.timeout = (unsigned int) timeout_ms;
    request.data = packet;
    int result = ioctl(fd, USBDEVFS_BULK, &request);
    if (result < 0) result = -errno;
    return result;
}

JNIEXPORT jint JNICALL Java_com_shilapi_xcertplay_compat_LegacyUsbNative_duplicate(JNIEnv *env, jobject self, jint fd) {
    (void) env; (void) self;
    if (fd < 0) return -EBADF;
    int owned_fd = dup(fd);
    return owned_fd < 0 ? -errno : owned_fd;
}

JNIEXPORT void JNICALL Java_com_shilapi_xcertplay_compat_LegacyUsbNative_closeOwned(JNIEnv *env, jobject self, jint fd) {
    (void) env; (void) self;
    if (fd >= 0) close(fd);
}

// Separate opt-in entry: the existing configure() contract and other platforms are unchanged.
JNIEXPORT jint JNICALL Java_com_shilapi_xcertplay_compat_LegacyUsbNative_configureGeely(JNIEnv *env, jobject self, jint fd, jint id) {
    (void) env; (void) self;
    if (fd < 0) return -EBADF;
    int owned_fd = dup(fd);
    if (owned_fd < 0) return -errno;
    int result = geely_configure_fd(owned_fd, (unsigned int) id);
    close(owned_fd);
    return result;
}

// Operate only on an already-authorized UsbDeviceConnection fd; never open/close it.
JNIEXPORT jint JNICALL Java_com_shilapi_xcertplay_compat_LegacyUsbNative_configure(JNIEnv *env, jobject self, jint fd, jint id) {
    (void) env; (void) self;
    if (fd < 0) return -EBADF;
    if (id < 1 || id > 255) return -EINVAL;
    unsigned int config = (unsigned int) id;
    int owned_fd = dup(fd);
    if (owned_fd < 0) return -errno;
    int result = ioctl(owned_fd, USBDEVFS_SETCONFIGURATION, &config) == 0 ? 0 : -errno;
    close(owned_fd);
    return result;
}
JNIEXPORT jint JNICALL Java_com_shilapi_xcertplay_compat_LegacyUsbNative_selectAlternate(JNIEnv *env, jobject self, jint fd, jint id, jint alt) {
    (void) env; (void) self;
    if (fd < 0) return -EBADF;
    if (id < 0 || id > 255 || alt < 0 || alt > 255) return -EINVAL;
    struct usbdevfs_setinterface request;
    memset(&request, 0, sizeof(request));
    request.interface = (unsigned int) id;
    request.altsetting = (unsigned int) alt;
    int owned_fd = dup(fd);
    if (owned_fd < 0) return -errno;
    int result = ioctl(owned_fd, USBDEVFS_SETINTERFACE, &request) == 0 ? 0 : -errno;
    close(owned_fd);
    return result;
}
JNIEXPORT jint JNICALL Java_com_shilapi_xcertplay_compat_LegacyUsbNative_claim(JNIEnv *env, jobject self, jint fd, jint id) {
    (void) env; (void) self;
    if (fd < 0) return -EBADF;
    if (id < 0 || id > 255) return -EINVAL;
    unsigned int number = (unsigned int) id;
    int owned_fd = dup(fd);
    if (owned_fd < 0) return -errno;
    int result = ioctl(owned_fd, USBDEVFS_CLAIMINTERFACE, &number) == 0 ? 0 : -errno;
    close(owned_fd);
    return result;
}
