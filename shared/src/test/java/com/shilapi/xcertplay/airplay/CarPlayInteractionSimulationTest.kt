package com.shilapi.xcertplay.airplay

import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Actual app protocol implementations, with Android services shadowed and post-pairing keys seeded. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class CarPlayInteractionSimulationTest {
    @Test fun encryptedSetupRecordAndBurstFramesReachTheActualMediaSinkInOrder() {
        SimulatedCarPlayPeer().use { phone ->
            val screen = phone.openScreen(42)
            screen.config()
            assertNotNull(phone.configurations.poll(4, TimeUnit.SECONDS))
            phone.request("RECORD")
            assertEquals("RECORD", phone.commands.poll(4, TimeUnit.SECONDS))
            repeat(90) { screen.frame(it, fragmented = it % 3 == 0) }
            repeat(90) { marker ->
                assertArrayEquals(byteArrayOf(0, 0, 0, 1, 0x65, marker.toByte()),
                    phone.frames.poll(4, TimeUnit.SECONDS))
            }
            assertTrue(phone.frames.isEmpty())
        }
    }

    @Test fun touchDownMoveUpReturnsThroughEncryptedEventChannel() {
        SimulatedCarPlayPeer().use { phone ->
            val events = phone.openEvents()
            val screen = phone.openScreen(41)
            screen.config()
            assertNotNull(phone.configurations.poll(4, TimeUnit.SECONDS))
            var marker = 0
            for ((x, down) in listOf(0.25 to true, 0.5 to true, 0.5 to false)) {
                assertTrue(phone.session.sendTouch(listOf(AirPlayContact(0, x, 0.5, down))))
                val command = events.command()
                assertEquals("hidSendReport", command["type"])
                assertEquals("2a2a2a2a", command["uuid"])
                val report = command["hidReport"] as ByteArray
                assertEquals(if (down) 1 else 0, report[1].toInt())
                assertEquals((x * 1920).toInt(), (report[2].toInt() and 255) or ((report[3].toInt() and 255) shl 8))
                assertEquals(360, (report[4].toInt() and 255) or ((report[5].toInt() and 255) shl 8))
                // Complete the interaction loop: the peer responds only after reading this HID.
                screen.frame(++marker)
                assertEquals(marker.toByte(), requireNotNull(phone.frames.poll(4, TimeUnit.SECONDS)).last())
            }
        }
    }

    @Test fun sequentialAudioPacketsPreserveFullTimestampWhileVideoArrives() {
        SimulatedCarPlayPeer().use { phone ->
            val port = phone.audioPort(51)
            val screen = phone.openScreen(52)
            screen.config()
            assertNotNull(phone.configurations.poll(4, TimeUnit.SECONDS))
            for ((index, sample) in listOf(1024, 2048, 0x12345678).withIndex()) {
                val pcm = byteArrayOf(index.toByte(), 1, 2, 3)
                phone.sendAudio(port, 51, sample, pcm)
                screen.frame(index)
                val packet = requireNotNull(phone.audio.poll(4, TimeUnit.SECONDS))
                assertEquals(index + 1, ((packet[2].toInt() and 255) shl 8) or (packet[3].toInt() and 255))
                assertEquals(sample, java.nio.ByteBuffer.wrap(packet, 4, 4).int)
                assertArrayEquals(pcm, packet.copyOfRange(12, packet.size))
                assertEquals(index.toByte(), requireNotNull(phone.frames.poll(4, TimeUnit.SECONDS)).last())
            }
        }
    }

    @Test fun recoveryRequestReachesPhoneAndFreshKeyFrameReturns() {
        SimulatedCarPlayPeer().use { phone ->
            val events = phone.openEvents()
            val screen = phone.openScreen(43)
            screen.config()
            assertNotNull(phone.configurations.poll(4, TimeUnit.SECONDS))
            requireNotNull(phone.requestKeyFrame).invoke()
            assertEquals("forceKeyFrame", events.command()["type"])
            screen.frame(7)
            assertEquals(7.toByte(), requireNotNull(phone.frames.poll(4, TimeUnit.SECONDS)).last())
        }
    }

    @Test fun screenReplacementResetsWireNonceAndKeepsAudioAlive() {
        SimulatedCarPlayPeer().use { phone ->
            val audioPort = phone.audioPort(50)
            phone.openScreen(44).apply { config(); frame(1) }
            assertNotNull(phone.frames.poll(4, TimeUnit.SECONDS))
            phone.openScreen(45).apply { config(); frame(2) }
            assertEquals(2.toByte(), requireNotNull(phone.frames.poll(4, TimeUnit.SECONDS)).last())
            phone.request("TEARDOWN", mapOf("streams" to listOf(mapOf("type" to 110))))
            val pcm = byteArrayOf(1, 2, 3, 4)
            phone.sendAudio(audioPort, 50, 64, pcm)
            val rtp = requireNotNull(phone.audio.poll(4, TimeUnit.SECONDS))
            assertArrayEquals(pcm, rtp.copyOfRange(12, rtp.size))
        }
    }

    @Test fun invalidVideoAuthenticationClosesStreamWithoutDeliveringCorruptedPicture() {
        SimulatedCarPlayPeer().use { phone ->
            val screen = phone.openScreen(46)
            assertEquals(true, phone.active.poll(4, TimeUnit.SECONDS))
            screen.config()
            assertNotNull(phone.configurations.poll(4, TimeUnit.SECONDS))
            screen.frame(9, corrupt = true)
            assertEquals(false, phone.active.poll(4, TimeUnit.SECONDS))
            assertTrue(phone.frames.isEmpty())
        }
    }
}
