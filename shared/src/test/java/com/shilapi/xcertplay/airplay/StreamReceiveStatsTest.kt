package com.shilapi.xcertplay.airplay

import org.junit.Assert.*
import org.junit.Test

class StreamReceiveStatsTest {
    @Test fun decryptTimingIsSeparateAndResetsWithWindow() {
        var clock = 0L
        val lines = mutableListOf<String>()
        val stats = StreamReceiveStats("video", lines::add) { clock }
        stats.decrypted(20_000)
        stats.decrypted(40_000)
        clock = 5_000_000_000L
        stats.flush()
        assertTrue(lines.first().contains("decryptCount=2 decryptAvgUs=30 decryptMaxUs=40"))
        stats.flush(true)
        assertTrue(lines.last { it.startsWith("Receive:") }.contains("decryptCount=0 decryptAvgUs=0 decryptMaxUs=0"))
    }

    @Test fun videoCadenceCountsOnlyFramesAtReadCompletionBeforeDecrypt() {
        var clock = 0L
        val lines = mutableListOf<String>()
        val stats = StreamReceiveStats("video", lines::add) { clock }
        stats.reading()
        clock = 100_000_000
        stats.received(200)
        stats.videoFrameReceived()
        clock = 1_200_000_000
        stats.decrypted(1_100_000_000)
        stats.processed()
        stats.received(128) // Config/heartbeat is a message, not a frame.
        clock = 5_000_000_000
        stats.flush()
        assertTrue(lines.last().contains("frames=1 windowMs=5000 perSec=[1,0,0,0,0] late=0"))
    }

    @Test fun separatesSocketWaitFromLocalProcessingAndTracksSequenceWrap() {
        var clock = 0L
        val output = mutableListOf<String>()
        val stats = StreamReceiveStats("audio", output::add) { clock }
        fun packet(sequence: Int) {
            stats.reading()
            clock += 400_000_000L
            stats.received(100, sequence)
            clock += 200_000L
            stats.processed()
        }
        packet(65535)
        packet(0)
        packet(3) // two missing, then one late packet arrives
        packet(2)
        packet(4)
        stats.flush(ended = true)
        assertEquals(1, output.size)
        assertTrue(output.single().contains("readMaxMs=400 processMaxUs=200 seqForwardGaps=2 lateOrDuplicate=1"))
        assertTrue(output.single().contains("packets=5 bytes=500"))
    }

    @Test fun reportResetsWindowButRetainsSequenceContinuity() {
        var clock = 0L
        val output = mutableListOf<String>()
        val stats = StreamReceiveStats("audio", output::add) { clock }
        stats.reading()
        clock = 5_000_000_000L
        stats.received(10, 1)
        stats.processed()
        stats.reading()
        stats.received(20, 3)
        stats.processed()
        stats.flush(true)
        assertEquals(2, output.size)
        assertTrue(output.last().contains("packets=1 bytes=20 readMaxMs=0"))
        assertTrue(output.last().contains("seqForwardGaps=1"))
    }
}
