package com.shilapi.xcertplay.media

import org.junit.Assert.*
import org.junit.Test

class PcmScratchBufferTest {
    @Test fun conversionPreservesSourceAndOddTail() {
        val source = byteArrayOf(9, 8, 1, 2, 3, 4, 5)
        val copy = source.clone()
        assertArrayEquals(byteArrayOf(2, 1, 4, 3, 5), PcmScratchBuffer().copySwapped(source, 2))
        assertArrayEquals(copy, source)
    }

    @Test fun shorterPacketReusesStorageAndOverwritesPreviousFade() {
        val scratch = PcmScratchBuffer()
        val first = scratch.copySwapped(byteArrayOf(1, 2, 3, 4), 0)
        first.fill(0) // The renderer's initial fade is allowed to mutate only its scratch.
        val next = scratch.copySwapped(byteArrayOf(5, 6), 0)
        assertSame(first, next)
        assertArrayEquals(byteArrayOf(6, 5), next.copyOf(2))
        assertSame(next, scratch.copySwapped(byteArrayOf(), 0))
        assertNotSame(next, scratch.copySwapped(ByteArray(8), 0))
    }

    @Test fun independentRenderersDoNotShareMutablePcm() {
        val first = PcmScratchBuffer().copySwapped(byteArrayOf(1, 2), 0)
        val second = PcmScratchBuffer().copySwapped(byteArrayOf(3, 4), 0)
        first.fill(0)
        assertArrayEquals(byteArrayOf(4, 3), second)
    }
}
