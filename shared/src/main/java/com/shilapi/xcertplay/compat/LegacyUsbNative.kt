package com.shilapi.xcertplay.compat

/** Linux usbfs operations on the fd Android has already authorized and opened. */
object LegacyUsbNative {
    init { System.loadLibrary("legacy_usb_compat") }
    external fun configure(fd: Int, configurationId: Int): Int
    /** H53/8600 opt-in only: verified 05ac:12a8, config 2 -> 6, snd-usb-audio only. */
    external fun configureGeely(fd: Int, configurationId: Int): Int
    external fun selectAlternate(fd: Int, interfaceId: Int, alternate: Int): Int
    external fun claim(fd: Int, interfaceId: Int): Int
    /** Duplicate under the connection owner's close lock; returns owned fd or negative errno. */
    external fun duplicate(fd: Int): Int
    /** Close only an fd returned by [duplicate], after its outstanding operation has returned. */
    external fun closeOwned(fd: Int)
    /** Diagnostic single USB OUT packet on a caller-owned duplicate: bytes or negative errno.
     * Only startup packets of 1..511 bytes and finite 1..2000ms deadlines are accepted.
     * Never closes the fd or retries. Negative is not proof of zero progress; caller owns replay.
     */
    external fun writeStartupPacket(fd: Int, endpoint: Int, bytes: ByteArray,
        offset: Int, length: Int, timeoutMillis: Int): Int
}
