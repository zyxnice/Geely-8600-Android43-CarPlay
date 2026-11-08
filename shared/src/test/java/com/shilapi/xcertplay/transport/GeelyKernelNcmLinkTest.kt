package com.shilapi.xcertplay.transport

import com.shilapi.xcertplay.compat.GeelyUsbIdentity
import org.junit.Assert.*
import org.junit.Test

/** Resource identity, scoped addresses and attachment progress without any system writes. */
class GeelyKernelNcmLinkTest {
    private val descriptorHash = "a".repeat(64)
    private fun facts(name: String = "usb1", device: Int = 47, index: Int = 157,
                      path: String = "/sys/devices/platform/fsl-ehci.0/usb1/1-1/1-1:6.3",
                      hash: String = descriptorHash): KernelNcmInterface = KernelNcmInterface(
        name, path, "cdc_ncm", GeelyUsbIdentity("1-1", 1, device, 0x05ac, 0x12a8, hash),
        6, 3, index, byteArrayOf(0x26, 0x2a, 0xea.toByte(), 0x37, 0x33, 0x35), 1)

    @Test fun selectionUsesEnumerationAndControllerInsteadOfNetdevName() {
        val actual = facts()
        val otherController = facts(name = "usb0", path = "/sys/devices/platform/fsl-ehci.2/usb3/3-1/3-1:1.0")
        val selected = GeelyKernelNcmLink.selectInterface("/dev/bus/usb/001/047", 0x05ac, 0x12a8,
            descriptorHash, 3, listOf(otherController, facts(device = 48), facts(hash = "b".repeat(64)), actual))
        assertSame(actual, selected)
    }

    @Test fun missingOrAmbiguousKernelOwnerIsRejected() {
        for (candidates in listOf(emptyList(), listOf(facts(), facts(name = "usb2")))) {
            try {
                GeelyKernelNcmLink.selectInterface("/dev/bus/usb/001/047", 0x05ac, 0x12a8, descriptorHash, 3, candidates)
                fail("Must not guess a kernel NIC")
            } catch (_: IphoneUsbException.DeviceUnavailable) { }
        }
    }

    @Test fun onlyAReadyLinkLocalAddressOnThePinnedIfindexCanBeAdvertised() {
        val linkLocal = "fe80000000000000242aeafffe373335"
        for (flags in listOf("40", "48", "88", "a0")) {
            assertNull(GeelyKernelNcmLink.usableAddress("$linkLocal 9d 40 20 $flags usb1", "usb1", 157))
        }
        assertNull(GeelyKernelNcmLink.usableAddress("$linkLocal 9e 40 20 80 usb1", "usb1", 157))
        assertNull(GeelyKernelNcmLink.usableAddress("$linkLocal 9d 40 20 80 usb0", "usb1", 157))
        assertNull(GeelyKernelNcmLink.usableAddress("24080000000000000000000000000001 9d 40 00 80 usb1", "usb1", 157))
        val address = GeelyKernelNcmLink.usableAddress("$linkLocal 9d 40 20 80 usb1", "usb1", 157)!!
        assertTrue(address.isLinkLocalAddress)
        assertEquals(157, address.scopeId)
    }

    @Test fun onlySuccessfulTxAfterThisAttachmentCanSetOutputReady() {
        val io = FakeIo(facts())
        val link = GeelyKernelNcmLink(io.current, 4, 1, io)
        val address = GeelyKernelNcmLink.usableAddress(io.table, "usb1", 157)!!
        io.tx = 1000 // Historical/DAD traffic must not mark this attachment ready.
        link.beginAttachment(address)
        assertFalse(link.isOutputReady())
        io.tx = 1001
        assertTrue(link.isOutputReady())
        io.tx = 0
        assertFalse(link.isOutputReady())
        io.tx = 1002
        io.current = facts(device = 48)
        assertFalse(link.isAttachedCurrent())
        assertFalse(link.isOutputReady())
        link.close()
        assertEquals(0x1003, io.flags)
        assertEquals(1002L, io.tx)
    }

    @Test fun recreatedOrTentativeInterfaceInvalidatesTheActiveBinding() {
        val io = FakeIo(facts())
        val link = GeelyKernelNcmLink(io.current, 4, 1, io)
        link.beginAttachment(GeelyKernelNcmLink.usableAddress(io.table, "usb1", 157)!!)
        assertTrue(link.isAttachedCurrent())
        io.current = facts(index = 158)
        assertFalse(link.isAttachedCurrent())
        io.current = facts()
        io.table = io.table.replace("80 usb1", "40 usb1")
        assertFalse(link.isAttachedCurrent())
    }

    private class FakeIo(var current: KernelNcmInterface) : KernelNcmIo {
        var flags = 0x1003
        var tx = 0L
        var table = "fe80000000000000242aeafffe373335 9d 40 20 80 usb1"
        override fun readInterface(name: String, dataInterfaceId: Int) = current
        override fun interfaceFlags(name: String) = flags
        override fun ipv6Table() = table
        override fun txPackets(name: String) = tx
    }
}
