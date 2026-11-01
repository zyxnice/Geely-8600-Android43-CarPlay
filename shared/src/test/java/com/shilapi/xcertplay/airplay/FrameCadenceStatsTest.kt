package com.shilapi.xcertplay.airplay

import org.junit.Assert.*
import org.junit.Test

class FrameCadenceStatsTest {
    @Test fun secondsAndGapBucketsKeepBurstAndPauseEvidenceSeparate() {
        val stats = FrameCadenceStats(0)
        listOf(0L, 5, 22, 56, 106, 206, 456, 1456, 3000, 5100).forEach {
            stats.frame(it * 1_000_000)
        }
        val line = stats.reportAndReset(5_100_000_000)
        assertTrue(line.contains("frames=10 windowMs=5100 perSec=[7,1,0,1,0] late=1"))
        assertTrue(line.contains("gapBins5_17_34_50_100_250_1000_over=[1,1,1,1,1,1,1,2]"))
    }

    @Test fun windowResetPreservesGapButDecoderResetRetiresIt() {
        val stats = FrameCadenceStats(0)
        stats.frame(1_000_000)
        stats.reportAndReset(5_000_000_000)
        stats.frame(6_000_000_000)
        assertTrue(stats.reportAndReset(6_000_000_000).contains("gapBins5_17_34_50_100_250_1000_over=[0,0,0,0,0,0,0,1]"))
        stats.resetContinuity()
        stats.frame(20_000_000_000)
        assertTrue(stats.reportAndReset(20_000_000_000).contains("gapBins5_17_34_50_100_250_1000_over=[0,0,0,0,0,0,0,0]"))
    }

    @Test fun decoderReplacementDoesNotDiscardEarlierFramesInTheSameWindow() {
        val stats = FrameCadenceStats(0)
        stats.frame(10_000_000)
        stats.frame(20_000_000)
        stats.resetContinuity()
        stats.frame(3_000_000_000)
        val line = stats.reportAndReset(5_000_000_000)
        assertTrue(line.contains("frames=3 windowMs=5000 perSec=[2,0,0,1,0]"))
        assertTrue(line.contains("gapBins5_17_34_50_100_250_1000_over=[0,1,0,0,0,0,0,0]"))
    }
}
