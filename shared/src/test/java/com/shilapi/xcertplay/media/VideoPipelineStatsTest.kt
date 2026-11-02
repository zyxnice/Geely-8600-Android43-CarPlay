package com.shilapi.xcertplay.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoPipelineStatsTest {
    @Test fun receiveAgeAndSubmitIntervalExcludeDroppedOutputsAndResetAcrossDecoders() {
        val lines = mutableListOf<String>()
        val stats = VideoPipelineStats(lines::add, { 0 })
        stats.input(1, 1_000_000)
        stats.released(1, true, 11_000_000)
        stats.input(2, 2_000_000)
        stats.released(2, false, 21_000_000)
        stats.input(3, 3_000_000)
        stats.released(3, true, 31_000_000)
        stats.flush(true)
        assertTrue(lines.single().contains("RECEIVE_TO_SUBMITUs=19000/28000"))
        assertTrue(lines.single().contains("SUBMIT_INTERVALUs=20000/20000"))
        stats.input(4, 4_000_000)
        stats.decoderChanged()
        stats.released(4, true, 101_000_000)
        stats.record(VideoPipelineStats.Stage.OUTPUT_RELEASE, 1_000)
        stats.flush(true)
        assertTrue(lines.last().contains("RECEIVE_TO_SUBMITUs=0/0"))
        assertTrue(lines.last().contains("SUBMIT_INTERVALUs=0/0"))
    }

    @Test fun omittedCodecOutputsCannotGrowTimestampHistoryIndefinitely() {
        val lines = mutableListOf<String>()
        val stats = VideoPipelineStats(lines::add, { 0 })
        for (pts in 1L..129L) stats.input(pts, 1_000_000)
        stats.released(1, true, 11_000_000) // Oldest metadata has been evicted.
        stats.released(129, true, 21_000_000)
        stats.flush(true)
        assertTrue(lines.single().contains("RECEIVE_TO_SUBMITUs=20000/20000"))
    }

    @Test fun windowReportsIndependentStagesAndDoesNotCallSubmissionPhysicalDisplay() {
        var now = 0L
        val lines = mutableListOf<String>()
        val stats = VideoPipelineStats(lines::add, { now })
        stats.record(VideoPipelineStats.Stage.INPUT_WAIT, 30_000_000)
        stats.record(VideoPipelineStats.Stage.INPUT_WAIT, 10_000_000)
        stats.record(VideoPipelineStats.Stage.OUTPUT_RELEASE, 2_000_000)
        stats.output(false)
        stats.superseded()
        stats.output(true)
        stats.flush()
        assertTrue(lines.isEmpty())
        now = 5_000_000_000
        stats.flush()
        assertTrue(lines.single().contains("INPUT_WAITUs=20000/30000"))
        assertTrue(lines.single().contains("OUTPUT_RELEASEUs=2000/2000"))
        assertTrue(lines.single().contains("decoded=2 rendered=1 superseded=1"))
        stats.flush(true)
        assertEquals(1, lines.size)
        stats.record(VideoPipelineStats.Stage.QUEUE_AGE, 1_000_000)
        stats.flush(true)
        assertTrue(lines.last().contains("INPUT_WAITUs=0/0"))
        assertTrue(lines.last().contains("QUEUE_AGEUs=1000/1000"))
    }
}
