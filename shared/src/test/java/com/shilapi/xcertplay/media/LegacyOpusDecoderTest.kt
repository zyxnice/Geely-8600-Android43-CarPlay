package com.shilapi.xcertplay.media

import io.github.jaredmdobson.concentus.OpusApplication
import io.github.jaredmdobson.concentus.OpusDecoder
import io.github.jaredmdobson.concentus.OpusEncoder
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.sin

class LegacyOpusDecoderTest {
    @Test fun `real encoded navigation tone decodes at negotiated rates and channel counts`() {
        for (rate in listOf(16000, 24000, 48000)) for (channels in 1..2) {
            val encoder = OpusEncoder(rate, channels, OpusApplication.OPUS_APPLICATION_AUDIO)
            val software = LegacyOpusDecoder(rate, channels) {}
            val reference = OpusDecoder(rate, channels)
            val frameSize = rate / 50
            val tone = ShortArray(frameSize * channels) { index ->
                (sin(2 * Math.PI * 440 * (index / channels) / rate) * 12000).toInt().toShort()
            }
            val packet = ByteArray(1275)
            val expected = ShortArray(5760 * channels)
            var hasSound = false
            repeat(20) {
                val length = encoder.encode(tone, 0, frameSize, packet, 0, packet.size)
                val accessUnit = packet.copyOf(length)
                val frames = reference.decode(accessUnit, 0, length, expected, 0, 5760, false)
                val bytes = software.decode(accessUnit)
                assertEquals(frameSize * channels * 2, bytes)
                assertEquals(frames * channels * 2, bytes)
                for (sample in 0 until frames * channels) {
                    val actual = ((software.pcm[sample * 2].toInt() and 255) or
                        (software.pcm[sample * 2 + 1].toInt() shl 8)).toShort()
                    assertEquals(expected[sample], actual)
                    if (actual.toInt() != 0) hasSound = true
                }
            }
            assertTrue("rate=$rate channels=$channels", hasSound)
        }
    }

    @Test fun `short silence packet decodes rather than being discarded`() {
        val decoder = LegacyOpusDecoder(48000, 1) {}
        // Valid Opus 20 ms silence frame, shorter than the old four-byte cutoff.
        assertEquals(1920, decoder.decode(byteArrayOf(0xf8.toByte(), 0xff.toByte(), 0xfe.toByte())))
    }

    @Test fun `invalid packets do not replay PCM and diagnostics are bounded`() {
        val logs = mutableListOf<String>()
        val decoder = LegacyOpusDecoder(48000, 1, logs::add)
        repeat(10) { assertEquals(0, decoder.decode(byteArrayOf(3))) }
        assertEquals(3, logs.size)
        assertEquals(0, decoder.decode(byteArrayOf()))
        assertTrue(decoder.decode(byteArrayOf(0xf8.toByte(), 0xff.toByte(), 0xfe.toByte())) > 0)
    }

    @Test fun `each stream starts with independent codec state and reuses output buffer`() {
        val first = LegacyOpusDecoder(24000, 1) {}
        val second = LegacyOpusDecoder(24000, 1) {}
        val packet = byteArrayOf(0xf8.toByte(), 0xff.toByte(), 0xfe.toByte())
        val buffer = first.pcm
        val firstBytes = first.decode(packet)
        val secondBytes = second.decode(packet)
        assertEquals(firstBytes, secondBytes)
        assertArrayEquals(first.pcm.copyOf(firstBytes), second.pcm.copyOf(secondBytes))
        first.decode(packet)
        assertSame(buffer, first.pcm)
        assertNotSame(first.pcm, second.pcm)
    }
}
