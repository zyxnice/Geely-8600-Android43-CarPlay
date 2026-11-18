package com.shilapi.xcertplay.media

import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaCodec
import com.shilapi.xcertplay.airplay.AudioCodecKind
import com.shilapi.xcertplay.airplay.AudioFormat
import java.lang.reflect.InvocationTargetException
import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowAudioTrack
import org.robolectric.shadows.ShadowMediaCodec

/** Exercises the actual renderer's ownership contract, not AAC decoding or audible playback. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE,
    shadows = [WirelessAacCodecOwnershipTest.Codec::class, WirelessAacCodecOwnershipTest.Track::class])
class WirelessAacCodecOwnershipTest {
    @Before fun reset() {
        events.clear()
        infos.clear()
        available = true
        missing = false
        invalidRange = false
        failWrite = false
        delivered = byteArrayOf()
        output = ByteBuffer.wrap(byteArrayOf(9, 9, 1, 2, 3, 4, 9, 9))
    }

    @Test fun wifiCopiesThenReleasesBeforeBlockingWriteAndReusesBufferInfo() {
        val renderer = renderer(wifi = true)
        drain(renderer)
        assertEquals(listOf("release", "write"), events)
        // release overwrites codec memory: AudioTrack must receive the private PCM copy.
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), delivered)
        drain(renderer)
        assertTrue(infos.size >= 3)
        assertTrue(infos.all { it === infos.first() })
    }

    @Test fun usbKeepsItsExistingWriteThenReleaseOrderAndAllocationPath() {
        val renderer = renderer(wifi = false)
        drain(renderer)
        assertEquals(listOf("write", "release"), events)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), delivered)
        val previous = infos.last()
        drain(renderer)
        assertNotSame(previous, infos.last())
    }

    @Test fun wifiReleasesTheBufferEvenWhenCopyingFails() {
        invalidRange = true
        assertThrows(InvocationTargetException::class.java) { drain(renderer(wifi = true)) }
        assertEquals(listOf("release"), events)
    }

    @Test fun wifiReturnsMissingOutputWithoutWritingOrReleasingTwice() {
        missing = true
        drain(renderer(wifi = true))
        assertEquals(listOf("release"), events)
    }

    @Test fun wifiWriteFailureCannotKeepTheCodecBufferOwned() {
        failWrite = true
        assertThrows(InvocationTargetException::class.java) { drain(renderer(wifi = true)) }
        assertEquals(listOf("release", "write"), events)
    }

    private fun renderer(wifi: Boolean): Any {
        val context = RuntimeEnvironment.getApplication()
        val report: (String) -> Unit = {}
        val focusClass = Class.forName("com.shilapi.xcertplay.media.AudioFocusCoordinator")
        val focus = focusClass.declaredConstructors.single { it.parameterCount == 4 }.apply {
            isAccessible = true
        }.newInstance(context, false, report, false)
        val type = Class.forName("com.shilapi.xcertplay.media.AudioRenderer")
        val renderer = type.declaredConstructors.single { it.parameterCount == 12 }.apply {
            isAccessible = true
        }.newInstance(AudioFormat(AudioCodecKind.AAC_LC, 48_000, 2, 102, "media"),
            false, false, 0, 0, focus, AudioManager.STREAM_MUSIC, 640, report, null, false, wifi)
        @Suppress("DEPRECATION")
        val track = AudioTrack(AudioManager.STREAM_MUSIC, 48_000,
            android.media.AudioFormat.CHANNEL_OUT_STEREO, android.media.AudioFormat.ENCODING_PCM_16BIT,
            8192, AudioTrack.MODE_STREAM)
        type.getDeclaredField("track").apply { isAccessible = true }.set(renderer, track)
        type.getDeclaredField("fadeApplied").apply { isAccessible = true }.setBoolean(renderer, true)
        type.getDeclaredField("playbackStarted").apply { isAccessible = true }.setBoolean(renderer, true)
        return renderer
    }

    private fun drain(renderer: Any) {
        val codec = MediaCodec.createDecoderByType("audio/mp4a-latm")
        try {
            renderer.javaClass.getDeclaredMethod("drainCodec", MediaCodec::class.java).apply {
                isAccessible = true
            }.invoke(renderer, codec)
        } finally {
            codec.release()
        }
    }

    @Implements(MediaCodec::class)
    class Codec : ShadowMediaCodec() {
        @Implementation fun dequeueOutputBuffer(info: MediaCodec.BufferInfo, timeoutUs: Long): Int {
            infos.add(info)
            if (!available) return MediaCodec.INFO_TRY_AGAIN_LATER
            available = false
            info.set(if (invalidRange) 99 else 2, 4, 21_333, 0)
            return 0
        }

        @Implementation fun getOutputBuffer(index: Int): ByteBuffer? = if (missing) null else output

        @Implementation override fun releaseOutputBuffer(index: Int, render: Boolean) {
            events.add("release")
            output.clear()
            while (output.hasRemaining()) output.put(0)
        }
    }

    @Implements(AudioTrack::class)
    class Track : ShadowAudioTrack() {
        @Implementation override fun native_write_byte(data: ByteArray, offset: Int, size: Int,
            format: Int, blocking: Boolean): Int {
            events.add("write")
            if (failWrite) throw IllegalStateException("simulated track write failure")
            delivered = data.copyOfRange(offset, offset + size)
            return size
        }
    }

    companion object {
        private val events = ArrayList<String>()
        private val infos = ArrayList<MediaCodec.BufferInfo>()
        private var available = true
        private var missing = false
        private var invalidRange = false
        private var failWrite = false
        private var delivered = byteArrayOf()
        private var output = ByteBuffer.allocate(8)
    }
}
