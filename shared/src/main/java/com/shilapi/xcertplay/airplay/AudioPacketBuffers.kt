package com.shilapi.xcertplay.airplay

/**
 * One audio receive worker's synchronous AEAD input scratch space. Never passed to listeners.
 * Ciphertext retains its exact length because MediaFrameCrypto authenticates the whole array.
 * Wire snapshots and returned RTP packets remain independently owned by their consumers.
 */
internal class AudioPacketBuffers {
    val aad = ByteArray(8)
    val nonce = ByteArray(12)
    var sealed = ByteArray(0)
        private set

    /** Valid wire layout: 12-byte RTP header, ciphertext and 16-byte tag, 8-byte nonce. */
    fun prepare(wire: ByteArray) {
        require(wire.size >= 36) { "audio packet shorter than RTP header plus tail" }
        val sealedEnd = wire.size - 8
        val sealedSize = sealedEnd - 12
        if (sealed.size != sealedSize) sealed = ByteArray(sealedSize)
        wire.copyInto(aad, 0, 4, 12)
        wire.copyInto(sealed, 0, 12, sealedEnd)
        // The leading four bytes stay zero; the packet carries the trailing eight bytes.
        wire.copyInto(nonce, 4, sealedEnd, wire.size)
    }

    /** Independent result preserves both callback retention and the queued audio renderer. */
    fun rtp(wire: ByteArray, payload: ByteArray): ByteArray = ByteArray(12 + payload.size).also {
        wire.copyInto(it, 0, 0, 12)
        payload.copyInto(it, 12)
    }
}
