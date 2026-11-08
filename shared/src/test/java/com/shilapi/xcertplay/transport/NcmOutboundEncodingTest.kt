package com.shilapi.xcertplay.transport

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** Final NTB bytes must match the established two-step encoder, including padding and valid length. */
class NcmOutboundEncodingTest {
    private val host = byteArrayOf(2, 3, 4, 5, 6, 7)
    private val peer = byteArrayOf(8, 9, 10, 11, 12, 13)

    @Test fun directEncodingMatchesExistingWireFormatAndExcludesUnusedTail() {
        for (length in listOf(1, 40, 470, 471, 1500, 4096, 65493)) {
            val buffer = ByteArray(length + 23) { (it * 17).toByte() }
            val original = buffer.copyOf()
            val expected = Ntb16Codec.build(EthernetIpv6Codec.build(host, peer, buffer.copyOf(length)), 65535)
            val block = Ntb16Codec.buildIpv6(host, peer, buffer, length, 65535)
            assertArrayEquals(expected, block)
            assertArrayEquals(original, buffer)
            val parsed = Ntb16Codec.parse(block).single()
            assertEquals(length + 14, parsed.size)
            assertArrayEquals(buffer.copyOf(length), parsed.copyOfRange(14, parsed.size))
            // Sender may read into the TUN buffer again once the final block owns the bytes.
            buffer.fill(0)
            assertArrayEquals(expected, block)
        }
    }

    @Test fun exactUsbBoundaryAddsOnlyOneZeroPadWithoutChangingDeclaredBlockSize() {
        val block = Ntb16Codec.buildIpv6(host, peer, ByteArray(470), 470, 0)
        assertEquals(513, block.size)
        assertEquals(0, block[512].toInt())
        assertEquals(0, block[8].toInt() and 255)
        assertEquals(2, block[9].toInt() and 255)
    }

    @Test fun shortPacketCannotReadNeighborOrMulticastFieldsFromPreviousPacketTail() {
        val buffer = ByteArray(100)
        buffer[0] = 0x60
        buffer[6] = 58
        buffer[24] = 0xff.toByte()
        buffer[40] = 136.toByte()
        assertFalse(EthernetIpv6Codec.isNeighborAdvertisement(buffer, 20))
        assertEquals(null, EthernetIpv6Codec.multicastDestinationMac(buffer, 20))
    }

    @Test(expected = IllegalArgumentException::class)
    fun oversizedIpv6PacketCannotOverflowNtbLengthField() {
        Ntb16Codec.buildIpv6(host, peer, ByteArray(65494), 65494, 0)
    }
}
