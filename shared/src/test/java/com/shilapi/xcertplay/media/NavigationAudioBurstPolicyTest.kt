package com.shilapi.xcertplay.media

import org.junit.Assert.*
import org.junit.Test

class NavigationAudioBurstPolicyTest {
    private val quietNs = 500_000_000L

    @Test fun streamSetupAndAnEmptyInputQueueDoNotRequestFocus() {
        val policy = NavigationAudioBurstPolicy()
        assertFalse(policy.releaseIfIdle(quietNs * 2, 0, true, 0))
        assertTrue(policy.onPcm(quietNs * 3))
    }

    @Test fun packetGapDoesNotReleaseAudioStillInHardware() {
        val policy = NavigationAudioBurstPolicy()
        assertTrue(policy.onPcm(1))
        assertFalse(policy.releaseIfIdle(quietNs * 4, 200, true, 1))
        assertFalse(policy.releaseIfIdle(quietNs * 5, 0, true, 1))
        assertFalse(policy.releaseIfIdle(quietNs * 6 - 1, 0, true, 1))
        assertTrue(policy.releaseIfIdle(quietNs * 6, 0, true, 1))
    }

    @Test fun shortPauseAndNewPcmStayInTheSameBurst() {
        val policy = NavigationAudioBurstPolicy()
        assertTrue(policy.onPcm(1))
        assertFalse(policy.releaseIfIdle(100, 0, true, 1))
        assertFalse(policy.releaseIfIdle(quietNs - 1, 0, true, 1))
        assertFalse(policy.onPcm(quietNs))
        assertFalse(policy.releaseIfIdle(quietNs + 10, 0, true, quietNs))
        assertTrue(policy.releaseIfIdle(quietNs * 2 + 10, 0, true, quietNs))
    }

    @Test fun queuedInputOrDelayedDecoderOutputRestartsTheQuietWindow() {
        val policy = NavigationAudioBurstPolicy()
        policy.onPcm(1)
        assertFalse(policy.releaseIfIdle(10, 0, true, 1))
        assertFalse(policy.releaseIfIdle(quietNs, 0, false, quietNs))
        assertFalse(policy.releaseIfIdle(quietNs + 10, 0, true, quietNs))
        assertFalse(policy.onPcm(quietNs * 2))
        assertFalse(policy.releaseIfIdle(quietNs * 2 + 10, 0, true, quietNs))
        assertTrue(policy.releaseIfIdle(quietNs * 3 + 10, 0, true, quietNs))
    }

    @Test fun deniedFocusIsNotRetriedWithinABurstButNextBurstCanRequest() {
        val policy = NavigationAudioBurstPolicy()
        var requests = 0
        fun writeMutedPcm(now: Long) {
            if (policy.onPcm(now)) requests++ // Simulated AudioService always denies.
        }
        writeMutedPcm(1)
        writeMutedPcm(quietNs)
        writeMutedPcm(quietNs * 2)
        assertEquals(1, requests)
        assertFalse(policy.releaseIfIdle(quietNs * 2 + 1, 0, true, quietNs * 2))
        assertTrue(policy.releaseIfIdle(quietNs * 3 + 1, 0, true, quietNs * 2))
        assertFalse(policy.releaseIfIdle(quietNs * 4, 0, true, quietNs * 2))
        writeMutedPcm(quietNs * 5)
        assertEquals(2, requests)
    }

    @Test fun freshInputPreventsReleaseWhileDecoderHasNotProducedItsPcm() {
        val policy = NavigationAudioBurstPolicy()
        policy.onPcm(1)
        assertFalse(policy.releaseIfIdle(10, 0, true, 1))
        assertFalse(policy.releaseIfIdle(quietNs, 0, true, quietNs - 1))
        assertTrue(policy.releaseIfIdle(quietNs * 2, 0, true, quietNs - 1))
    }
}
