package com.shilapi.xcertplay.transport

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

/** Replay USB completions through the real NCM parser; no physical device is required. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE,
    shadows = [EvidenceUsbConnectionShadow::class, EvidenceUsbRequestShadow::class])
class NcmReceiveReplayTest {
    @Before fun resetReplay() { UsbEvidenceReplay.reset() }

    @Test fun completeAlignedBlockIsDeliveredWithoutWaitingForOptionalPad() {
        val frame = ByteArray(484) { it.toByte() }
        UsbEvidenceReplay.transfers.add(Ntb16Codec.build(frame, 0).copyOf(512))
        bridge().use { bridge ->
            assertArrayEquals(frame, bridge.recv(100))
            assertEquals(1, UsbEvidenceReplay.completedReads)
            assertEquals(0, UsbEvidenceReplay.timedOutReads)
        }
    }

    @Test fun delayedOptionalPadDoesNotDelayOrDuplicateThePreviousFrame() {
        val first = ByteArray(484) { it.toByte() }
        val second = byteArrayOf(7, 8)
        UsbEvidenceReplay.transfers.add(Ntb16Codec.build(first, 0).copyOf(512))
        bridge().use { bridge ->
            assertArrayEquals(first, bridge.recv(100))
            UsbEvidenceReplay.transfers.add(byteArrayOf(0))
            assertEquals(null, bridge.recv(20))
            val next = Ntb16Codec.build(second, 1)
            UsbEvidenceReplay.transfers.add(next.copyOfRange(0, 3))
            UsbEvidenceReplay.transfers.add(next.copyOfRange(3, next.size))
            assertArrayEquals(second, bridge.recv(1000))
        }
    }

    @Test fun alignedBlockMayBeFollowedDirectlyByNextHeader() {
        val first = ByteArray(484) { it.toByte() }
        val second = byteArrayOf(9)
        UsbEvidenceReplay.transfers.add(Ntb16Codec.build(first, 0).copyOf(512) +
            Ntb16Codec.build(second, 1))
        bridge().use { bridge ->
            assertArrayEquals(first, bridge.recv(1000))
            assertArrayEquals(second, bridge.recv(1000))
        }
    }

    @Test fun omittedPadAndSplitNextHeaderSurviveReceiveTimeoutAndCompaction() {
        val first = ByteArray(484) { it.toByte() }
        val second = ByteArray(484) { (it + 17).toByte() }
        val third = byteArrayOf(31, 32)
        UsbEvidenceReplay.transfers.add(Ntb16Codec.build(first, 0).copyOf(512))
        bridge().use { bridge ->
            assertTrue(bridge.consumeFrame(1000) { bytes, offset, length ->
                assertArrayEquals(first, bytes.copyOfRange(offset, offset + length))
            })
            // Pending optional padding must survive both compaction and an empty USB timeout.
            assertEquals(null, bridge.recv(20))
            val next = Ntb16Codec.build(second, 1).copyOf(512)
            UsbEvidenceReplay.transfers.add(next.copyOfRange(0, 1))
            assertEquals(null, bridge.recv(20))
            UsbEvidenceReplay.transfers.add(next.copyOfRange(1, next.size))
            assertArrayEquals(second, bridge.recv(1000))
            // The second aligned block independently allows one delayed pad.
            UsbEvidenceReplay.transfers.add(byteArrayOf(0) + Ntb16Codec.build(third, 2))
            assertArrayEquals(third, bridge.recv(1000))
        }
    }

    @Test fun malformedByteAfterAlignedBlockStillFails() {
        UsbEvidenceReplay.transfers.add(Ntb16Codec.build(ByteArray(484), 0).copyOf(512) +
            byteArrayOf(2))
        bridge().use { bridge ->
            try {
                bridge.recv(100)
                fail("Unexpected trailing byte must not be silently skipped")
            } catch (_: IphoneUsbException.DeviceUnavailable) { }
        }
    }

    @Test fun multipleDatagramsInOneBlockKeepAbsoluteRangesUntilAllConsumed() {
        val block = Ntb16Codec.build(byteArrayOf(1, 2), 0).copyOf(36)
        block[8] = 36
        block[16] = 20
        block[20] = 32
        block[24] = 34
        block[26] = 2
        block[28] = 0
        block[29] = 0
        block[32] = 1
        block[33] = 2
        block[34] = 3
        block[35] = 4
        UsbEvidenceReplay.transfers.add(block + Ntb16Codec.build(byteArrayOf(5), 1))
        bridge().use { bridge ->
            for (expected in listOf(byteArrayOf(1, 2), byteArrayOf(3, 4), byteArrayOf(5))) {
                assertTrue(bridge.consumeFrame(1000) { bytes, offset, length ->
                    assertArrayEquals(expected, bytes.copyOfRange(offset, offset + length))
                })
            }
        }
    }

    @Test fun corruptFollowingBlockFailsBeforeAnyBorrowedDelivery() {
        val invalid = Ntb16Codec.build(byteArrayOf(2), 1).also { it[0] = 0 }
        UsbEvidenceReplay.transfers.add(Ntb16Codec.build(byteArrayOf(1), 0) + invalid)
        bridge().use { bridge ->
            try {
                bridge.consumeFrame(1000) { _, _, _ -> fail("Must validate the entire batch first") }
                fail("Expected malformed block failure")
            } catch (_: IphoneUsbException.DeviceUnavailable) { }
        }
    }

    @Test fun coalescedBlocksStillEnforceAggregateFrameLimit() {
        val blocks = java.io.ByteArrayOutputStream()
        repeat(257) { blocks.write(Ntb16Codec.build(byteArrayOf(1), it)) }
        UsbEvidenceReplay.transfers.add(blocks.toByteArray())
        bridge().use { bridge ->
            try {
                bridge.consumeFrame(1000) { _, _, _ -> fail("Queue limit must precede delivery") }
                fail("Expected aggregate queue overflow")
            } catch (_: IphoneUsbException.DeviceUnavailable) { }
        }
    }

    @Test fun borrowedFrameUsesAccumulatorAndCannotBeInvalidatedByReentrantRead() {
        val first = ByteArray(484) { it.toByte() } // NTB requires the USB short-packet pad.
        val second = ByteArray(80) { (it + 9).toByte() }
        UsbEvidenceReplay.transfers.add(Ntb16Codec.build(first, 0) + Ntb16Codec.build(second, 1))
        bridge().use { bridge ->
            assertTrue(bridge.consumeFrame(1000) { bytes, offset, length ->
                assertSame(ReflectionHelpers.getField<ByteArray>(bridge, "buffered"), bytes)
                assertArrayEquals(first, bytes.copyOfRange(offset, offset + length))
                try {
                    bridge.recv(1)
                    fail("Borrowed memory must not be compacted by recursive receive")
                } catch (_: IllegalStateException) { }
                assertArrayEquals(first, bytes.copyOfRange(offset, offset + length))
            })
            assertTrue(bridge.consumeFrame(1000) { bytes, offset, length ->
                assertArrayEquals(second, bytes.copyOfRange(offset, offset + length))
            })
        }
    }

    @Test fun callbackFailureReleasesBorrowAndPreservesNextBlock() {
        UsbEvidenceReplay.transfers.add(Ntb16Codec.build(byteArrayOf(1), 0) +
            Ntb16Codec.build(byteArrayOf(2), 1))
        bridge().use { bridge ->
            try {
                bridge.consumeFrame(1000) { _, _, _ -> throw java.io.IOException("write failed") }
                fail("Expected callback failure")
            } catch (_: java.io.IOException) { }
            assertArrayEquals(byteArrayOf(2), bridge.recv(1000))
        }
    }

    @Test fun fragmentedAndCoalescedBlocksPreserveFramesAcrossBufferReuse() {
        val first = ByteArray(22000) { (it * 7).toByte() }
        val second = ByteArray(12000) { (it * 11).toByte() }
        val wire = Ntb16Codec.build(first, 0) + Ntb16Codec.build(second, 1)
        // A partial header, multiple partial bodies, then two blocks sharing a completion.
        listOf(0, 7, 16000, 22000, wire.size).zipWithNext().forEach { (start, end) ->
            UsbEvidenceReplay.transfers.add(wire.copyOfRange(start, end))
        }
        bridge().use { bridge ->
            val delivered = bridge.recv(1000)!!
            assertArrayEquals(first, delivered)
            assertArrayEquals(second, bridge.recv(1000))
            // Subsequent buffer compaction/read must not mutate a previously returned frame.
            assertArrayEquals(first, delivered)
            assertEquals(4, UsbEvidenceReplay.completedReads)
        }
    }

    @Test fun timedOutPartialBlockSurvivesNextReceiveAndCapacityGrowth() {
        val frame = ByteArray(48000) { (it * 13).toByte() }
        val wire = Ntb16Codec.build(frame, 2)
        UsbEvidenceReplay.transfers.add(wire.copyOfRange(0, 16000))
        bridge().use { bridge ->
            assertEquals(null, bridge.recv(100))
            assertEquals(1, UsbEvidenceReplay.completedReads)
            assertTrue(UsbEvidenceReplay.timedOutReads > 0)
            UsbEvidenceReplay.transfers.add(wire.copyOfRange(16000, 32000))
            UsbEvidenceReplay.transfers.add(wire.copyOfRange(32000, wire.size))
            assertArrayEquals(frame, bridge.recv(1000))
        }
    }

    private fun bridge(): NcmUsbBridge {
        val connection = ReflectionHelpers.callConstructor(UsbDeviceConnection::class.java,
            ClassParameter.from(UsbDevice::class.java, null))
        fun endpoint(address: Int): UsbEndpoint = ReflectionHelpers.callConstructor(
            UsbEndpoint::class.java, ClassParameter.from(Int::class.javaPrimitiveType, address),
            ClassParameter.from(Int::class.javaPrimitiveType, 2),
            ClassParameter.from(Int::class.javaPrimitiveType, 512),
            ClassParameter.from(Int::class.javaPrimitiveType, 0))
        return NcmUsbBridge(connection, endpoint(4), endpoint(0x85), null, emptyList(), null)
    }
}
