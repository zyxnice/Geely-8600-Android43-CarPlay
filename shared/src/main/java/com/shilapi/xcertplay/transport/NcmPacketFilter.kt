package com.shilapi.xcertplay.transport

import android.hardware.usb.UsbDeviceConnection
import android.util.Log

/** Optional CDC receive filter setup for the isolated API18 connection-start trial. */
internal object NcmPacketFilter {
    /** Null means skipped or an exception fallback; zero is a successful no-data request. */
    fun configure(connection: UsbDeviceConnection, controlInterfaceId: Int): Int? {
        return try {
            if (!supportsFilter(connection.rawDescriptors, controlInterfaceId)) return null
            // Directed + broadcast + all multicast, matching the IPv6 bridge's receive needs.
            connection.controlTransfer(0x21, 0x43, 0x0e, controlInterfaceId, null, 0, 500)
        } catch (error: RuntimeException) {
            // This capability trial must not invalidate an otherwise usable USB connection.
            Log.w(IphoneCarPlayConfiguration.TAG, "ncm optional packet filter failed", error)
            null
        }
    }

    /** Raw descriptors may contain several configurations; conflicting capabilities are skipped. */
    internal fun supportsFilter(raw: ByteArray, controlInterfaceId: Int): Boolean {
        var offset = 0
        var matchingControl = false
        var blockHasCapability = false
        val capabilities = HashSet<Int>()
        while (offset + 2 <= raw.size) {
            val length = raw[offset].toInt() and 0xff
            val type = raw[offset + 1].toInt() and 0xff
            if (length < 2 || offset + length > raw.size) return false
            if (type == 2 || type == 4) {
                if (matchingControl && !blockHasCapability) return false
                matchingControl = false
                blockHasCapability = false
            }
            if (type == 4) {
                matchingControl = length >= 9 &&
                    (raw[offset + 2].toInt() and 0xff) == controlInterfaceId &&
                    (raw[offset + 5].toInt() and 0xff) == 2 &&
                    (raw[offset + 6].toInt() and 0xff) == 0x0d
            } else if (matchingControl && type == 0x24 && length >= 6 &&
                (raw[offset + 2].toInt() and 0xff) == 0x1a
            ) {
                if (blockHasCapability) return false
                capabilities.add(raw[offset + 5].toInt() and 0xff)
                blockHasCapability = true
            }
            offset += length
        }
        return offset == raw.size && (!matchingControl || blockHasCapability) &&
            capabilities.size == 1 && (capabilities.single() and 1) != 0
    }
}
