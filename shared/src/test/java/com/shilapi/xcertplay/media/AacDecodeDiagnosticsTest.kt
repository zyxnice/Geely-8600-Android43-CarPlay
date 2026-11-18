package com.shilapi.xcertplay.media

import org.junit.Assert.*
import org.junit.Test

class AacDecodeDiagnosticsTest {
    @Test fun optimizedInspectionCountsEverySignedSampleExactlyAcrossBuffers() {
        val stats = AacDecodeDiagnostics { 10_000_000L }
        // All S16LE values, including -32768 and zero, through the actual diagnostic loop.
        val samples = ByteArray(65536 * 2)
        for (value in -32768..32767) {
            val index = (value + 32768) * 2
            samples[index] = value.toByte()
            samples[index + 1] = (value shr 8).toByte()
        }
        val before = samples.copyOf()
        for (offset in samples.indices step 4096) stats.pcm(samples, offset, 4096, offset.toLong())
        val line = stats.snapshot()
        assertTrue(line.contains("pcmSamples=65536 pcmNonZeroSamples=65535 pcmZeroBuffers=0 pcmPeak=32768"))
        assertArrayEquals(before, samples)
        stats.pcm(byteArrayOf(1, 0), 0, 2, 1)
        assertTrue(stats.snapshot().contains("pcmPeak=1"))
    }

    @Test fun reportsMeasuredPcmInspectionAndResetsOnlyWindowCosts() {
        var now = 1_000_000L
        var step = 0L
        val stats = AacDecodeDiagnostics { now.also { now += step } }
        val pcm = byteArrayOf(0, 0, 1, 0, 0, -128)
        val before = pcm.copyOf()
        step = 12_000L
        stats.pcm(pcm, 0, pcm.size, 0)
        step = 24_000L
        stats.pcm(pcm, 0, pcm.size, 21_333)
        step = 0
        stats.logEmitted(2_000_000L)
        stats.logEmitted(3_000_000L)
        now = 5_001_000_000L
        val first = stats.snapshot()
        assertTrue(first.contains("window=1 windowMs=5000"))
        assertTrue(first.contains("pcmInspectCalls=2 pcmInspectTotalUs=36 pcmInspectMaxUs=24"))
        assertTrue(first.contains("logEmitCallsTotal=2 logEmitTotalUs=5000 logEmitMaxUs=3000"))
        assertArrayEquals(before, pcm)
        val second = stats.snapshot()
        assertTrue(second.contains("pcmInspectCalls=0 pcmInspectTotalUs=0 pcmInspectMaxUs=0"))
        assertTrue(second.contains("logEmitCallsTotal=2 logEmitTotalUs=5000 logEmitMaxUs=3000"))
    }

    @Test fun quietInputAndSilentPcmAreNotCountedAsInputWithoutOutput() {
        var now = 1_000_000L
        val stats = AacDecodeDiagnostics { now }
        stats.queued(0, 0)
        now += 5_000_000_000L
        assertTrue(stats.snapshot().contains("inputNoPcmWindowsTotal=1"))
        now += 5_000_000_000L
        assertTrue(stats.snapshot().contains("inputNoPcmWindowsTotal=1"))
        stats.queued(21_333, 0)
        stats.pcm(ByteArray(4), 0, 4, 21_333)
        assertTrue(stats.snapshot().contains("inputNoPcmWindowsTotal=1"))
        stats.queued(42_666, 0)
        assertTrue(stats.snapshot().contains("inputNoPcmWindowsTotal=2"))
    }

    @Test fun outputGapSpansWindowsAndCompletedLogCostsAppearNextTime() {
        var now = 1_000_000L
        val stats = AacDecodeDiagnostics { now }
        stats.pcm(byteArrayOf(1, 0), 0, 2, 0)
        assertTrue(stats.snapshot().contains("outputGapMaxMs=0"))
        // An emission cannot include its own completed duration without another log call.
        stats.logEmitted(4_000_000L)
        now += 1_400_000_000L
        stats.pcm(byteArrayOf(1, 0), 0, 2, 21_333)
        val next = stats.snapshot()
        assertTrue(next.contains("outputGapMaxMs=1400"))
        assertTrue(next.contains("logEmitCallsTotal=1 logEmitTotalUs=4000 logEmitMaxUs=4000"))
        assertTrue(stats.snapshot().contains("outputGapMaxMs=0"))
    }

    @Test fun pcmStatisticsRespectOffsetSigned16BitAndDoNotModifyAudio() {
        val stats = AacDecodeDiagnostics { 10_000_000L }
        val pcm = byteArrayOf(99, 99, 0, 0, 0, -128, -1, 127, -1, -1, 99)
        val before = pcm.copyOf()
        stats.pcm(pcm, 2, 8, 123)
        val line = stats.snapshot()
        assertArrayEquals(before, pcm)
        assertTrue(line.contains("pcmSamples=4 pcmNonZeroSamples=3 pcmZeroBuffers=0 pcmPeak=32768"))
        assertTrue(line.contains("unmatchedOutput=1"))
    }

    @Test fun separatesSilentDecodedPcmFromNoDecoderOutput() {
        val stats = AacDecodeDiagnostics { 1_000_000L }
        stats.accessUnit(6, 0)
        stats.pcm(ByteArray(4096), 0, 4096, 0)
        val first = stats.snapshot()
        assertTrue(first.contains("tinyAu=1"))
        assertTrue(first.contains("pcmBuffers=1 pcmBytes=4096 pcmSamples=2048 pcmNonZeroSamples=0 pcmZeroBuffers=1"))
        val second = stats.snapshot()
        assertTrue(second.contains("pcmBuffers=0 pcmBytes=0 pcmSamples=0"))
        assertTrue(second.contains("au=0 auBytes=0 auMin=0 auMax=0"))
    }

    @Test fun timesActualSubmittedPtsAndKeepsPendingAcrossWindows() {
        var now = 1_000_000L
        val stats = AacDecodeDiagnostics { now }
        stats.queued(21_333, 5_000)
        now = 11_000_000L
        assertTrue(stats.snapshot().contains("oldestPendingMs=10"))
        now = 31_000_000L
        stats.pcm(byteArrayOf(1, 0), 0, 2, 21_333)
        val line = stats.snapshot()
        assertTrue(line.contains("submitToOutputMaxUs=30000"))
        assertTrue(line.contains("pendingTimings=0"))
        assertTrue(line.contains("sinceInputMs=30 sinceOutputMs=0"))
    }

    @Test fun brokenDecoderDoesNotGrowDiagnosticStorageAndErrorsRemainDistinct() {
        var now = 1_000_000L
        val stats = AacDecodeDiagnostics { now }
        repeat(200) {
            stats.queued(it.toLong(), 1000)
            now += 1_000_000
        }
        stats.inputDequeued(-1, 10_000_000)
        stats.inputMissing()
        stats.oversize()
        stats.noCodec()
        stats.outputPolled(true, 2000)
        stats.emptyOutput()
        stats.outputMissing()
        val line = stats.snapshot()
        assertTrue(line.contains("inputWaitMaxUs=10000 inputUnavailable=1 inputBufferMissing=1 inputOversize=1 codecMissing=1"))
        assertTrue(line.contains("outputNotReady=1 emptyOutput=1 outputBufferMissing=1"))
        assertTrue(line.contains("pendingTimings=128 timingEvictions=72"))
    }
}
