package com.shilapi.xcertplay.airplay

import java.io.Closeable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class CarPlayAudioStreamIsolationTest {
    @Test fun guidanceSetupAndMediaReplacementKeepTheOtherAudioStreamAlive() {
        val session = session()
        val engine = CarPlayMediaEngine(object : MediaSink {})
        try {
            assertNotNull(engine.onAudio(session, 100, setup("media")))
            val streams = streams(engine)
            val mediaKey = CarPlayMediaEngine.StreamKey(session, 100, "media")
            val firstMedia = streams[mediaKey]
            assertNotNull(engine.onAudio(session, 100, setup("default")))
            val guidanceKey = CarPlayMediaEngine.StreamKey(session, 100, "default")
            val guidance = streams[guidanceKey]
            assertSame(firstMedia, streams[mediaKey])
            assertEquals(2, streams.size)
            assertNotNull(engine.onAudio(session, 100, setup("MEDIA")))
            assertNotSame(firstMedia, streams[mediaKey])
            assertSame(guidance, streams[guidanceKey])
            assertEquals(2, streams.size)
        } finally {
            engine.onSessionClosed(session)
            session.close()
        }
    }

    @Test fun typeTeardownClosesEveryVariantButKeepsOtherTypes() {
        val stopped = mutableListOf<AudioStreamId>()
        val engine = CarPlayMediaEngine(object : MediaSink {
            override fun onAudioStopped(id: AudioStreamId) { stopped.add(id) }
        })
        val session = session()
        try {
            assertNotNull(engine.onAudio(session, 100, setup("media")))
            assertNotNull(engine.onAudio(session, 100, setup("default")))
            assertNotNull(engine.onAudio(session, 102, setup("media")))
            stopped.clear()
            engine.onTeardown(session, 100)
            assertEquals(setOf(AudioStreamId(100, "media"), AudioStreamId(100, "default")), stopped.toSet())
            assertEquals(setOf(CarPlayMediaEngine.StreamKey(session, 102, "media")), streams(engine).keys)
        } finally {
            engine.onSessionClosed(session)
            session.close()
        }
    }

    @Test fun feedbackUsesBoundPlaybackHeadAndHoldsWhileHeadIsUnchanged() {
        val arrived = CountDownLatch(1)
        val feedbackDiagnostics = mutableListOf<String>()
        var position: AudioPlaybackPosition? = null
        val sink = object : MediaSink {
            override fun audioPlaybackPositionSource(id: AudioStreamId): () -> AudioPlaybackPosition? = { position }
            override fun onAudioFeedbackDiagnostic(message: String) { feedbackDiagnostics += message }
            override fun onAudioRtp(id: AudioStreamId, format: AudioFormat, rtp: ByteArray, sample: Int) {
                arrived.countDown()
            }
        }
        val engine = CarPlayMediaEngine(sink)
        val session = session()
        try {
            val response = engine.onAudio(session, 102, setup("media") + ("audioFormat" to 0x800000L))!!
            val key = AirPlayCrypto.hkdfSha512(session.sharedSecret!!, "DataStream-Salt42".toByteArray(),
                "DataStream-Output-Encryption-Key".toByteArray(), 32)
            val packet = MicrophonePacketizer.sealPacket(key, 102, MicrophoneCounters(timestamp = 3072),
                ByteArray(8), 1024)
            DatagramSocket().use { socket ->
                socket.send(DatagramPacket(packet, packet.size, InetAddress.getByName("127.0.0.1"),
                    response["dataPort"] as Int))
            }
            assertTrue("The RTP packet did not reach the media sink", arrived.await(3, TimeUnit.SECONDS))
            fun feedbackEntry() = (engine.onFeedback(session)?.get("streams") as List<*>).single() as Map<*, *>
            assertEquals(3072L, feedbackEntry()["sampleTime"])
            val firstRead = System.nanoTime()
            position = AudioPlaybackPosition(5120L, 1024L, firstRead, "music-test")
            assertEquals(5120L, feedbackEntry()["sampleTime"])
            val diagnostic = feedbackDiagnostics.last()
            assertTrue(diagnostic.startsWith("AAC: stream=music-test event=feedback_timing "))
            assertTrue(diagnostic.contains("latestReceivedRtpSample=3072 rxAheadOfFeedbackMs=-42"))
            position = AudioPlaybackPosition(5120L, 1024L, firstRead + 1_000_000_000L)
            val paused = feedbackEntry()
            assertEquals(5120L, paused["sampleTime"])
            assertEquals(firstRead + 1_000_000_000L, paused["timestampRawNs"])
        } finally {
            engine.onSessionClosed(session)
            session.close()
        }
    }

    private fun setup(audioType: String): Map<String, Any?> = mapOf(
        "audioType" to audioType,
        "audioFormat" to 0x8000L,
        "streamConnectionID" to 42L,
    )

    @Suppress("UNCHECKED_CAST")
    private fun streams(engine: CarPlayMediaEngine): MutableMap<CarPlayMediaEngine.StreamKey, Closeable> =
        CarPlayMediaEngine::class.java.getDeclaredField("streams").apply { isAccessible = true }
            .get(engine) as MutableMap<CarPlayMediaEngine.StreamKey, Closeable>

    private fun session(): AirPlaySession {
        val session = AirPlaySession(
            socket = Socket(),
            config = AirPlayConfig(
                deviceName = "test", deviceId = "02:00:00:00:00:02", btMac = "02:00:00:00:00:01",
                sourceVersion = "1.0", main = AirPlayDisplayConfig(widthPixels = 800, heightPixels = 480),
            ),
            identity = AirPlayIdentity.generate(), pairings = PairingStore(), mfi = null,
            listener = object : AirPlaySessionListener {}, media = object : AirPlayMediaHandler {},
        )
        val secret = ByteArray(32).also(SecureRandom()::nextBytes)
        session.pairVerify.javaClass.getDeclaredField("sharedSecret").apply { isAccessible = true }
            .set(session.pairVerify, secret)
        return session
    }
}
