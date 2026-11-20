package com.shilapi.xcertplay

import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayDisplayConfig
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.airplay.AirPlayMediaHandler
import com.shilapi.xcertplay.airplay.AirPlaySession
import com.shilapi.xcertplay.airplay.AirPlaySessionListener
import com.shilapi.xcertplay.airplay.PairingStore
import com.shilapi.xcertplay.orchestration.CarPlayController
import com.shilapi.xcertplay.orchestration.CarPlayRuntimeConfig
import com.shilapi.xcertplay.orchestration.CarPlayTransport
import com.shilapi.xcertplay.orchestration.MfiTarget
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class WirelessDisconnectReportTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun establishedWirelessControlEofIsPersistedButLocalStopIsNot() {
        val context = RuntimeEnvironment.getApplication()
        val recorded = CountDownLatch(1)
        val config = AirPlayConfig(
            deviceName = "test", deviceId = "02:00:00:00:00:02", btMac = "02:00:00:00:00:01",
            sourceVersion = "1", main = AirPlayDisplayConfig(widthPixels = 1280, heightPixels = 480),
        )
        val identity = AirPlayIdentity.generate()
        val pairings = PairingStore()
        val media = object : AirPlayMediaHandler {}
        val controller = CarPlayController(
            context = context,
            config = CarPlayRuntimeConfig(
                mfiTarget = MfiTarget.LOCAL,
                identification = Iap2IdentificationConfig(
                    name = "test", modelIdentifier = "test", manufacturer = "test", serialNumber = "test",
                    firmwareVersion = "1", hardwareVersion = "1", carPlayUsbInterfaceNumber = 3,
                ),
                transport = CarPlayTransport.WIRELESS,
            ),
            airPlayConfig = config,
            identity = identity,
            pairings = pairings,
            listener = object : AirPlaySessionListener {},
            media = media,
            reportStatus = {},
            onWirelessDisconnect = { event ->
                WirelessDisconnectReport.enqueue(context, event, 123)
                recorded.countDown()
            },
        )
        // Supply the established wireless controller boundary without a vehicle hotspot or phone.
        val phase = controller.javaClass.getDeclaredField("phase").apply { isAccessible = true }
        phase.set(controller, phase.type.enumConstants.orEmpty().single { (it as Enum<*>).name == "WIRELESS" })
        val listener = controller.javaClass.getDeclaredMethod("wirelessSessionListener", Int::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(controller, 0) as AirPlaySessionListener
        val loopback = InetAddress.getByName("127.0.0.1")
        try {
            ServerSocket(0, 1, loopback).use { server ->
                Socket(loopback, server.localPort).use { phone ->
                    val session = AirPlaySession(server.accept(), config, identity, pairings, null, listener, media)
                    session.start()
                    record(phone)
                    phone.shutdownOutput()
                    assertTrue(recorded.await(5, TimeUnit.SECONDS))
                }
                val saved = exportedRecords()
                assertEquals(1, saved.size)
                assertEquals("CONTROL", saved.single().getString("source"))
                assertEquals("PEER_EOF", saved.single().getString("observedReason"))
                assertEquals(123, saved.single().getInt("mainFramesSubmittedAtEvent"))
                assertTrue(saved.single().has("systemSnapshot"))
                assertFalse(saved.single().toString().contains("127.0.0.1"))

                Socket(loopback, server.localPort).use { phone ->
                    val session = AirPlaySession(server.accept(), config, identity, pairings, null, listener, media)
                    session.start()
                    record(phone)
                    controller.close()
                    session.close()
                    assertEquals(1, exportedRecords().size)
                }
            }
        } finally {
            controller.close()
            controller.awaitClosed(2000)
        }
    }

    @Test fun incidentHistoryRetainsTheLatest500CompleteRecords() {
        val directory = temporary.newFolder()
        val file = File(directory, "logs/wifi-disconnects.jsonl")
        check(file.parentFile!!.mkdirs())
        file.bufferedWriter().use { writer ->
            repeat(499) { index -> writer.appendLine(JSONObject().put("index", index).toString()) }
        }
        for (index in 499..504) WirelessDisconnectReport.append(directory, JSONObject().put("index", index))
        assertEquals((5..504).toList(), file.readLines().map { JSONObject(it).getInt("index") })
    }

    private fun exportedRecords(): List<JSONObject> = buildString {
        WirelessDisconnectReport.appendTo(this, RuntimeEnvironment.getApplication())
    }.lineSequence().filter { it.startsWith("{") }.map(::JSONObject).toList()

    private fun record(phone: Socket) {
        phone.soTimeout = 4000
        phone.getOutputStream().apply {
            write("RECORD * RTSP/1.0\r\nCSeq: 1\r\nContent-Length: 0\r\n\r\n".toByteArray(Charsets.US_ASCII))
            flush()
        }
        assertTrue(phone.getInputStream().bufferedReader().readLine().contains("200"))
    }
}
