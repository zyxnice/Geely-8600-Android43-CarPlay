package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.airplay.AudioCodecKind
import com.shilapi.xcertplay.airplay.AudioFormat
import org.junit.Assert.*
import org.junit.Test

class H53WirelessAacPlaybackTest {
    private val music = AudioFormat(AudioCodecKind.AAC_LC, 48_000, 2, 102, "media")

    @Test fun api18ExhaustedMusicMustRecoverWithoutUnavailableUnderrunCounter() {
        val progress = AudioBufferProgress(4)
        progress.written(192_000)
        assertFalse(progress.shouldRebuffer(true, true, false, true, 47_999, allowHeadOnly = true))
        assertFalse(progress.shouldRebuffer(true, true, false, false, 48_000, allowHeadOnly = true))
        // API18 reports zero underruns even when the playback head proves exhaustion.
        assertTrue(progress.shouldRebuffer(true, true, false, true, 48_000, allowHeadOnly = true))
        progress.written(4096)
        assertFalse(progress.shouldRebuffer(true, true, false, true, 48_000, allowHeadOnly = true))
    }

    @Test fun usbRetainsItsExistingUnderrunRequirement() {
        val progress = AudioBufferProgress(4)
        progress.written(192_000)
        assertFalse(progress.shouldRebuffer(true, true, false, true, 48_000))
        assertTrue(progress.shouldRebuffer(true, true, true, true, 48_000))
        assertFalse(usesH53WirelessAac(false, music))
    }

    @Test fun wirelessAacPathIsLimitedToNegotiatedStereoMedia() {
        assertTrue(usesH53WirelessAac(true, music))
        assertTrue(usesH53WirelessAac(true, music.copy(sampleRate = 44_100)))
        assertFalse(usesH53WirelessAac(true, music.copy(sampleRate = 32_000)))
        assertFalse(usesH53WirelessAac(true, music.copy(channels = 1)))
        assertFalse(usesH53WirelessAac(true, music.copy(codec = AudioCodecKind.LPCM, payloadType = 100)))
        assertFalse(usesH53WirelessAac(true, music.copy(codec = AudioCodecKind.OPUS, payloadType = 101)))
        for (type in listOf("default", "alert", "telephony", "speechrecognition")) {
            assertFalse(usesH53WirelessAac(true, music.copy(audioType = type)))
        }
    }

    @Test fun doesNotRebufferBeforeAnyAudioOrWhenStoppedOrSpeaking() {
        val progress = AudioBufferProgress(4)
        assertFalse(progress.shouldRebuffer(true, true, false, true, 0, allowHeadOnly = true))
        progress.written(4096)
        assertFalse(progress.shouldRebuffer(true, false, false, true, 1024, allowHeadOnly = true))
        assertFalse(progress.shouldRebuffer(false, true, false, true, 1024, allowHeadOnly = true))
    }

    @Test fun defaultPcmPlanStartsAt300MillisecondsAtBothNegotiatedRates() {
        for (rate in listOf(44_100, 48_000)) {
            val plan = MediaAudioBuffer.plan(true, rate, 2, 7_680,
                mediaMillis = MediaAudioBuffer.DEFAULT_MILLIS)
            assertEquals(rate * 4 * 300 / 1000, plan.startBytes)
            assertEquals(rate * 4 * 500 / 1000, plan.trackBufferBytes)
        }
    }

    @Test fun h53MinimumKeepsTheOriginalCapacityHeadroom() {
        for (rate in listOf(44_100, 48_000)) {
            val bytesPerSecond = rate * 4
            val plan = MediaAudioBuffer.plan(true, rate, 2, bytesPerSecond * 160 / 1000,
                mediaMillis = MediaAudioBuffer.DEFAULT_MILLIS)
            assertEquals(bytesPerSecond * 300 / 1000, plan.startBytes)
            assertEquals(bytesPerSecond * 640 / 1000, plan.trackBufferBytes)
        }
    }

    @Test fun playbackHeadWrapStillAllowsRecoveryOnApi18() {
        val progress = AudioBufferProgress(4)
        repeat(16) { progress.written(1_073_741_824) }
        progress.written(4096)
        assertEquals(4100, progress.queuedBytes(-1))
        assertFalse(progress.shouldRebuffer(true, true, false, true, 1023, allowHeadOnly = true))
        assertTrue(progress.shouldRebuffer(true, true, false, true, 1024, allowHeadOnly = true))
    }

    @Test fun feedbackHoldsAtDrainedHeadUntilNewPcmActuallyPlays() {
        val clock = PlaybackHeadRtpClock()
        clock.recordWrite(0, 512, 1024)
        clock.recordWrite(512, 512, 1536)
        clock.advance(0)
        assertNull(clock.rtpSample())

        clock.advance(960)
        assertEquals(1984L, clock.rtpSample())
        clock.advance(1024)
        assertEquals(2048L, clock.rtpSample())

        // Packets were missed. The newly written PCM is prebuffered while the head is paused.
        clock.recordWrite(1024, 1024, 8192)
        clock.advance(1024)
        assertEquals(2048L, clock.rtpSample())
        clock.advance(1025)
        assertEquals(8193L, clock.rtpSample())
    }

    @Test fun feedbackHasIndependentUnsignedTrackAndRtpWraps() {
        val clock = PlaybackHeadRtpClock()
        val wrap = 1L shl 32
        clock.recordWrite(wrap - 8, 8, wrap - 3)
        clock.recordWrite(wrap, 8, 5)
        clock.advance(-2)
        assertEquals(wrap - 2, clock.playedFrames)
        assertEquals(3L, clock.rtpSample())
        clock.advance(4)
        assertEquals(wrap + 4, clock.playedFrames)
        assertEquals(9L, clock.rtpSample())
        // A head read can catch AudioTrack before the blocking write has published its mapping.
        clock.advance(12)
        assertEquals(wrap + 8, clock.playedFrames)
        assertEquals(13L, clock.rtpSample())
    }

    @Test fun codecPtsPreservesTheInputRtpSampleAtBothSupportedRates() {
        for (rate in listOf(44_100, 48_000)) {
            for (sample in listOf(0L, 1L, 1024L, 0xffff_ffffL)) {
                val ptsUs = sample * 1_000_000L / rate
                assertEquals(sample, PlaybackHeadRtpClock.sampleFromPts(ptsUs, rate))
            }
        }
    }
}
