package com.shilapi.xcertplay

import com.shilapi.xcertplay.airplay.AirPlaySessionListener
import com.shilapi.xcertplay.orchestration.CarPlayController
import com.shilapi.xcertplay.compat.GeelyMediaOptions
import com.shilapi.xcertplay.media.AndroidMediaSink
import java.io.Closeable
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class CarPlayConnectionDiagnosticLogTest {
    private lateinit var activity: CarPlayHostActivity
    private lateinit var listener: AirPlaySessionListener
    private val log get() = File(activity.filesDir, "logs/diplay.log").readText()

    @Before fun prepareOldControllerListener() {
        activity = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        activity.javaClass.getDeclaredMethod("initializeSessionLog").apply { isAccessible = true }.invoke(activity)
        activity.javaClass.getDeclaredField("restartGeneration").apply { isAccessible = true }.set(activity, 2)
        listener = activity.javaClass.getDeclaredMethod("createSessionListener", Int::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(activity, 1) as AirPlaySessionListener
    }

    @After fun cleanup() {
        assertTrue(AsyncDiagnosticLog.awaitIdle(2_000))
        activity.javaClass.getDeclaredField("sessionLog").apply { isAccessible = true }
            .get(activity).let { (it as Closeable).close() }
        for (field in listOf("teardownExecutor", "airPlayCommandExecutor")) {
            (activity.javaClass.getDeclaredField(field).apply { isAccessible = true }
                .get(activity) as ExecutorService).shutdownNow()
        }
    }

    @Test fun oldTeardownEvidenceSurvivesWithoutAcceptingOtherOldControllerLogs() {
        listener.onDebugLog("${CarPlayController.CONNECTION_DIAGNOSTIC_PREFIX} attempt=1 phase=CONTROL teardown end elapsedMs=117 executorTerminated=true")
        listener.onDebugLog("old controller ordinary state")
        assertTrue(AsyncDiagnosticLog.awaitIdle(2_000))
        assertTrue(log.contains("teardown end elapsedMs=117"))
        assertFalse(log.contains("old controller ordinary state"))
        assertEquals(2, activity.javaClass.getDeclaredField("restartGeneration").apply { isAccessible = true }.get(activity))
    }

    @Test fun theDiagnosticPrefixDoesNotBypassCredentialRedaction() {
        listener.onDebugLog("${CarPlayController.CONNECTION_DIAGNOSTIC_PREFIX} attempt=1 token=private-token")
        assertTrue(AsyncDiagnosticLog.awaitIdle(2_000))
        assertFalse(log.contains("private-token"))
    }

    @Test(timeout = 10_000) fun aacCallbackReturnsWhileSessionFileIsBlocked() {
        val sink = activity.javaClass.getDeclaredMethod("createMediaSink",
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            GeelyMediaOptions::class.java).apply { isAccessible = true }
            .invoke(activity, 800, 480, 2, GeelyMediaOptions()) as AndroidMediaSink
        @Suppress("UNCHECKED_CAST")
        val callback = sink.javaClass.getDeclaredField("onAudioDiagnostic")
            .apply { isAccessible = true }.get(sink) as (String) -> Unit
        val target = activity.javaClass.getDeclaredField("sessionLog")
            .apply { isAccessible = true }.get(activity) as SessionLogFile
        val fileLock = checkNotNull(target.javaClass.getDeclaredField("lock").apply { isAccessible = true }.get(target))
        val returned = CountDownLatch(1)
        val caller = Thread {
            try {
                callback("AAC: stream=test event=window window=1 pcmInspectTotalUs=12")
            } finally {
                returned.countDown()
            }
        }
        try {
            synchronized(fileLock) {
                caller.start()
                assertTrue("AAC callback must not wait for file append/rotation",
                    returned.await(2, TimeUnit.SECONDS))
            }
            assertTrue(AsyncDiagnosticLog.awaitIdle(2_000))
            assertTrue(log.contains("window=1 part=1/1 pcmInspectTotalUs=12"))
        } finally {
            caller.join(2_000)
            sink.close()
        }
    }
}
