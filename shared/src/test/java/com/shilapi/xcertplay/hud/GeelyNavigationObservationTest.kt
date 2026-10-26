package com.shilapi.xcertplay.hud

import com.shilapi.xcertplay.hud.NavigationTestFrames.maneuver
import com.shilapi.xcertplay.hud.NavigationTestFrames.route
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class GeelyNavigationObservationTest {
    @Test fun `slow diagnostics cannot block the phone receiver`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val observation = GeelyNavigationObservation { message ->
            if (message.contains("observe-only")) {
                entered.countDown()
                release.await(10, TimeUnit.SECONDS)
            }
            if (message.contains("observation closed")) closed.countDown()
        }
        val caller = Executors.newSingleThreadExecutor()
        try {
            val token = observation.beginChannel()
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            assertTrue(caller.submit<Boolean> { observation.accept(token, maneuver()) }.get(2, TimeUnit.SECONDS))
        } finally {
            release.countDown()
            observation.close()
            caller.shutdownNow()
        }
        assertTrue(closed.await(3, TimeUnit.SECONDS))
    }

    @Test fun `queue overflow disables observation rather than dropping incremental state and continuing`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val disabled = CountDownLatch(1)
        val logs = CopyOnWriteArrayList<String>()
        val observation = GeelyNavigationObservation { message ->
            logs.add(message)
            if (message.contains("observe-only")) {
                entered.countDown()
                release.await(10, TimeUnit.SECONDS)
            }
            if (message.contains("disabled-queue-or-decoder-failure")) disabled.countDown()
        }
        try {
            val token = observation.beginChannel()
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            repeat(150) { observation.accept(token, route()) }
            release.countDown()
            assertTrue(disabled.await(3, TimeUnit.SECONDS))
            val replacement = observation.beginChannel()
            assertTrue(replacement > token)
            assertTrue(observation.accept(replacement, Iap2Frame(0x5001, byteArrayOf())))
            assertTrue(observation.accept(replacement, maneuver()))
            assertFalse(observation.accept(token, route()))
            assertFalse(logs.any { it.contains("status=candidate-only") })
        } finally {
            release.countDown()
            observation.close()
        }
    }

    @Test fun `old queued wireless run cannot replace a newer control channel`() {
        val candidate = CountDownLatch(1)
        val observation = GeelyNavigationObservation { message ->
            if (message.contains("status=candidate-only")) candidate.countDown()
        }
        try {
            val old = observation.beginChannel(connectionGeneration = 1)
            val current = observation.beginChannel(connectionGeneration = 2)
            assertEquals(-1L, observation.beginChannel(connectionGeneration = 1))
            assertFalse(observation.accept(old, route()))
            assertTrue(observation.accept(current, maneuver()))
            assertTrue(observation.accept(current, route()))
            assertTrue(candidate.await(3, TimeUnit.SECONDS))
        } finally { observation.close() }
    }
}
