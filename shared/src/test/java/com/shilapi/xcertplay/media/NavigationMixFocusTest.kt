package com.shilapi.xcertplay.media

import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class NavigationMixFocusTest {
    @Test fun sinkShutdownCannotResumeDeferredNavigationWhenPhoneCleanupArrivesFirst() {
        val f = Fixture(ducking = false)
        val phone = RecordingTrack()
        try {
            f.coordinator.acquireLegacy(phone, AudioChannel.PHONE, 0)
            val call = f.shadow.lastAudioFocusRequest
            f.coordinator.acquireLegacy(f.navigation, AudioChannel.NAVIGATION, 11)
            f.coordinator.close()
            f.coordinator.release(phone)
            assertSame(call, f.shadow.lastAudioFocusRequest)
            assertEquals(0f, f.navigation.lastGain)
            f.coordinator.acquireLegacy(f.music, AudioChannel.MEDIA, 23)
            assertSame(call, f.shadow.lastAudioFocusRequest)
        } finally {
            f.coordinator.release(phone)
            phone.release()
            f.close()
        }
    }

    @Test fun phoneFirstDefersNavigationAndMusicUntilCallTrackReleases() {
        val f = Fixture(ducking = false)
        val phone = RecordingTrack()
        try {
            f.coordinator.acquireLegacy(phone, AudioChannel.PHONE, 0)
            val call = f.shadow.lastAudioFocusRequest
            f.coordinator.acquireLegacy(f.navigation, AudioChannel.NAVIGATION, 11)
            f.coordinator.acquireLegacy(f.music, AudioChannel.MEDIA, 23)
            assertSame(call, f.shadow.lastAudioFocusRequest)
            assertEquals(0f, f.navigation.lastGain)
            assertEquals(0f, f.music.lastGain)
            f.coordinator.release(phone)
            assertNotSame(call, f.shadow.lastAudioFocusRequest)
            assertEquals(23, f.shadow.lastAudioFocusRequest.streamType)
            assertEquals(1f, f.navigation.lastGain)
            assertEquals(1f, f.music.lastGain)
        } finally {
            f.coordinator.release(phone)
            phone.release()
            f.close()
        }
    }

    @Test fun assistantAndPhoneBothMustReleaseBeforeDeferredNavigationCanRequestMediaFocus() {
        val f = Fixture(ducking = false)
        val phone = RecordingTrack()
        val assistant = RecordingTrack()
        try {
            f.coordinator.acquireLegacy(assistant, AudioChannel.ASSISTANT, 3)
            f.coordinator.acquireLegacy(f.navigation, AudioChannel.NAVIGATION, 11)
            f.coordinator.acquireLegacy(phone, AudioChannel.PHONE, 0)
            val call = f.shadow.lastAudioFocusRequest
            f.coordinator.release(assistant)
            assertSame(call, f.shadow.lastAudioFocusRequest)
            assertEquals(0f, f.navigation.lastGain)
            f.coordinator.release(phone)
            assertEquals(23, f.shadow.lastAudioFocusRequest.streamType)
            assertEquals(1f, f.navigation.lastGain)
        } finally {
            f.coordinator.release(assistant)
            f.coordinator.release(phone)
            assistant.release()
            phone.release()
            f.close()
        }
    }

    @Test fun mixingSharesOneMediaLeaseAndNavigationReleaseDoesNotAbandonMusicFocus() {
        val f = Fixture(ducking = false)
        try {
            f.coordinator.acquireLegacy(f.music, AudioChannel.MEDIA, 23)
            val mediaRequest = f.shadow.lastAudioFocusRequest
            assertEquals(23, mediaRequest.streamType)
            assertEquals(AudioManager.AUDIOFOCUS_GAIN, mediaRequest.durationHint)
            f.coordinator.acquireLegacy(f.navigation, AudioChannel.NAVIGATION, 11)
            assertSame(mediaRequest, f.shadow.lastAudioFocusRequest)
            assertEquals(1f, f.music.lastGain)
            assertEquals(1f, f.navigation.lastGain)
            f.coordinator.release(f.navigation)
            assertNull(f.shadow.lastAbandonedAudioFocusListener)
            f.coordinator.release(f.music)
            assertSame(mediaRequest.listener, f.shadow.lastAbandonedAudioFocusListener)
        } finally { f.close() }
    }

    @Test fun enablingDuckingRestoresIndependentNavigationFocusOnEleven() {
        val f = Fixture(ducking = true)
        try {
            f.coordinator.acquireLegacy(f.music, AudioChannel.MEDIA, 23)
            val mediaRequest = f.shadow.lastAudioFocusRequest
            f.coordinator.acquireLegacy(f.navigation, AudioChannel.NAVIGATION, 11)
            val navigationRequest = f.shadow.lastAudioFocusRequest
            assertNotSame(mediaRequest, navigationRequest)
            assertEquals(11, navigationRequest.streamType)
            assertEquals(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK, navigationRequest.durationHint)
        } finally { f.close() }
    }

    @Test fun navigationWithoutMusicRequestsMediaFocusAndLaterMusicReusesIt() {
        val f = Fixture(ducking = false)
        try {
            f.coordinator.acquireLegacy(f.navigation, AudioChannel.NAVIGATION, 11)
            val request = f.shadow.lastAudioFocusRequest
            assertEquals(23, request.streamType)
            assertEquals(AudioManager.AUDIOFOCUS_GAIN, request.durationHint)
            f.coordinator.acquireLegacy(f.music, AudioChannel.MEDIA, 23)
            assertSame(request, f.shadow.lastAudioFocusRequest)
            f.coordinator.release(f.music)
            assertNull(f.shadow.lastAbandonedAudioFocusListener)
            f.coordinator.release(f.navigation)
            assertSame(request.listener, f.shadow.lastAbandonedAudioFocusListener)
        } finally { f.close() }
    }

    @Test fun callsAndExternalPromptsStillControlBothMixedTracks() {
        val f = Fixture(ducking = false)
        val phone = RecordingTrack()
        try {
            f.coordinator.acquireLegacy(f.music, AudioChannel.MEDIA, 23)
            f.coordinator.acquireLegacy(f.navigation, AudioChannel.NAVIGATION, 11)
            val mediaRequest = f.shadow.lastAudioFocusRequest
            f.coordinator.acquireLegacy(phone, AudioChannel.PHONE, 0)
            assertEquals(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT, f.shadow.lastAudioFocusRequest.durationHint)
            mediaRequest.listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
            assertEquals(0f, f.music.lastGain)
            assertEquals(0f, f.navigation.lastGain)
            mediaRequest.listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
            assertEquals(1f, f.music.lastGain)
            assertEquals(1f, f.navigation.lastGain)
            mediaRequest.listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)
            assertEquals(0.2f, f.music.lastGain)
            assertEquals(0.2f, f.navigation.lastGain)
        } finally {
            f.coordinator.release(phone)
            phone.release()
            f.close()
        }
    }

    @Test fun deniedSharedFocusKeepsNavigationMutedAndOldCallbacksCannotChangeNewLease() {
        val f = Fixture(ducking = false)
        try {
            f.shadow.setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
            f.coordinator.acquireLegacy(f.navigation, AudioChannel.NAVIGATION, 11)
            val denied = f.shadow.lastAudioFocusRequest
            assertEquals(0f, f.navigation.lastGain)
            f.coordinator.release(f.navigation)
            f.shadow.setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
            f.coordinator.acquireLegacy(f.navigation, AudioChannel.NAVIGATION, 11)
            assertEquals(1f, f.navigation.lastGain)
            denied.listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)
            assertEquals(1f, f.navigation.lastGain)
        } finally { f.close() }
    }

    private class Fixture(ducking: Boolean) {
        private val context = RuntimeEnvironment.getApplication()
        val shadow = shadowOf(context.getSystemService(Context.AUDIO_SERVICE) as AudioManager)
        val music = RecordingTrack()
        val navigation = RecordingTrack()
        val coordinator = AudioFocusCoordinator(context, true, {}, true, ducking, 23)

        fun close() {
            coordinator.release(navigation)
            coordinator.release(music)
            navigation.release()
            music.release()
        }
    }

    /** Real coordinator/AudioManager entry with observable track gain instead of a native speaker. */
    @Suppress("DEPRECATION")
    private class RecordingTrack : AudioTrack(AudioManager.STREAM_MUSIC, 48000,
        AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT, 4096, AudioTrack.MODE_STREAM) {
        var lastGain = 1f
        override fun setVolume(gain: Float): Int { lastGain = gain; return SUCCESS }
        override fun setStereoVolume(left: Float, right: Float): Int { lastGain = left; return SUCCESS }
    }
}
