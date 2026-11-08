package com.shilapi.xcertplay.network

import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayDisplayConfig
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.airplay.AirPlayMediaHandler
import com.shilapi.xcertplay.airplay.AirPlaySessionListener
import com.shilapi.xcertplay.airplay.PairingStore
import com.shilapi.xcertplay.compat.GeelyUsbIdentity
import com.shilapi.xcertplay.transport.GeelyKernelNcmLink
import com.shilapi.xcertplay.transport.KernelNcmInterface
import com.shilapi.xcertplay.transport.KernelNcmIo
import java.io.IOException
import java.net.InetAddress
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Exercises the real service cleanup/error owner, without configuring a host NIC. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class CarPlayKernelNcmAttachmentTest {
    private val config = AirPlayConfig("test", "02:00:00:00:00:02", "02:00:00:00:00:01", "1.0",
        AirPlayDisplayConfig(800, 480), port = 0)
    private val media = object : AirPlayMediaHandler {}

    @Test
    fun failedKernelAttachmentClosesTheTransferredLink() = withService { service ->
        val io = FakeDownInterface()
        val link = GeelyKernelNcmLink(io.facts, 4, 1, io)
        val address = GeelyKernelNcmLink.usableAddress(io.ipv6Table(), "usb1", 157)!!
        val result = service.attachKernelNcm(link, address, config, AirPlayIdentity.generate(), PairingStore(),
            null, object : AirPlaySessionListener {}, media)
        assertTrue(result is CarPlayVpnService.AttachResult.Failed)
        assertFalse(service.isAttached())
        assertNull(service.boundPort())
        // Restore the fake NIC to UP: only a genuinely closed link must still reject this attach.
        io.flags = 0x1003
        try {
            link.beginAttachment(address)
            fail("The service must close the link even when beginAttachment fails")
        } catch (_: IOException) { }
        assertEquals(0x1003, io.flags)
    }

    @Test
    fun delayedKernelMonitorErrorCannotCloseTheReplacementAttachment() = withService { service ->
        var errors = 0
        val listener = object : AirPlaySessionListener {
            override fun onTransportError(message: String) { errors += 1 }
        }
        fun attach() {
            assertEquals(CarPlayVpnService.AttachResult.Started, service.attachWireless(
                InetAddress.getByName("127.0.0.1"), config, AirPlayIdentity.generate(), PairingStore(),
                null, listener, media))
        }
        attach()
        val oldGeneration = service.startupGeneration()
        attach()
        val replacement = service.startupGeneration()
        val port = service.boundPort()
        val callback = CarPlayVpnService::class.java.getDeclaredMethod("onTransportError",
            Int::class.javaPrimitiveType, AirPlaySessionListener::class.java, Throwable::class.java)
        callback.isAccessible = true
        // Hold the real service monitor so the asynchronous callback cannot finish before we
        // capture and join its worker. This makes the late-generation check deterministic.
        val worker = synchronized(service) {
            val existing = Thread.getAllStackTraces().keys
            callback.invoke(service, oldGeneration, listener, IOException("Old kernel monitor event"))
            Thread.getAllStackTraces().keys.single {
                it.name == "airplay-teardown" && it !in existing
            }
        }
        worker.join(3_000)
        assertFalse("Teardown worker must finish", worker.isAlive)
        assertTrue(service.isAttached())
        assertEquals(replacement, service.startupGeneration())
        assertEquals(port, service.boundPort())
        assertEquals(0, errors)
    }

    private fun withService(test: (CarPlayVpnService) -> Unit) {
        val controller = Robolectric.buildService(CarPlayVpnService::class.java).create()
        val service = controller.get()
        try { test(service) } finally {
            service.detach()
            controller.destroy()
        }
    }

    private class FakeDownInterface : KernelNcmIo {
        val facts = KernelNcmInterface("usb1", "/sys/devices/platform/fsl-ehci.0/usb1/1-1/1-1:6.3",
            "cdc_ncm", GeelyUsbIdentity("1-1", 1, 47, 0x05ac, 0x12a8, "a".repeat(64)),
            6, 3, 157, byteArrayOf(2, 0, 0, 0, 0, 1), 1)
        var flags = 0x1002
        override fun readInterface(name: String, dataInterfaceId: Int) = facts
        override fun interfaceFlags(name: String) = flags
        override fun ipv6Table() = "fe800000000000000000000000000001 9d 40 20 80 usb1"
        override fun txPackets(name: String) = 0L
        override fun enableInterface(expected: KernelNcmInterface, dataInterfaceId: Int, dataAlternate: Int,
                                     isCancelled: () -> Boolean) { fail("Attachment cannot prepare a NIC") }
    }
}
