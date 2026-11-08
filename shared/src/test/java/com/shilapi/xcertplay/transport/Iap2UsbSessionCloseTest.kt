package com.shilapi.xcertplay.transport

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbRequest
import java.nio.ByteBuffer
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

/** Exercises the pre-26 pump with a controlled driver; real API18 driver release needs car testing. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23], manifest = Config.NONE,
    shadows = [ClosingUsbConnectionShadow::class, ClosingUsbRequestShadow::class])
class Iap2UsbSessionCloseTest {
    @Before fun setUp() = LegacyCloseReplay.reset()

    @Test fun releaseFalseStillWaitsForDequeueBeforeClosingAndRejectsLateReaders() {
        val (session, connection, endpoint) = session()
        val workers = Executors.newCachedThreadPool()
        val read = workers.submit<ByteArray?> { session.read(2000) }
        try {
            assertTrue(LegacyCloseReplay.queued.await(2, TimeUnit.SECONDS))
            val close = workers.submit { session.close() }
            assertTrue(LegacyCloseReplay.released.await(2, TimeUnit.SECONDS))
            val secondClose = workers.submit { session.close() }
            assertFalse(close.isDone)
            assertFalse(secondClose.isDone)
            assertEquals(listOf("queue", "release:1"), LegacyCloseReplay.events.toList())
            try {
                H52UsbReadPump.read(connection, endpoint, ByteArray(1), 0, 1, 1)
                fail("A closing connection must not create another pump")
            } catch (_: IllegalStateException) { }
            LegacyCloseReplay.allowReap.countDown()
            close.get(2, TimeUnit.SECONDS)
            secondClose.get(2, TimeUnit.SECONDS)
            assertEquals(listOf("queue", "release:1", "reap", "request-close", "connection-close"),
                LegacyCloseReplay.events.toList())
            session.close()
            assertEquals(1, LegacyCloseReplay.events.count { it == "connection-close" })
            assertEquals(1, LegacyCloseReplay.events.count { it == "queue" })
            try { read.get(3, TimeUnit.SECONDS) } catch (_: ExecutionException) { }
            try {
                H52UsbReadPump.read(connection, endpoint, ByteArray(1), 0, 1, 1)
                fail("A closed connection must not initialize another request")
            } catch (_: IllegalStateException) { }
        } finally {
            LegacyCloseReplay.allowReap.countDown()
            workers.shutdownNow()
        }
    }

    @Test fun closeWaitsForInitializationBeforeReleasingTheInterface() {
        val (session, connection, endpoint) = session()
        LegacyCloseReplay.blockInitialize = true
        val workers = Executors.newCachedThreadPool()
        workers.submit<ByteArray?> { session.read(2000) }
        try {
            assertTrue(LegacyCloseReplay.initializing.await(2, TimeUnit.SECONDS))
            val closeThread = AtomicReference<Thread>()
            val close = workers.submit { closeThread.set(Thread.currentThread()); session.close() }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (closeThread.get()?.state != Thread.State.BLOCKED && System.nanoTime() < deadline) {
                Thread.yield()
            }
            assertEquals(Thread.State.BLOCKED, closeThread.get()?.state)
            try {
                H52UsbReadPump.read(connection, endpoint, ByteArray(1), 0, 1, 1)
                fail("Close must publish its gate before waiting for initialization")
            } catch (error: IllegalStateException) {
                assertEquals("USB connection is closing", error.message)
            }
            assertEquals(1L, LegacyCloseReplay.released.count)
            assertFalse(close.isDone)
            LegacyCloseReplay.allowInitialize.countDown()
            assertTrue(LegacyCloseReplay.released.await(2, TimeUnit.SECONDS))
            LegacyCloseReplay.allowReap.countDown()
            close.get(2, TimeUnit.SECONDS)
            assertTrue(LegacyCloseReplay.connectionClosed)
            assertEquals(1, LegacyCloseReplay.events.count { it == "request-close" })
        } finally {
            LegacyCloseReplay.allowInitialize.countDown()
            LegacyCloseReplay.allowReap.countDown()
            workers.shutdownNow()
        }
    }

    @Test fun ncmReleasesOnlyItsInterfacesInReverseOrderBeforeWaitingForItsPump() {
        val (_, connection, input) = session()
        val bridge = NcmUsbBridge(connection, input, input, null, listOf(usbInterface(3), usbInterface(4)), null)
        val workers = Executors.newCachedThreadPool()
        workers.submit<ByteArray?> { bridge.recv(100) }
        try {
            assertTrue(LegacyCloseReplay.queued.await(2, TimeUnit.SECONDS))
            val close = workers.submit { bridge.close() }
            assertTrue(LegacyCloseReplay.released.await(2, TimeUnit.SECONDS))
            LegacyCloseReplay.allowReap.countDown()
            close.get(2, TimeUnit.SECONDS)
            val events = LegacyCloseReplay.events.toList()
            assertEquals(listOf("release:4", "release:3"), events.filter { it.startsWith("release:") })
            assertTrue(events.indexOf("release:3") < events.indexOf("connection-close"))
            assertTrue(events.indexOf("reap") < events.indexOf("request-close"))
            assertTrue(events.indexOf("request-close") < events.indexOf("connection-close"))
            bridge.close()
            assertEquals(1, LegacyCloseReplay.events.count { it == "connection-close" })
        } finally {
            LegacyCloseReplay.allowReap.countDown()
            workers.shutdownNow()
        }
    }

    @Test fun anUnconfirmedReapRetainsTheRequestAndConnection() {
        val (session, connection, endpoint) = session()
        LegacyCloseReplay.returnExpectedRequest = false
        val workers = Executors.newCachedThreadPool()
        workers.submit<ByteArray?> { session.read(2000) }
        try {
            assertTrue(LegacyCloseReplay.queued.await(2, TimeUnit.SECONDS))
            val close = workers.submit { session.close() }
            assertTrue(LegacyCloseReplay.released.await(2, TimeUnit.SECONDS))
            LegacyCloseReplay.allowReap.countDown()
            try {
                close.get(2, TimeUnit.SECONDS)
                fail("Unknown completion cannot be reported as closed")
            } catch (error: ExecutionException) {
                assertTrue(error.cause!!.message!!.contains("has not been reaped"))
            }
            assertFalse(LegacyCloseReplay.events.contains("request-close"))
            assertFalse(LegacyCloseReplay.events.contains("connection-close"))
            try {
                H52UsbReadPump.read(connection, endpoint, ByteArray(1), 0, 1, 1)
                fail("An undrained owner must remain closing")
            } catch (_: IllegalStateException) { }
        } finally {
            LegacyCloseReplay.allowReap.countDown()
            workers.shutdownNow()
        }
    }

    private fun session(): Triple<Iap2UsbSession, UsbDeviceConnection, UsbEndpoint> {
        val connection = ReflectionHelpers.callConstructor(UsbDeviceConnection::class.java,
            ClassParameter.from(UsbDevice::class.java, null))
        val usbInterface = usbInterface(1)
        fun endpoint(address: Int): UsbEndpoint = ReflectionHelpers.callConstructor(UsbEndpoint::class.java,
            ClassParameter.from(Int::class.javaPrimitiveType, address),
            ClassParameter.from(Int::class.javaPrimitiveType, 2),
            ClassParameter.from(Int::class.javaPrimitiveType, 512),
            ClassParameter.from(Int::class.javaPrimitiveType, 0))
        val input = endpoint(0x85)
        return Triple(Iap2UsbSession(connection, usbInterface, endpoint(4), input), connection, input)
    }

    private fun usbInterface(id: Int): UsbInterface = ReflectionHelpers.callConstructor(UsbInterface::class.java,
            ClassParameter.from(Int::class.javaPrimitiveType, id),
            ClassParameter.from(Int::class.javaPrimitiveType, 0),
            ClassParameter.from(String::class.java, "USBMUX"),
            ClassParameter.from(Int::class.javaPrimitiveType, 255),
            ClassParameter.from(Int::class.javaPrimitiveType, 254),
            ClassParameter.from(Int::class.javaPrimitiveType, 2))
}

object LegacyCloseReplay {
    val events: MutableList<String> = Collections.synchronizedList(mutableListOf())
    lateinit var queued: CountDownLatch
    lateinit var released: CountDownLatch
    lateinit var allowReap: CountDownLatch
    lateinit var initializing: CountDownLatch
    lateinit var allowInitialize: CountDownLatch
    lateinit var request: UsbRequest
    var connectionClosed = false
    var returnExpectedRequest = true
    var blockInitialize = false
    fun reset() {
        events.clear()
        queued = CountDownLatch(1)
        released = CountDownLatch(1)
        allowReap = CountDownLatch(1)
        initializing = CountDownLatch(1)
        allowInitialize = CountDownLatch(1)
        connectionClosed = false
        returnExpectedRequest = true
        blockInitialize = false
    }
}

@Implements(UsbDeviceConnection::class)
class ClosingUsbConnectionShadow {
    @Implementation fun getFileDescriptor(): Int = if (LegacyCloseReplay.connectionClosed) -1 else 42
    @Implementation fun releaseInterface(usbInterface: UsbInterface): Boolean {
        LegacyCloseReplay.events += "release:${usbInterface.id}"
        LegacyCloseReplay.released.countDown()
        return false // The observed API18 boolean does not prove whether release completed.
    }
    @Implementation fun requestWait(): UsbRequest? {
        LegacyCloseReplay.allowReap.await()
        if (!LegacyCloseReplay.returnExpectedRequest) return null
        LegacyCloseReplay.events += "reap"
        return LegacyCloseReplay.request
    }
    @Implementation fun close() {
        LegacyCloseReplay.events += "connection-close"
        LegacyCloseReplay.connectionClosed = true
    }
}

@Implements(UsbRequest::class)
class ClosingUsbRequestShadow {
    @RealObject private lateinit var request: UsbRequest
    @Implementation fun initialize(connection: UsbDeviceConnection, endpoint: UsbEndpoint): Boolean {
        LegacyCloseReplay.initializing.countDown()
        if (LegacyCloseReplay.blockInitialize) LegacyCloseReplay.allowInitialize.await()
        return true
    }
    @Implementation fun queue(buffer: ByteBuffer, length: Int): Boolean {
        LegacyCloseReplay.request = request
        LegacyCloseReplay.events += "queue"
        LegacyCloseReplay.queued.countDown()
        return true
    }
    @Implementation fun cancel(): Boolean = throw AssertionError("Broken platform cancel must not be a close gate")
    @Implementation fun close() { LegacyCloseReplay.events += "request-close" }
}
