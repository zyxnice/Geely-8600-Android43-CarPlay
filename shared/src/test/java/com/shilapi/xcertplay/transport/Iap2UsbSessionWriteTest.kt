package com.shilapi.xcertplay.transport

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

/** API27 exercises the same pre-28 chunking branch as API18; it is not a device-driver test. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27], manifest = Config.NONE, shadows = [NcmOutboundConnectionShadow::class])
class Iap2UsbSessionWriteTest {
    @Test fun negativeAndZeroResultsRemainDistinctAndPoisonTheSession() {
        for (rawResult in listOf(-1, 0)) {
            var calls = 0
            NcmOutboundConnectionShadow.transfer = { _, _, _, _ -> calls++; rawResult }
            try {
                session().use { pipe ->
                    val first = failure { pipe.write(ByteArray(1233), 1000) }
                    assertTrue(first.message!!.contains("rawResult=$rawResult"))
                    assertTrue(first.message!!.contains("confirmedBytes=0"))
                    assertTrue(first.message!!.contains("endpoint=0x4"))
                    assertTrue(first.message!!.contains("progressUnknown=${rawResult < 0}"))
                    assertSame(first, failure { pipe.write(ByteArray(32), 1000) })
                    assertSame(first, failure { pipe.read(100) })
                    assertEquals(1, calls)
                }
            } finally { NcmOutboundConnectionShadow.transfer = null }
        }
    }

    @Test fun partialFrameFailureDoesNotReplayAnyConfirmedBytes() {
        val offsets = mutableListOf<Int>()
        NcmOutboundConnectionShadow.transfer = { _, offset, length, _ ->
            offsets += offset
            if (offset == 0) length else -1
        }
        try {
            session().use { pipe ->
                val error = failure { pipe.write(ByteArray(20000), 1000) }
                assertTrue(error.message!!.contains("confirmedBytes=16384"))
                assertTrue(error.message!!.contains("chunkBytes=3616"))
                assertSame(error, failure { pipe.write(ByteArray(20000), 1000) })
                assertEquals(listOf(0, 16384), offsets)
            }
        } finally { NcmOutboundConnectionShadow.transfer = null }
    }

    @Test fun exhaustedDeadlineAfterPartialWriteAlsoPoisonsTheSession() {
        val previousClock = LegacyUsbTransfer.clock
        var calls = 0
        NcmOutboundConnectionShadow.transfer = { _, _, length, _ ->
            calls++
            LegacyUsbTransfer.clock = { Long.MAX_VALUE }
            length
        }
        try {
            session().use { pipe ->
                val error = failure { pipe.write(ByteArray(20000), 1000) }
                assertTrue(error.message!!.contains("transferred 16384 of 20000"))
                assertSame(error, failure { pipe.write(ByteArray(32), 1000) })
                assertEquals(1, calls)
            }
        } finally {
            LegacyUsbTransfer.clock = previousClock
            NcmOutboundConnectionShadow.transfer = null
        }
    }

    private fun session(): Iap2UsbSession {
        val connection = ReflectionHelpers.callConstructor(UsbDeviceConnection::class.java,
            ClassParameter.from(UsbDevice::class.java, null))
        fun endpoint(address: Int): UsbEndpoint = ReflectionHelpers.callConstructor(
            UsbEndpoint::class.java, ClassParameter.from(Int::class.javaPrimitiveType, address),
            ClassParameter.from(Int::class.javaPrimitiveType, 2),
            ClassParameter.from(Int::class.javaPrimitiveType, 512),
            ClassParameter.from(Int::class.javaPrimitiveType, 0))
        val usbInterface = ReflectionHelpers.callConstructor(UsbInterface::class.java,
            ClassParameter.from(Int::class.javaPrimitiveType, 1),
            ClassParameter.from(Int::class.javaPrimitiveType, 0),
            ClassParameter.from(String::class.java, "USBMUX"),
            ClassParameter.from(Int::class.javaPrimitiveType, 255),
            ClassParameter.from(Int::class.javaPrimitiveType, 254),
            ClassParameter.from(Int::class.javaPrimitiveType, 2))
        return Iap2UsbSession(connection, usbInterface, endpoint(4), endpoint(0x85))
    }

    private fun failure(action: () -> Unit): IphoneUsbException.DeviceUnavailable {
        try { action() } catch (error: IphoneUsbException.DeviceUnavailable) { return error }
        fail("Expected USBMUX session failure")
        throw AssertionError("unreachable")
    }
}
