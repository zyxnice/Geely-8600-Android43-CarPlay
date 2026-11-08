package com.shilapi.xcertplay.transport

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class Ntb16CodecTest {
    @Test fun zeroNdpPointerRemainsEmptyAtNonzeroBlockOffset() {
        val block = Ntb16Codec.build(byteArrayOf(1), 12).also { it[10] = 0; it[11] = 0 }
        assertEquals(emptyList<Ntb16Codec.DatagramRange>(), Ntb16Codec.parseViews(block, 0, block.size))
        val prefixed = ByteArray(9) + block
        assertEquals(emptyList<Ntb16Codec.DatagramRange>(), Ntb16Codec.parseViews(prefixed, 9, block.size))
    }
    @Test fun borrowedRangesAndOwnedParserAgreeForNonzeroBlockOffset() {
        val frame = byteArrayOf(1, 2, 3, 4, 5)
        val block = Ntb16Codec.build(frame, 3)
        val bytes = ByteArray(9) + block + ByteArray(17)
        val range = Ntb16Codec.parseViews(bytes, 9, block.size).single()
        assertEquals(9 + 28, range.offset)
        assertEquals(frame.size, range.length)
        assertArrayEquals(Ntb16Codec.parse(bytes, 9, block.size).single(),
            bytes.copyOfRange(range.offset, range.offset + range.length))
    }

    @Test fun ethernetViewExcludesAdjacentDatagramsAndUnusedCapacity() {
        val payload = ByteArray(40) { it.toByte() }
        val mac = byteArrayOf(1, 2, 3, 4, 5, 6)
        val ethernet = EthernetIpv6Codec.build(mac, mac, payload)
        val bytes = ByteArray(28) + ethernet + ByteArray(100)
        val view = EthernetIpv6Codec.parseIpv6View(bytes, 28, ethernet.size)!!
        assertEquals(42, view.payloadOffset)
        assertEquals(40, view.payloadLength)
        assertArrayEquals(mac, view.sourceMac)
        assertArrayEquals(payload, bytes.copyOfRange(view.payloadOffset, view.payloadOffset + view.payloadLength))
    }
    @Test
    fun parseCanReadBlockFromLargerBuffer() {
        val frame = byteArrayOf(0x33, 0x33, 0, 0, 0, 1, 0x86.toByte(), 0xdd.toByte())
        val block = Ntb16Codec.build(frame, 7)
        val buffer = ByteArray(11) + block + ByteArray(3)

        val parsed = Ntb16Codec.parse(buffer, 11, block.size)

        assertArrayEquals(frame, parsed.single())
    }

    @Test
    fun largestDatagramKeepsBlockLengthWithinU16() {
        val block = Ntb16Codec.build(ByteArray(65_507), 0x1234)

        assertEquals(65_535, block.size)
        assertEquals(0xff, block[8].toInt() and 0xff)
        assertEquals(0xff, block[9].toInt() and 0xff)
    }

    @Test(expected = IllegalArgumentException::class)
    fun datagramThatWouldOverflowBlockLengthIsRejected() {
        Ntb16Codec.build(ByteArray(65_508), 0x1234)
    }
}
