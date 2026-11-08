package com.shilapi.xcertplay.transport

import java.lang.reflect.InvocationTargetException
import java.nio.ByteBuffer
import java.util.ArrayDeque
import java.util.Random
import org.junit.Assert.*
import org.junit.Test

/** Test private storage behavior separately from the real recv/engine tests in TlsReadScratchTest. */
class TlsByteQueueTest {
    private val type = Class.forName("com.shilapi.xcertplay.transport.TlsDuplexChannel\$ByteQueue")
    private fun queue(): Any = type.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
    private fun append(queue: Any, value: ByteArray) = call(queue, "append", ByteArray::class.java, value)
    private fun take(queue: Any, count: Int): ByteArray? =
        call(queue, "take", Int::class.javaPrimitiveType!!, count) as ByteArray?
    private fun call(queue: Any, name: String, parameter: Class<*>, value: Any): Any? = try {
        type.getDeclaredMethod(name, parameter).apply { isAccessible = true }.invoke(queue, value)
    } catch (error: InvocationTargetException) { throw error.targetException }
    private fun buffer(queue: Any): ByteArray = type.getDeclaredField("bytes")
        .apply { isAccessible = true }.get(queue) as ByteArray
    private fun view(queue: Any): ByteBuffer = type.getDeclaredMethod("asByteBuffer")
        .apply { isAccessible = true }.invoke(queue) as ByteBuffer

    @Test fun ordinaryRecordsReuseCapacityWithoutExposingTailOrBorrowingInput() {
        val queue = queue()
        val input = byteArrayOf(1, 2, 3)
        append(queue, input)
        val storage = buffer(queue)
        input.fill(9)
        val delivered = take(queue, 10)!!
        assertArrayEquals(byteArrayOf(1, 2, 3), delivered)
        assertEquals(0, view(queue).remaining())
        assertNull(take(queue, 10))
        repeat(100) { append(queue, byteArrayOf(4)); assertArrayEquals(byteArrayOf(4), take(queue, 10)) }
        assertSame(storage, buffer(queue))
        assertArrayEquals(byteArrayOf(1, 2, 3), delivered)
    }

    @Test fun partialConsumptionCompactsInPlaceAndPreservesByteOrder() {
        val queue = queue()
        val first = ByteArray(16000) { it.toByte() }
        append(queue, first)
        val storage = buffer(queue)
        assertArrayEquals(first.copyOf(15000), take(queue, 15000))
        val next = ByteArray(4000) { (it + 7).toByte() }
        append(queue, next)
        assertSame(storage, buffer(queue))
        assertArrayEquals(first.copyOfRange(15000, 16000) + next, take(queue, 99999))
    }

    @Test fun defensiveLimitStillRejectsWithoutChangingDataAndLargeStorageIsReleased() {
        val queue = queue()
        val maximum = ByteArray(1024 * 1024) { 7 }
        append(queue, maximum)
        try { append(queue, byteArrayOf(8)); fail("Expected defensive limit") }
        catch (_: IphoneUsbException.Protocol) { }
        assertArrayEquals(maximum, take(queue, maximum.size))
        assertEquals(0, buffer(queue).size)
    }

    @Test fun fragmentedRecordsMatchIndependentQueueModel() {
        val queue = queue()
        val expected = ArrayDeque<Byte>()
        val random = Random(8600)
        repeat(600) {
            if (expected.size < 50000 && random.nextBoolean()) {
                val chunk = ByteArray(random.nextInt(2048))
                random.nextBytes(chunk)
                chunk.forEach(expected::addLast)
                append(queue, chunk)
            } else {
                val count = random.nextInt(4096)
                val result = if (expected.isEmpty()) null else ByteArray(minOf(count, expected.size)) {
                    expected.removeFirst()
                }
                assertArrayEquals(result, take(queue, count))
            }
            assertEquals(expected.size, view(queue).remaining())
        }
    }
}
