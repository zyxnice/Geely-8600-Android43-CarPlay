package com.shilapi.xcertplay.media

import org.junit.Assert.*
import org.junit.Test

class VideoRecoveryPolicyTest {
    @Test fun bOutputStallRequiresAcceptedInputsAndTimeAndIsConsumedOnce() {
        val policy = VideoRecoveryPolicy(true, monitorProgress = true)
        policy.onRendered()
        repeat(29) { policy.onInputQueued(100) }
        assertFalse(policy.consumeOutputStall(2_000_000_100))
        policy.onInputQueued(200)
        assertFalse(policy.consumeOutputStall(1_500_000_099))
        assertTrue(policy.consumeOutputStall(1_500_000_100))
        assertFalse(policy.consumeOutputStall(3_000_000_100))
        assertEquals(VideoRecoveryPolicy.Action.KEEP_DECODER, policy.begin(true, true))
    }

    @Test fun actualOutputAndSurfaceReplacementRetireOldStallEvidence() {
        val policy = VideoRecoveryPolicy(true, monitorProgress = true)
        repeat(30) { policy.onInputQueued(1) }
        policy.onRendered()
        assertFalse(policy.consumeOutputStall(2_000_000_001))
        repeat(30) { policy.onInputQueued(3_000_000_001) }
        policy.onDecoderChanged()
        assertFalse(policy.consumeOutputStall(5_000_000_001))
    }

    @Test fun outputWatchdogDoesNotRunForExistingCOrForAnActiveRecovery() {
        val legacy = VideoRecoveryPolicy(true)
        repeat(30) { legacy.onInputQueued(1) }
        assertFalse(legacy.consumeOutputStall(2_000_000_001))
        val policy = VideoRecoveryPolicy(true, monitorProgress = true)
        policy.onRendered()
        policy.begin(true, true)
        repeat(30) { policy.onInputQueued(1) }
        assertFalse(policy.consumeOutputStall(2_000_000_001))
    }

    @Test fun missingKeyframeIsReportedWithoutDeclaringCodecFailureAndInputResetsIt() {
        val policy = VideoRecoveryPolicy(true, monitorProgress = true)
        policy.onRendered()
        policy.begin(true, true)
        assertFalse(policy.reportKeyFrameWaitIfDue(1))
        assertFalse(policy.reportKeyFrameWaitIfDue(5_000_000_000))
        assertTrue(policy.reportKeyFrameWaitIfDue(5_000_000_001))
        assertFalse(policy.reportKeyFrameWaitIfDue(5_000_000_002))
        assertFalse(policy.disableIfOutputTimedOut(Long.MAX_VALUE))
        policy.onInputQueued(6_000_000_001)
        assertFalse(policy.reportKeyFrameWaitIfDue(12_000_000_001))
        assertFalse(VideoRecoveryPolicy(true).reportKeyFrameWaitIfDue(Long.MAX_VALUE))
    }

    @Test fun outputBatchRendersOnlyFreshRecoveryOutputAndCompletesRecovery() {
        val policy = VideoRecoveryPolicy(true, monitorProgress = true)
        val chain = VideoReferenceChain()
        policy.onRendered()
        assertEquals(VideoRecoveryPolicy.Action.KEEP_DECODER, policy.begin(true, true))
        chain.reset()
        val idr = byteArrayOf(0, 0, 0, 1, 0x65)
        val dependent = byteArrayOf(0, 0, 0, 1, 0x41)
        val avc = com.shilapi.xcertplay.airplay.VideoCodec.H264
        assertFalse(chain.accepts(dependent, avc))
        assertTrue(chain.accepts(idr, avc))
        policy.onInputQueued(1_000_000)
        policy.onKeyFrameQueued(1_000, 1_000_000)
        chain.onQueued()
        val rendered = mutableListOf<Long>()
        val batch = VideoOutputBatch { _, pts, _, _, requested ->
            if (requested && policy.canRender(pts)) {
                rendered.add(pts)
                policy.onRendered()
            }
        }
        batch.offer(0, 900, 0, 100)
        batch.offer(1, 1_000, 0, 100)
        batch.offer(2, 1_100, 0, 100)
        batch.finish(true)
        assertEquals(listOf(1_100L), rendered)
        assertFalse(policy.canRender(900))
        assertFalse(policy.disableIfOutputTimedOut(Long.MAX_VALUE))
        assertTrue(chain.accepts(dependent, avc))
    }

    @Test fun disabledStrategyAndFirstOutputBeforeRecoveryAlwaysRebuild() {
        val disabled = VideoRecoveryPolicy(false)
        disabled.onRendered()
        assertEquals(VideoRecoveryPolicy.Action.REBUILD, disabled.begin(true, true))
        val enabled = VideoRecoveryPolicy(true)
        assertEquals(VideoRecoveryPolicy.Action.REBUILD, enabled.begin(true, true))
    }

    @Test fun onlyReferenceGapWithLiveDecoderCanKeepAnAlreadyRenderingCodec() {
        val policy = VideoRecoveryPolicy(true)
        policy.onRendered()
        assertEquals(VideoRecoveryPolicy.Action.REBUILD, policy.begin(false, true))
        assertEquals(VideoRecoveryPolicy.Action.REBUILD, policy.begin(true, false))
        assertEquals(VideoRecoveryPolicy.Action.KEEP_DECODER, policy.begin(true, true))
    }

    @Test fun oldOutputRemainsSuppressedUntilAcceptedNewKeyframeOutput() {
        val policy = VideoRecoveryPolicy(true)
        policy.onRendered()
        policy.begin(true, true)
        assertFalse(policy.canRender(999))
        policy.onKeyFrameQueued(1_000, 1_000_000)
        assertFalse(policy.canRender(999))
        assertTrue(policy.canRender(1_000))
        policy.onRendered()
        assertTrue(policy.canRender(1_001))
        // Delayed pre-recovery output must not reappear after the first good output.
        assertFalse(policy.canRender(999))
        assertFalse(policy.disableIfOutputTimedOut(Long.MAX_VALUE))
    }

    @Test fun noNewKeyframeMeansNoStaticScreenWatchdog() {
        val policy = VideoRecoveryPolicy(true, outputTimeoutNs = 100)
        policy.onRendered()
        assertFalse(policy.disableIfOutputTimedOut(10_000))
        policy.begin(true, true)
        assertFalse(policy.disableIfOutputTimedOut(10_000))
    }

    @Test fun acceptedKeyframeWithoutOutputDisablesSoftRecoveryForStreamLifetime() {
        val policy = VideoRecoveryPolicy(true, outputTimeoutNs = 100)
        policy.onRendered()
        policy.begin(true, true)
        policy.onKeyFrameQueued(10, 50)
        assertFalse(policy.disableIfOutputTimedOut(149))
        assertTrue(policy.disableIfOutputTimedOut(150))
        assertFalse(policy.disableIfOutputTimedOut(200))
        policy.onDecoderChanged()
        policy.onRendered()
        assertEquals(VideoRecoveryPolicy.Action.REBUILD, policy.begin(true, true))
    }

    @Test fun repeatedGapInvalidatesPendingKeyframeWithoutExtendingDeadline() {
        val policy = VideoRecoveryPolicy(true, outputTimeoutNs = 100)
        policy.onRendered()
        policy.begin(true, true)
        policy.onKeyFrameQueued(10, 50)
        assertEquals(VideoRecoveryPolicy.Action.ALREADY_WAITING, policy.begin(true, true))
        assertFalse(policy.canRender(10))
        policy.onKeyFrameQueued(20, 140)
        assertFalse(policy.canRender(10))
        assertTrue(policy.disableIfOutputTimedOut(150))
    }

    @Test fun formatOrSurfaceChangeRequiresFreshFirstOutputAndClearsOldTimestampBoundary() {
        val policy = VideoRecoveryPolicy(true)
        policy.onRendered()
        policy.begin(true, true)
        policy.onKeyFrameQueued(1_000, 1_000_000)
        policy.onDecoderChanged()
        assertTrue(policy.canRender(1))
        assertEquals(VideoRecoveryPolicy.Action.REBUILD, policy.begin(true, true))
        policy.onRendered()
        assertEquals(VideoRecoveryPolicy.Action.KEEP_DECODER, policy.begin(true, true))
    }

    @Test fun decoderExceptionDuringSoftAttemptDisablesTheFailedStrategy() {
        val policy = VideoRecoveryPolicy(true)
        policy.onRendered()
        policy.begin(true, true)
        policy.onDecoderFailure()
        policy.onRendered()
        assertEquals(VideoRecoveryPolicy.Action.REBUILD, policy.begin(true, true))
    }

    @Test fun hardInputFailureDuringSoftAttemptAlsoDisablesTheFailedStrategy() {
        val policy = VideoRecoveryPolicy(true)
        policy.onRendered()
        policy.begin(true, true)
        assertEquals(VideoRecoveryPolicy.Action.REBUILD, policy.begin(false, true))
        policy.onDecoderChanged()
        policy.onRendered()
        assertEquals(VideoRecoveryPolicy.Action.REBUILD, policy.begin(true, true))
    }
}
