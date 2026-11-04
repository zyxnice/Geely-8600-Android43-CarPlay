package com.shilapi.xcertplay.transport

import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

class UsbReadChunkPoolTest {
    @Test fun producerCannotOverwriteQueuedOrPartiallyConsumedChunk() {
        val pool = UsbReadChunkPool()
        val direct = ByteBuffer.allocateDirect(16)
        direct.put(byteArrayOf(1, 2, 3, 4)).flip()
        val first = pool.copyFrom(direct, 4)
        val destination = ByteArray(4)
        first.bytes.copyInto(destination, 0, 0, 2)
        direct.clear()
        direct.put(byteArrayOf(8, 9)).flip()
        val second = pool.copyFrom(direct, 2)
        assertNotSame(first, second)
        first.bytes.copyInto(destination, 2, 2, 4)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), destination)
        assertArrayEquals(byteArrayOf(8, 9), second.bytes.copyOf(second.length))
    }

    @Test fun shorterReuseDoesNotExposeOldTailAndLongerRequestGrows() {
        val pool = UsbReadChunkPool()
        val first = pool.copyFrom(ByteBuffer.wrap(byteArrayOf(1, 2, 3, 4)), 4)
        val original = first.bytes
        pool.release(first)
        val short = pool.copyFrom(ByteBuffer.wrap(byteArrayOf(7, 8)), 2)
        assertSame(first, short)
        assertSame(original, short.bytes)
        assertEquals(2, short.length)
        assertArrayEquals(byteArrayOf(7, 8), short.bytes.copyOf(short.length))
        pool.release(short)
        val large = pool.copyFrom(ByteBuffer.wrap(ByteArray(16384) { 5 }), 16384)
        assertSame(first, large)
        assertEquals(16384, large.length)
        assertTrue(large.bytes.all { it == 5.toByte() })
    }

    @Test fun retainedChunksAreBoundedAndEndpointPoolsAreIndependent() {
        val pool = UsbReadChunkPool()
        val chunks = List(10) { pool.copyFrom(ByteBuffer.wrap(byteArrayOf(it.toByte())), 1) }
        chunks.forEach(pool::release)
        val reused = List(10) { pool.copyFrom(ByteBuffer.wrap(byteArrayOf(42)), 1) }
        assertEquals(8, reused.count { next -> chunks.any { it === next } })
        val other = UsbReadChunkPool().copyFrom(ByteBuffer.wrap(byteArrayOf(42)), 1)
        assertFalse(chunks.any { it === other })
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidRequestLengthFailsBeforeReading() {
        UsbReadChunkPool().copyFrom(ByteBuffer.wrap(byteArrayOf(1)), 2)
    }
}
