package com.shilapi.xcertplay.airplay

import org.junit.Assert.*
import org.junit.Test

class AudioPacketBuffersTest {
    @Test fun reuseKeepsExactInputsAndIndependentRtpAcrossPacketSizes() {
        val buffers = AudioPacketBuffers()
        val key = ByteArray(32) { it.toByte() }
        var previousRtp: ByteArray? = null
        var previousExpected: ByteArray? = null
        var previousSealed: ByteArray? = null
        for ((packet, size) in listOf(1400, 1400, 64, 1400, 0).withIndex()) {
            val header = ByteArray(12) { (it + size + packet).toByte() }
            val nonce = ByteArray(12) { if (it < 4) 0 else (it + size + packet).toByte() }
            val payload = ByteArray(size) { (it * 3 + packet).toByte() }
            val sealed = AirPlayCrypto.chachaSeal(key, nonce, payload, header.copyOfRange(4, 12))
            val wire = header + sealed + nonce.copyOfRange(4, 12)
            val original = wire.copyOf()
            buffers.prepare(wire)
            if (previousSealed?.size == sealed.size) assertSame(previousSealed, buffers.sealed)
            assertArrayEquals(sealed, buffers.sealed)
            assertArrayEquals(nonce, buffers.nonce)
            assertArrayEquals(header.copyOfRange(4, 12), buffers.aad)
            val plain = AirPlayCrypto.chachaOpen(key, buffers.nonce, buffers.sealed, buffers.aad)
            val rtp = buffers.rtp(wire, plain)
            assertArrayEquals(header + payload, rtp)
            assertArrayEquals(original, wire)
            if (previousRtp != null) assertArrayEquals(previousExpected, previousRtp)
            previousRtp = rtp
            previousExpected = rtp.copyOf()
            previousSealed = buffers.sealed
        }
    }

    @Test fun changedTagStillFailsAuthentication() {
        val buffers = AudioPacketBuffers()
        val key = ByteArray(32)
        val header = ByteArray(12)
        val sealed = AirPlayCrypto.chachaSeal(key, ByteArray(12), byteArrayOf(1, 2), ByteArray(8))
        sealed[sealed.lastIndex] = (sealed.last().toInt() xor 1).toByte()
        buffers.prepare(header + sealed + ByteArray(8))
        assertThrows(org.bouncycastle.crypto.InvalidCipherTextException::class.java) {
            AirPlayCrypto.chachaOpen(key, buffers.nonce, buffers.sealed, buffers.aad)
        }
        val rejectedStorage = buffers.sealed
        sealed[sealed.lastIndex] = (sealed.last().toInt() xor 1).toByte()
        buffers.prepare(header + sealed + ByteArray(8))
        assertSame(rejectedStorage, buffers.sealed)
        assertArrayEquals(byteArrayOf(1, 2),
            AirPlayCrypto.chachaOpen(key, buffers.nonce, buffers.sealed, buffers.aad))
    }
}
