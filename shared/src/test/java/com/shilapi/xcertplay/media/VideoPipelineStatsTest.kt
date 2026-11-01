package com.shilapi.xcertplay.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoPipelineStatsTest {
    @Test fun conversionBytesAreWindowLocalAndAreNotReportedAsTotalAllocationsOrGc() {
        val lines = mutableListOf<String>()
        val stats = VideoPipelineStats(lines::add, { 0 })
        stats.copy(100, 100)
        stats.copy(50, 0)
        stats.record(VideoPipelineStats.Stage.INPUT_PREPARE, 3_000)
        stats.flush(true)
        assertTrue(lines.first().contains("INPUT_PREPAREUs=3/3"))
        assertTrue(lines.first().contains("copiedFrames=2 copiedBytes=150 annexBArrayBytes=100 heapUsedBytes="))
        stats.copy(10, 0)
        stats.flush(true)
        assertTrue(lines[2].contains("copiedFrames=1 copiedBytes=10 annexBArrayBytes=0"))
    }

    @Test fun idleAndQueueOnlyWindowsAdvanceCadenceBeforeFramesResume() {
        var now = 0L
        val lines = mutableListOf<String>()
        val stats = VideoPipelineStats(lines::add, { now })
        now = 5_000_000_000
        stats.flush()
        assertTrue(lines.isEmpty())
        stats.queue(VideoDecodeQueue.Snapshot(0, 0, 0))
        now = 10_000_000_000
        stats.flush()
        assertTrue(lines.last().contains("frames=0 windowMs=5000 perSec=[0,0,0,0,0]"))
        assertTrue(lines.last().contains("queueSamples=1"))
        stats.input(1, 11_000_000_000)
        stats.released(1, true, 11_000_000_000)
        now = 15_000_000_000
        stats.flush()
        assertTrue(lines.last().contains("frames=1 windowMs=5000 perSec=[0,1,0,0,0] late=0"))
        assertTrue(lines.last().contains("queueSamples=0"))
    }
    @Test fun receiveAgeAndSubmitIntervalExcludeDroppedOutputsAndResetAcrossDecoders() {
        val lines = mutableListOf<String>()
        val stats = VideoPipelineStats({ if (it.startsWith("pipeline")) lines.add(it) }, { 0 })
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
        val stats = VideoPipelineStats({ if (it.startsWith("pipeline")) lines.add(it) }, { 0 })
        for (pts in 1L..129L) stats.input(pts, 1_000_000)
        stats.released(1, true, 11_000_000) // Oldest metadata has been evicted.
        stats.released(129, true, 21_000_000)
        stats.flush(true)
        assertTrue(lines.single().contains("RECEIVE_TO_SUBMITUs=20000/20000"))
    }

    @Test fun windowReportsIndependentStagesAndDoesNotCallSubmissionPhysicalDisplay() {
        var now = 0L
        val lines = mutableListOf<String>()
        val stats = VideoPipelineStats({ if (it.startsWith("pipeline")) lines.add(it) }, { now })
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

    @Test fun cadenceReportsSampledQueueAndBoundedPendingInputMetadata() {
        var now = 0L
        val lines = mutableListOf<String>()
        val stats = VideoPipelineStats(lines::add, { now })
        stats.queue(VideoDecodeQueue.Snapshot(7, 1000, 200_000_000))
        stats.queue(VideoDecodeQueue.Snapshot(3, 1500, 100_000_000))
        stats.input(1, 0)
        stats.input(2, 0)
        stats.released(1, true, 1_000_000)
        stats.released(2, false, 2_000_000)
        now = 5_000_000_000
        stats.flush()
        val cadence = lines.last()
        assertTrue(cadence.contains("frames=1 windowMs=5000 perSec=[1,0,0,0,0]"))
        assertTrue(cadence.contains("queueSamples=2 queuePeakFrames=7 queuePeakBytes=1500 queuePeakAgeMs=200"))
        assertTrue(cadence.contains("trackedInputs=0/2 metadataEvictions=0"))
        stats.record(VideoPipelineStats.Stage.INPUT_DEQUEUE, 2_000)
        stats.flush(true)
        assertTrue(lines.last().contains("queueSamples=0 queuePeakFrames=0 queuePeakBytes=0"))
    }
}
