package com.shilapi.xcertplay

import android.view.Surface
import com.shilapi.xcertplay.airplay.*
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.orchestration.CarPlayController
import com.shilapi.xcertplay.orchestration.CarPlayRuntimeConfig
import com.shilapi.xcertplay.orchestration.MfiTarget
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import com.shilapi.xcertplay.transport.Iap2LocationProvider
import java.io.IOException
import java.util.concurrent.ExecutorService
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.util.concurrent.PausedExecutorService
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

/** Drives the Activity shutdown path with a real controller and a failing closeable resource. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
@LooperMode(LooperMode.Mode.PAUSED)
class CarPlayCloseGateTest {
    private lateinit var activity: CarPlayHostActivity
    private lateinit var teardown: PausedExecutorService
    private var controller: CarPlayController? = null

    @Before fun setUp() {
        activity = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        // No OEM HUD service exists in this JVM fixture; supply a well-formed unavailable binder.
        shadowOf(activity.application).setComponentNameAndServiceForBindService(
            android.content.ComponentName(activity, "TestHudService"), android.os.Binder())
        (field("teardownExecutor") as ExecutorService).shutdownNow()
        teardown = PausedExecutorService()
        setField("teardownExecutor", teardown)
    }

    @After fun tearDown() {
        controller?.close()
        controller?.awaitClosed(2000)
        teardown.shutdownNow()
        (field("airPlayCommandExecutor") as ExecutorService).shutdownNow()
        CarPlayBackgroundSession.clear()
    }

    @Test fun repeatedShutdownCannotRunCompletionsOrExposeAnUnreleasedOwner() {
        installController(failClose = true)
        var completed = 0
        shutdown { completed++ }
        shutdown { completed++ }
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(0, completed)
        assertTrue(CarPlayBackgroundSession.hasSession())
        assertNull(CarPlayBackgroundSession.snapshot())
        assertTrue(CarPlayBackgroundSession.isOwner(activity))
        teardown.runAll()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertFalse(controller!!.awaitClosed(0))
        assertEquals(0, completed)
        assertTrue(CarPlayBackgroundSession.hasSession())
        shutdown { completed++ }
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(0, completed)
        // Another Activity sees the old owner but cannot adopt a closing snapshot.
        val next = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        try {
            assertFalse(CarPlayBackgroundSession.isOwner(next))
            assertNull(CarPlayBackgroundSession.snapshot())
        } finally {
            for (name in listOf("teardownExecutor", "airPlayCommandExecutor")) {
                (next.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(next) as ExecutorService).shutdownNow()
            }
        }
    }

    @Test fun aCleanShutdownReleasesTheOwnerAndCompletesAllWaitersOnce() {
        installController(failClose = false)
        var completed = 0
        shutdown { completed++ }
        shutdown { completed++ }
        teardown.runAll()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertTrue(controller!!.awaitClosed(0))
        assertFalse(CarPlayBackgroundSession.hasSession())
        assertEquals(2, completed)
        shutdown { completed++ }
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(3, completed)
    }

    private fun installController(failClose: Boolean) {
        val provider = object : Iap2LocationProvider {
            override fun start() = false
            override fun stop() { if (failClose) throw IOException("test resource remains open") }
            override fun latestNmea(): String? = null
        }
        val current = CarPlayController(activity,
            CarPlayRuntimeConfig(mfiTarget = MfiTarget.LOCAL, identification = Iap2IdentificationConfig(
                name = "test", modelIdentifier = "test", manufacturer = "test", serialNumber = "test",
                firmwareVersion = "1", hardwareVersion = "1", carPlayUsbInterfaceNumber = 3)),
            AirPlayConfig(deviceName = "test", deviceId = "02:00:00:00:00:02", btMac = "02:00:00:00:00:01",
                sourceVersion = "1", main = AirPlayDisplayConfig(widthPixels = 1920, heightPixels = 990)),
            AirPlayIdentity.generate(), PairingStore(), object : AirPlaySessionListener {},
            object : AirPlayMediaHandler {}, {}, locationProvider = provider)
        controller = current
        val sink = AndroidMediaSink()
        setField("controller", current)
        setField("sink", sink)
        CarPlayBackgroundSession.store(current, sink, 1920, 990, activity,
            CarPlaySessionDisplay(1920, 990, Surface.ROTATION_0, true, true, 1920, 990)) {}
    }

    private fun shutdown(completion: () -> Unit) {
        activity.javaClass.getDeclaredMethod("shutdown", Boolean::class.javaPrimitiveType,
            String::class.java, kotlin.jvm.functions.Function0::class.java)
            .apply { isAccessible = true }.invoke(activity, false, "close regression", completion)
    }
    private fun field(name: String): Any? = activity.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.get(activity)
    private fun setField(name: String, value: Any) = activity.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.set(activity, value)
}
