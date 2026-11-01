package com.shilapi.xcertplay.airplay

import java.io.ByteArrayInputStream
import org.junit.Assert.*
import org.junit.Test

class ScreenPacketReadTest {
    @Test fun fragmentedRecordsReuseOnlyTheExplicitSynchronousHeaderBuffer() {
        val input = object : ByteArrayInputStream(byteArrayOf(1, 2, 3, 4, 5, 6)) {
            override fun read(target: ByteArray, offset: Int, length: Int): Int =
                super.read(target, offset, minOf(length, 1))
        }
        val header = ByteArray(2)
        assertSame(header, readScreenBytes(input, 2, header))
        assertArrayEquals(byteArrayOf(1, 2), header)
        val retainedBody = readScreenBytes(input, 2)!!
        assertSame(header, readScreenBytes(input, 2, header))
        assertArrayEquals(byteArrayOf(5, 6), header)
        assertArrayEquals(byteArrayOf(3, 4), retainedBody)
        assertNotSame(header, retainedBody)
        assertNull(readScreenBytes(input, 2, header))
    }

    @Test fun truncationNeverPublishesAPartialRecordAndQueuedHeadersOwnTheirArrays() {
        assertNull(readScreenBytes(ByteArrayInputStream(byteArrayOf(1)), 2, ByteArray(2)))
        assertNull(readScreenBytes(ByteArrayInputStream(byteArrayOf()), -1))
        val input = ByteArrayInputStream(byteArrayOf(1, 2, 3, 4))
        val first = readScreenBytes(input, 2)!!
        val second = readScreenBytes(input, 2)!!
        assertNotSame(first, second)
        assertArrayEquals(byteArrayOf(1, 2), first)
        assertArrayEquals(byteArrayOf(3, 4), second)
    }
}
