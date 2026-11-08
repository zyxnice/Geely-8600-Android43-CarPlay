package com.shilapi.xcertplay.transport

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

/** Exercise the production send entry and startup retry using a borrowed TUN source. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE, shadows = [NcmOutboundConnectionShadow::class])
class NcmOutboundReplayTest {
    @Test fun retryRetainsEncodedBlockWhenBorrowedSourceChangesAndReadyTimeoutDoesNotReplay() {
        val source = ByteArray(40) { it.toByte() }
        val original = source.copyOf()
        val mac = byteArrayOf(1, 2, 3, 4, 5, 6)
        val writes = mutableListOf<ByteArray>()
        NcmOutboundConnectionShadow.transfer = { bytes, offset, length, _ ->
            writes.add(bytes.copyOfRange(offset, offset + length))
            when (writes.size) {
                1 -> { source.fill(0); 0 }
                2 -> length
                else -> 0
            }
        }
        val connection = ReflectionHelpers.callConstructor(UsbDeviceConnection::class.java,
            ClassParameter.from(UsbDevice::class.java, null))
        fun endpoint(address: Int): UsbEndpoint = ReflectionHelpers.callConstructor(
            UsbEndpoint::class.java, ClassParameter.from(Int::class.javaPrimitiveType, address),
            ClassParameter.from(Int::class.javaPrimitiveType, 2),
            ClassParameter.from(Int::class.javaPrimitiveType, 512),
            ClassParameter.from(Int::class.javaPrimitiveType, 0))
        try {
            NcmUsbBridge(connection, endpoint(4), endpoint(0x85), null, emptyList(), null).use { bridge ->
                bridge.sendIpv6(mac, mac, source, source.size, 100)
                assertTrue(bridge.isOutputReady())
                assertEquals(2, writes.size)
                assertArrayEquals(writes[0], writes[1])
                assertArrayEquals(original, Ntb16Codec.parse(writes[0]).single().copyOfRange(14, 54))
                bridge.sendIpv6(mac, mac, source, source.size, 100)
                assertEquals(3, writes.size)
                assertEquals(1, writes[2][6].toInt())
            }
        } finally { NcmOutboundConnectionShadow.transfer = null }
    }
}

@Implements(UsbDeviceConnection::class)
class NcmOutboundConnectionShadow {
    companion object {
        var transfer: ((ByteArray, Int, Int, Int) -> Int)? = null
    }
    @Implementation fun bulkTransfer(endpoint: UsbEndpoint, buffer: ByteArray,
        offset: Int, length: Int, timeout: Int): Int = transfer!!(buffer, offset, length, timeout)
    @Implementation fun close() = Unit
}
