package com.shilapi.xcertplay

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.mfi.LocalMfiAuthenticationClient
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import java.io.File
import java.io.FileNotFoundException
import java.util.concurrent.ExecutorService
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.android.util.concurrent.PausedExecutorService
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowLog
import org.robolectric.shadows.ShadowToast

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
@LooperMode(LooperMode.Mode.PAUSED)
class DiPlayStartupTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val controllers = mutableListOf<ActivityController<DiPlayActivity>>()

    @Before fun setUp() {
        setBootstrapReady(false)
        CarPlayBackgroundSession.clear()
        clearPreferences()
        // Keep unrelated hotspot/vendor probes out of these UI gate tests.
        AirPlayPersistence.saveWirelessHotspotMode(context, WirelessHotspotMode.WIFI_P2P)
        AirPlayPersistence.saveGeelyBluetoothConnectionEnabled(context, false)
        DiPlayPreferences.savePhone(context, "02:00:00:00:00:01", "Test iPhone")
    }

    @After fun tearDown() {
        try {
            controllers.asReversed().forEach { it.close() }
        } finally {
            controllers.forEach { executor(it).shutdownNow() }
            setBootstrapReady(false)
            CarPlayBackgroundSession.clear()
            clearPreferences()
        }
    }

    @Test fun realOnCreateShowsHomeAndSettingsBeforeAuthenticationRuns() {
        val controller = createActivity()
        val activity = controller.get()

        assertPreparing(controller)
        assertTrue(text(activity, R.string.a_familiar_drive).isShown)
        assertTrue(text(activity, R.string.preparing_mfi_authentication).isShown)
        assertTrue(button(activity, R.string.connect_phone).isEnabled)
        assertTrue(button(activity, R.string.connect_with_usb).isEnabled)
        assertNoNavigation(activity)

        click(activity, R.string.settings)
        assertTrue(text(activity, R.string.your_drive_your_way).isShown)
        assertTrue(button(activity, R.string.open_connection_setup).isShown)
        assertPreparing(controller)
        assertNoNavigation(activity)
    }

    @Test fun earlyUsbAndWirelessClicksOnlyResumeTheLastRequestOnce() {
        val controller = createActivity()
        val activity = controller.get()

        click(activity, R.string.connect_with_usb)
        assertEquals(false, field(activity, "pendingConnection"))
        assertNoNavigation(activity)
        repeat(2) {
            click(activity, R.string.connect_phone)
            assertEquals(true, field(activity, "pendingConnection"))
            assertNoNavigation(activity)
        }
        assertPreparing(controller)

        completeAuthenticationSuccessfully(controller)
        assertEquals(true, field(activity, "setupComplete"))
        assertNull(field(activity, "setupError"))
        assertNull(field(activity, "pendingConnection"))
        assertProjection(activity, wireless = true)
        assertEquals(0, executor(controller).runAll())
        controller.pause().resume()
        shadowOf(Looper.getMainLooper()).idle()
        assertNoNavigation(activity)
    }

    @Test fun missingAuthenticationAssetsShowTheErrorAndDiscardQueuedConnections() {
        // Refuse provisioned sandboxes without reading or deleting credentials.
        assertFalse(File(context.noBackupFilesDir, LocalMfiAuthenticationClient.DIRECTORY).exists())
        assertTrue(context.assets.list("offline-mfi").orEmpty().isEmpty())
        val controller = createActivity()
        val activity = controller.get()
        click(activity, R.string.connect_with_usb)
        click(activity, R.string.connect_phone)
        assertPreparing(controller)
        assertNoNavigation(activity)

        assertEquals(1, executor(controller).runAll())
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(true, field(activity, "setupComplete"))
        assertEquals(activity.getString(R.string.setup_error_auth), field(activity, "setupError"))
        assertNull(field(activity, "pendingConnection"))
        assertTrue(text(activity, R.string.setup_error_auth).isShown)
        assertTrue(text(activity, R.string.setup_needs_attention).isShown)
        assertFalse(button(activity, R.string.connect_phone).isEnabled)
        assertTrue(ShadowLog.getLogsForTag("DiPlaySetup").single().throwable is FileNotFoundException)
        assertNoNavigation(activity)

        click(activity, R.string.connect_with_usb)
        assertEquals(activity.getString(R.string.setup_error_auth), ShadowToast.getTextOfLatestToast())
        controller.pause().resume()
        shadowOf(Looper.getMainLooper()).idle()
        assertNoNavigation(activity)
    }

    @Test fun authenticationWhilePausedWaitsForResumeBeforeOpeningProjection() {
        val controller = createActivity()
        val activity = controller.get()
        click(activity, R.string.connect_with_usb)
        controller.pause()

        completeAuthenticationSuccessfully(controller)
        assertEquals(true, field(activity, "setupComplete"))
        assertEquals(false, field(activity, "activityResumed"))
        assertEquals(false, field(activity, "pendingConnection"))
        assertNoNavigation(activity)

        controller.resume()
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(field(activity, "pendingConnection"))
        assertProjection(activity, wireless = false)
        controller.pause().resume()
        shadowOf(Looper.getMainLooper()).idle()
        assertNoNavigation(activity)
    }

    @Test fun authenticationCallbackDeliveredAfterDestroyIsIgnored() {
        val controller = createActivity()
        val activity = controller.get()
        click(activity, R.string.connect_with_usb)
        controller.pause().stop()

        // Finish the task before shutdown clears it, but deliver its callback after destruction.
        setBootstrapReady(true)
        assertEquals(1, executor(controller).runAll())
        assertEquals(false, field(activity, "setupComplete"))
        controller.destroy()
        assertTrue(activity.isDestroyed)
        assertTrue(executor(controller).isShutdown)
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(false, field(activity, "setupComplete"))
        assertNull(field(activity, "setupError"))
        assertNoNavigation(activity)
    }

    @Test fun savedPendingUsbFalseSurvivesRecreationAndOverridesWirelessAutoConnect() {
        AirPlayPersistence.saveWirelessEnabled(context, true)
        val original = createActivity()
        click(original.get(), R.string.connect_phone)
        click(original.get(), R.string.connect_with_usb)
        assertEquals(false, field(original.get(), "pendingConnection"))
        assertNoNavigation(original.get())
        val state = Bundle()
        original.pause().saveInstanceState(state).stop().destroy()
        assertTrue(state.containsKey("pending_connection"))
        assertFalse(state.getBoolean("pending_connection"))

        DiPlayPreferences.saveAutoConnect(context, true)
        val recreated = createActivity(state)
        val activity = recreated.get()
        assertPreparing(recreated)
        assertEquals(false, field(activity, "pendingConnection"))
        assertNoNavigation(activity)

        completeAuthenticationSuccessfully(recreated)
        assertNull(field(activity, "pendingConnection"))
        assertFalse(AirPlayPersistence.loadWirelessEnabled(context))
        assertProjection(activity, wireless = false)
        assertNoNavigation(original.get())
    }

    @Test fun autoConnectQueuesTheSavedTransportUntilAuthenticationCompletes() {
        DiPlayPreferences.saveAutoConnect(context, true)
        AirPlayPersistence.saveWirelessEnabled(context, true)
        val controller = createActivity()
        val activity = controller.get()
        shadowOf(Looper.getMainLooper()).idle()

        assertPreparing(controller)
        assertNull(field(activity, "pendingConnection"))
        assertEquals(true, field(activity, "pendingAutoConnect"))
        assertNoNavigation(activity)

        completeAuthenticationSuccessfully(controller)
        assertNull(field(activity, "pendingConnection"))
        assertEquals(false, field(activity, "pendingAutoConnect"))
        assertProjection(activity, wireless = true)
        controller.pause().resume()
        shadowOf(Looper.getMainLooper()).idle()
        assertNoNavigation(activity)
    }

    @Test fun authenticationFailureRemainsVisibleOnConnectionSettings() {
        val controller = createActivity()
        val activity = controller.get()
        click(activity, R.string.settings)
        click(activity, R.string.open_connection_setup)
        assertEquals("connection", field(activity, "page"))

        assertEquals(1, executor(controller).runAll())
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(text(activity, R.string.setup_error_auth).isShown)
        assertNoNavigation(activity)
    }

    @Test fun queuedAutoConnectDoesNotRestartASessionCreatedWhilePaused() {
        DiPlayPreferences.saveAutoConnect(context, true)
        val controller = createActivity()
        val activity = controller.get()
        assertEquals(true, field(activity, "pendingAutoConnect"))
        controller.pause()
        var stops = 0
        val stopAction: (() -> Unit) -> Unit = { completion -> stops++; completion() }
        CarPlayBackgroundSession::class.java.getDeclaredField("stopAction")
            .apply { isAccessible = true }.set(CarPlayBackgroundSession, stopAction)
        CarPlayBackgroundSession.active = true

        completeAuthenticationSuccessfully(controller)
        controller.resume()
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(0, stops)
        assertTrue(CarPlayBackgroundSession.hasSession())
        assertEquals(false, field(activity, "pendingAutoConnect"))
        assertNoNavigation(activity)
    }

    @Test fun queuedAutoConnectRechecksThePreferenceBeforeStarting() {
        DiPlayPreferences.saveAutoConnect(context, true)
        val controller = createActivity()
        assertEquals(true, field(controller.get(), "pendingAutoConnect"))
        DiPlayPreferences.saveAutoConnect(context, false)

        completeAuthenticationSuccessfully(controller)

        assertEquals(false, field(controller.get(), "pendingAutoConnect"))
        assertNoNavigation(controller.get())
    }

    @Test fun existingSessionCanBeReopenedWithoutWaitingOrRestartingIt() {
        // Model the process-local session marker, not a live USB/Bluetooth transport.
        var stops = 0
        val stopAction: (() -> Unit) -> Unit = { completion -> stops++; completion() }
        CarPlayBackgroundSession::class.java.getDeclaredField("stopAction")
            .apply { isAccessible = true }.set(CarPlayBackgroundSession, stopAction)
        CarPlayBackgroundSession.active = true
        DiPlayPreferences.saveAutoConnect(context, true)
        AirPlayPersistence.saveWirelessEnabled(context, false)
        val controller = createActivity()
        val activity = controller.get()

        assertPreparing(controller)
        assertNull(field(activity, "pendingConnection"))
        assertTrue(text(activity, R.string.carplay_connected).isShown)
        assertNoNavigation(activity)
        click(activity, R.string.open_carplay)
        assertProjection(activity, wireless = false)
        assertPreparing(controller)
        assertNull(field(activity, "pendingConnection"))

        completeAuthenticationSuccessfully(controller)
        assertNoNavigation(activity)
        assertEquals(0, stops)
        assertTrue(CarPlayBackgroundSession.hasSession())
        assertTrue(CarPlayBackgroundSession.active)
    }

    private fun createActivity(state: Bundle? = null): ActivityController<DiPlayActivity> {
        val controller = Robolectric.buildActivity(DiPlayActivity::class.java)
        val activity = controller.get()
        (field(activity, "setupExecutor") as ExecutorService).shutdownNow()
        activity.javaClass.getDeclaredField("setupExecutor").apply { isAccessible = true }
            .set(activity, PausedExecutorService())
        controllers.add(controller)
        // Exercise the real page and AndroidX lifecycle; do not replace onCreate or render.
        controller.create(state).start()
        if (state != null) controller.restoreInstanceState(state)
        return controller.postCreate(state).resume().postResume().visible()
    }

    private fun completeAuthenticationSuccessfully(controller: ActivityController<DiPlayActivity>) {
        // Supply an already-validated identity; execute the real task without reading assets.
        setBootstrapReady(true)
        assertEquals(1, executor(controller).runAll())
        assertEquals(false, field(controller.get(), "setupComplete"))
        assertNoNavigation(controller.get())
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun assertPreparing(controller: ActivityController<DiPlayActivity>) {
        assertTrue(executor(controller).hasQueuedTasks())
        assertEquals(false, field(controller.get(), "setupComplete"))
        assertNull(field(controller.get(), "setupError"))
    }

    private fun assertProjection(activity: DiPlayActivity, wireless: Boolean) {
        val intent = shadowOf(activity).nextStartedActivity
        assertNotNull("Expected exactly one projection launch", intent)
        assertEquals(CarPlayHostActivity::class.java.name, intent!!.component?.className)
        assertTrue(intent.hasExtra(EXTRA_TRANSPORT_WIRELESS))
        assertEquals(wireless, intent.getBooleanExtra(EXTRA_TRANSPORT_WIRELESS, !wireless))
        assertEquals(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT, intent.flags and Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        assertNoNavigation(activity)
    }

    private fun assertNoNavigation(activity: DiPlayActivity) {
        assertNull("Authentication must not launch an Activity here", shadowOf(activity).nextStartedActivity)
    }

    private fun click(activity: DiPlayActivity, textId: Int) {
        val button = button(activity, textId)
        assertTrue(button.isShown)
        assertTrue(button.isEnabled)
        assertTrue(button.performClick())
    }

    private fun button(activity: DiPlayActivity, textId: Int): Button = text(activity, textId) as Button

    private fun text(activity: DiPlayActivity, textId: Int): TextView = views(activity.window.decorView)
        .filterIsInstance<TextView>().single { it.text.toString() == activity.getString(textId) }

    private fun views(root: View): Sequence<View> = sequence {
        yield(root)
        if (root is ViewGroup) for (index in 0 until root.childCount) yieldAll(views(root.getChildAt(index)))
    }

    private fun executor(controller: ActivityController<DiPlayActivity>): PausedExecutorService =
        field(controller.get(), "setupExecutor") as PausedExecutorService

    private fun field(activity: DiPlayActivity, name: String): Any? = activity.javaClass
        .getDeclaredField(name).apply { isAccessible = true }.get(activity)

    private fun setBootstrapReady(ready: Boolean) {
        DiPlayBootstrap::class.java.getDeclaredField("ready").apply { isAccessible = true }
            .setBoolean(DiPlayBootstrap, ready)
    }

    private fun clearPreferences() {
        for (name in listOf("diplay", "xcertplay_airplay")) {
            context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
        }
    }
}
