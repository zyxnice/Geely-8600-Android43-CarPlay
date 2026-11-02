package com.shilapi.xcertplay.media

import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.Resetter
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowAudioManager
import org.robolectric.shadows.ShadowAudioTrack

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE, shadows = [
    GeelyNavigationAudioFocusTest.RecordingAudioManager::class,
    GeelyNavigationAudioFocusTest.RecordingAudioTrack::class,
])
class GeelyNavigationAudioFocusTest {
    @Before fun setUp() { RecordingAudioManager.resetRequests() }

    @Test fun sharedNavigationTracksAbandonOnlyAfterTheLastBurstEnds() {
        val coordinator = coordinator()
        val first = track()
        val second = track()
        coordinator.acquireLegacy(first, AudioChannel.NAVIGATION, 11, burstScoped = true)
        coordinator.acquireLegacy(second, AudioChannel.NAVIGATION, 11, burstScoped = true)
        assertEquals(1, RecordingAudioManager.requests)
        assertEquals(11, RecordingAudioManager.requestedStream)
        assertEquals(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK, RecordingAudioManager.requestedGain)
        coordinator.release(first)
        assertEquals(0, RecordingAudioManager.abandons)
        coordinator.release(second)
        assertEquals(1, RecordingAudioManager.abandons)
        first.release()
        second.release()
    }

    @Test fun deniedSharedBurstStaysMutedAndOnlyANewBurstRetries() {
        RecordingAudioManager.response = AudioManager.AUDIOFOCUS_REQUEST_FAILED
        val coordinator = coordinator()
        val first = track()
        val second = track()
        coordinator.acquireLegacy(first, AudioChannel.NAVIGATION, 11, burstScoped = true)
        coordinator.acquireLegacy(first, AudioChannel.NAVIGATION, 11, burstScoped = true)
        coordinator.acquireLegacy(second, AudioChannel.NAVIGATION, 11, burstScoped = true)
        assertEquals(1, RecordingAudioManager.requests)
        assertEquals(0f, volume(first), 0f)
        assertEquals(0f, volume(second), 0f)
        coordinator.release(first)
        coordinator.release(second)
        RecordingAudioManager.response = AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        coordinator.acquireLegacy(first, AudioChannel.NAVIGATION, 11, burstScoped = true)
        assertEquals(2, RecordingAudioManager.requests)
        assertEquals(1f, volume(first), 0f)
        coordinator.release(first)
        first.release()
        second.release()
    }

    @Test fun lossDoesNotRestoreOldDirectionsOnGainAndStaleCallbacksCannotMuteANewBurst() {
        val coordinator = coordinator()
        val track = track()
        coordinator.acquireLegacy(track, AudioChannel.NAVIGATION, 11, burstScoped = true)
        val oldListener = RecordingAudioManager.lastListener!!
        oldListener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        assertEquals(0f, volume(track), 0f)
        oldListener.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        assertEquals(0f, volume(track), 0f)
        assertEquals(1, RecordingAudioManager.requests)
        coordinator.release(track)
        coordinator.acquireLegacy(track, AudioChannel.NAVIGATION, 11, burstScoped = true)
        assertEquals(1f, volume(track), 0f)
        oldListener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)
        assertEquals(1f, volume(track), 0f)
        coordinator.release(track)
        track.release()
    }

    @Test fun mediaFocusStillRestoresItsVolumeOnGain() {
        val coordinator = coordinator()
        val track = track()
        coordinator.acquireLegacy(track, AudioChannel.MEDIA, 23)
        val listener = RecordingAudioManager.lastListener!!
        listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        assertEquals(0f, volume(track), 0f)
        listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        assertEquals(1f, volume(track), 0f)
        coordinator.release(track)
        track.release()
    }

    private fun coordinator() = AudioFocusCoordinator(RuntimeEnvironment.getApplication(),
        enabled = true, separateGeelyFocus = true)

    @Suppress("DEPRECATION")
    private fun track() = AudioTrack(AudioManager.STREAM_MUSIC, 16000,
        AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT, 2048, AudioTrack.MODE_STREAM)

    private fun volume(track: AudioTrack): Float = Shadow.extract<RecordingAudioTrack>(track).volume

    @Implements(AudioManager::class)
    class RecordingAudioManager : ShadowAudioManager() {
        @Implementation
        override fun requestAudioFocus(listener: AudioManager.OnAudioFocusChangeListener?,
            streamType: Int, durationHint: Int): Int {
            requests++
            lastListener = listener
            requestedStream = streamType
            requestedGain = durationHint
            return response
        }

        @Implementation
        override fun abandonAudioFocus(listener: AudioManager.OnAudioFocusChangeListener?): Int {
            abandons++
            return AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }

        companion object {
            var requests = 0
            var abandons = 0
            var requestedStream = -1
            var requestedGain = -1
            var response = AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            var lastListener: AudioManager.OnAudioFocusChangeListener? = null

            @Resetter @JvmStatic fun resetRequests() {
                requests = 0
                abandons = 0
                requestedStream = -1
                requestedGain = -1
                response = AudioManager.AUDIOFOCUS_REQUEST_GRANTED
                lastListener = null
            }
        }
    }

    @Implements(AudioTrack::class)
    class RecordingAudioTrack : ShadowAudioTrack() {
        var volume = 1f
            private set

        @Implementation
        fun setVolume(nextVolume: Float): Int {
            volume = nextVolume
            return AudioTrack.SUCCESS
        }
    }
}
