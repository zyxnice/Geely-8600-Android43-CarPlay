package com.shilapi.xcertplay.transport;

import java.nio.ByteBuffer;
import java.util.concurrent.ArrayBlockingQueue;

/** Reuses only fully consumed USB chunks; queued and partially read chunks remain exclusively owned. */
final class UsbReadChunkPool {
    private final ArrayBlockingQueue<Chunk> available = new ArrayBlockingQueue<>(8);

    /** Copy exactly the completed USB request, without retaining its reusable direct buffer. */
    Chunk copyFrom(ByteBuffer source, int count) {
        if (count <= 0 || count > 16384 || count > source.remaining()) {
            throw new IllegalArgumentException("Invalid completed USB request length");
        }
        Chunk chunk = available.poll();
        if (chunk == null) chunk = new Chunk();
        if (chunk.bytes.length < count) chunk.bytes = new byte[count];
        source.get(chunk.bytes, 0, count);
        chunk.length = count;
        return chunk;
    }

    /** Called once after the consumer has copied the last byte, never while a chunk is queued. */
    void release(Chunk chunk) {
        chunk.length = 0;
        // Retain at most eight 16 KiB arrays per endpoint; bursts still use the original queue limit.
        available.offer(chunk);
    }

    static final class Chunk {
        byte[] bytes = new byte[0];
        int length;
    }
}
