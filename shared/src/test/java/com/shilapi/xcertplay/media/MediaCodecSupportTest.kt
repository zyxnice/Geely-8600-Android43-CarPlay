package com.shilapi.xcertplay.media

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import com.shilapi.xcertplay.airplay.VideoCodec
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse

class MediaCodecSupportTest {
    @Test fun directAacPackingMatchesExistingAdtsAtBothWifiRatesWithoutStaleTail() {
        val target = java.nio.ByteBuffer.allocateDirect(8192)
        for (rate in listOf(44_100, 48_000)) {
            for (length in listOf(8184, 512, 1, 6, 1024, 7, 4096, 2)) {
                val rtp = ByteArray(length + 12) { (it * 37 + length).toByte() }
                val original = rtp.copyOf()
                target.clear()
                val bytes = MediaCodecSupport.writeAdtsFrame(target, rtp, 12, length, rate, 2)
                assertEquals(length + 7, bytes)
                assertEquals(bytes, target.position())
                val encoded = ByteArray(bytes)
                target.flip()
                target.get(encoded)
                assertArrayEquals(MediaCodecSupport.adtsFrame(rtp.copyOfRange(12, rtp.size), rate, 2), encoded)
                assertArrayEquals(original, rtp)
                val adtsLength = ((encoded[3].toInt() and 3) shl 11) or
                    ((encoded[4].toInt() and 255) shl 3) or ((encoded[5].toInt() and 224) ushr 5)
                assertEquals(bytes, adtsLength)
            }
        }
    }

    @Test fun directAacPackingRejectsInvalidOrOversizeInputWithoutPartialHeader() {
        val target = java.nio.ByteBuffer.allocate(8192)
        val source = ByteArray(8200)
        for ((offset, length) in listOf(-1 to 8, 0 to -1, 0 to 0, 0 to 8185,
                8199 to 2, Int.MAX_VALUE to 1, 1 to Int.MAX_VALUE)) {
            target.clear()
            target.put(42)
            val before = target.array().copyOf()
            assertEquals(0, MediaCodecSupport.writeAdtsFrame(target, source, offset, length, 48_000, 2))
            assertEquals(1, target.position())
            assertArrayEquals(before, target.array())
        }
        target.clear()
        target.limit(10)
        assertEquals(0, MediaCodecSupport.writeAdtsFrame(target, source, 12, 4, 48_000, 2))
        assertEquals(0, target.position())
    }

    @Test fun directAacPackingHonorsTheDestinationPosition() {
        val target = java.nio.ByteBuffer.allocate(16)
        target.put(42)
        assertEquals(9, MediaCodecSupport.writeAdtsFrame(target, byteArrayOf(5, 6, 7), 1, 2, 48_000, 2))
        assertEquals(42, target.get(0).toInt())
        assertArrayEquals(MediaCodecSupport.adtsFrame(byteArrayOf(6, 7), 48_000, 2),
            target.array().copyOfRange(1, 10))
    }

    @Test fun truncatedAccessUnitIsRejectedInsteadOfSubmittingItsValidPrefix() {
        assertEquals(0, MediaCodecSupport.toAnnexB(byteArrayOf(0, 0, 0, 2, 0x41, 1, 0, 0, 0, 9, 0x41)).size)
        assertEquals(0, MediaCodecSupport.toAnnexB(byteArrayOf(0, 0, 0, 2, 0x41, 1, 0)).size)
    }

    @Test fun findsRandomAccessAfterParameterSetsButNeverOnInterframes() {
        val sps = byteArrayOf(0, 0, 0, 1, 0x67, 10)
        assertTrue(MediaCodecSupport.isRandomAccess(sps + byteArrayOf(0, 0, 1, 0x65, 20), VideoCodec.H264))
        assertFalse(MediaCodecSupport.isRandomAccess(sps + byteArrayOf(0, 0, 1, 0x41, 20), VideoCodec.H264))
        for (type in 16..21) assertTrue(MediaCodecSupport.isRandomAccess(byteArrayOf(0, 0, 0, 1, (type shl 1).toByte(), 1, 20), VideoCodec.H265))
        assertFalse(MediaCodecSupport.isRandomAccess(byteArrayOf(0, 0, 0, 1, 2, 1, 20), VideoCodec.H265))
    }

    @Test
    fun lengthPrefixedNalUnitsBecomeOneAnnexBBuffer() {
        val first = byteArrayOf(0x40, 0x01)
        val second = byteArrayOf(0x42, 0x01, 0x02)
        val lengthPrefixed =
            byteArrayOf(0, 0, 0, first.size.toByte()) + first +
                byteArrayOf(0, 0, 0, second.size.toByte()) + second

        assertArrayEquals(
            byteArrayOf(0, 0, 0, 1) + first +
                byteArrayOf(0, 0, 0, 1) + second,
            MediaCodecSupport.toAnnexB(lengthPrefixed),
        )
    }

    @Test
    fun hevcCodecSpecificDataBuildsAnnexBParameterSets() {
        val vps = byteArrayOf(0x40, 0x01)
        val sps = byteArrayOf(0x42, 0x01, 0x02)
        val pps = byteArrayOf(0x44, 0x01)
        val record = hevcRecord(
            vps,
            sps,
            pps,
        )

        assertArrayEquals(
            byteArrayOf(0, 0, 0, 1) + vps +
                byteArrayOf(0, 0, 0, 1) + sps +
                byteArrayOf(0, 0, 0, 1) + pps,
            MediaCodecSupport.hevcCodecSpecificData(record),
        )
    }

    @Test
    fun malformedHevcCodecSpecificDataIsRejected() {
        val truncated = hevcRecord(byteArrayOf(0x40, 0x01), byteArrayOf())
            .copyOfRange(0, 25)

        assertEquals(0, MediaCodecSupport.hevcCodecSpecificData(truncated).size)
    }

    private fun hevcRecord(vararg parameterSets: ByteArray): ByteArray {
        var size = 23
        parameterSets.forEach { size += 5 + it.size }
        val record = ByteArray(size)
        record[0] = 1
        record[21] = 3
        record[22] = parameterSets.size.toByte()
        var cursor = 23
        parameterSets.forEachIndexed { index, parameterSet ->
            record[cursor++] = (32 + index).toByte()
            record[cursor++] = 0
            record[cursor++] = 1
            record[cursor++] = (parameterSet.size ushr 8).toByte()
            record[cursor++] = parameterSet.size.toByte()
            parameterSet.copyInto(record, cursor)
            cursor += parameterSet.size
        }
        return record
    }
}
