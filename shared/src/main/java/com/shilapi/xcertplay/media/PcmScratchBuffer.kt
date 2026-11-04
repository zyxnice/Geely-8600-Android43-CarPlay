package com.shilapi.xcertplay.media

/**
 * Renderer-worker-owned S16 byte-order conversion storage. The returned array is borrowed
 * until the next call: consume only [source.size] minus [offset] bytes synchronously.
 * The RTP source remains immutable for capture/other consumers. An odd final byte is preserved.
 */
internal class PcmScratchBuffer {
    private var buffer = ByteArray(0)

    fun copySwapped(source: ByteArray, offset: Int): ByteArray {
        require(offset in 0..source.size)
        val length = source.size - offset
        if (buffer.size < length) buffer = ByteArray(length)
        var index = 0
        while (index + 1 < length) {
            buffer[index] = source[offset + index + 1]
            buffer[index + 1] = source[offset + index]
            index += 2
        }
        if (index < length) buffer[index] = source[offset + index]
        return buffer
    }
}
