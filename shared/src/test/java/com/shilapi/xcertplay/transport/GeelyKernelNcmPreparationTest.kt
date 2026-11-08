package com.shilapi.xcertplay.transport

import com.shilapi.xcertplay.compat.GeelyUsbIdentity
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class GeelyKernelNcmPreparationTest {
    @Test fun downPhoneNicIsEnabledBeforeWaitingForItsScopedAddress() {
        val io = FakeIo()
        val link = GeelyKernelNcmLink(io.current, 4, 1, io)
        val address = link.awaitAddress({ false })
        assertEquals(1, io.enables)
        assertEquals("usb9", io.enabledName)
        assertEquals(157, address.scopeId)
        assertTrue(address.isLinkLocalAddress)
        link.close()
        assertEquals(0x1003, io.flags)
    }

    @Test fun alreadyUpIsReadOnlyAndDoesNotPrepareAgain() {
        val io = FakeIo()
        io.flags = 0x1003
        val link = GeelyKernelNcmLink(io.current, 4, 1, io)
        link.awaitAddress({ false })
        link.awaitAddress({ false })
        assertEquals(0, io.enables)
    }

    @Test fun failedUpIsNotRepeatedForTheSameBinding() {
        val io = FakeIo()
        io.ignoreUp = true
        val link = GeelyKernelNcmLink(io.current, 4, 1, io)
        repeat(2) {
            try { link.awaitAddress({ false }); fail() } catch (_: IOException) { }
        }
        assertEquals(1, io.enables)
    }

    @Test fun cancellationOrStaleEnumerationPreventsEnable() {
        val io = FakeIo()
        val link = GeelyKernelNcmLink(io.current, 4, 1, io)
        try { link.awaitAddress({ true }); fail() } catch (_: IOException) { }
        io.current = io.current.copy(ifindex = 158)
        try { link.awaitAddress({ false }); fail() } catch (_: IOException) { }
        assertEquals(0, io.enables)
    }

    @Test fun interfaceReplacementDuringEnableCannotSupplyAnAddress() {
        val io = FakeIo()
        io.replaceDuringEnable = true
        val link = GeelyKernelNcmLink(io.current, 4, 1, io)
        try { link.awaitAddress({ false }); fail() } catch (_: IOException) { }
        assertEquals(1, io.enables)
    }

    private class FakeIo : KernelNcmIo {
        var current = KernelNcmInterface("usb9", "/sys/devices/platform/fsl-ehci.0/usb1/1-1/1-1:6.3",
            "cdc_ncm", GeelyUsbIdentity("1-1", 1, 47, 0x05ac, 0x12a8, "a".repeat(64)),
            6, 3, 157, byteArrayOf(2, 0, 0, 0, 0, 1), 1)
        var flags = 0x1002
        var enables = 0
        var enabledName: String? = null
        var ignoreUp = false
        var replaceDuringEnable = false
        override fun readInterface(name: String, dataInterfaceId: Int) = current
        override fun interfaceFlags(name: String) = flags
        override fun ipv6Table() = "fe800000000000000000000000000001 9d 40 20 80 usb9"
        override fun txPackets(name: String) = 0L
        override fun enableInterface(expected: KernelNcmInterface, dataInterfaceId: Int, dataAlternate: Int,
                                     isCancelled: () -> Boolean) {
            check(!isCancelled())
            enables++
            enabledName = expected.name
            if (!ignoreUp) flags = flags or 1
            if (replaceDuringEnable) current = current.copy(ifindex = 158)
        }
    }
}
