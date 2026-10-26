package com.shilapi.xcertplay.hud

import com.shilapi.xcertplay.hud.NavigationTestFrames.fields
import com.shilapi.xcertplay.hud.NavigationTestFrames.maneuver
import com.shilapi.xcertplay.hud.NavigationTestFrames.route
import com.shilapi.xcertplay.hud.NavigationTestFrames.tlv
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import org.junit.Assert.*
import org.junit.Test

class GeelyNavigationObserverTest {
    @Test fun `old channel callbacks cannot restore route after handoff`() {
        val logs = mutableListOf<String>()
        val observer = GeelyNavigationObserver(logs::add)
        val old = observer.beginChannel()
        observer.accept(old, maneuver())
        val current = observer.beginChannel()
        assertFalse(observer.accept(old, route()))
        observer.accept(current, route())
        assertFalse(logs.last().contains("candidate-only"))
        observer.accept(current, maneuver())
        assertTrue(logs.last().contains("status=candidate-only"))
        assertTrue(logs.all { it.contains("vehicleOutput=blocked") })
    }

    @Test fun `freshness is observed on clock ticks without another phone frame`() {
        var now = 0L
        val logs = mutableListOf<String>()
        val observer = GeelyNavigationObserver(logs::add) { now }
        val token = observer.beginChannel()
        observer.accept(token, maneuver())
        observer.accept(token, route())
        assertTrue(logs.last().contains("candidate-only"))
        now = 30_000_000_000L
        observer.tick()
        assertTrue(logs.last().contains("incomplete-or-expired"))
    }

    @Test fun `pause clears cache and close refuses all late callbacks`() {
        val logs = mutableListOf<String>()
        val observer = GeelyNavigationObserver(logs::add)
        val token = observer.beginChannel()
        observer.accept(token, maneuver())
        observer.accept(token, route())
        observer.pause()
        val pausedCount = logs.size
        observer.accept(token, route())
        observer.tick()
        assertEquals(pausedCount, logs.size)
        observer.resume()
        observer.accept(token, route())
        assertTrue(logs.last().contains("incomplete-or-expired"))
        observer.close()
        assertFalse(observer.accept(token, maneuver()))
        assertEquals(-1L, observer.beginChannel())
    }

    @Test fun `stable distance ticks are rate limited but route end is immediate`() {
        var now = 0L
        val logs = mutableListOf<String>()
        val observer = GeelyNavigationObserver(logs::add) { now }
        val token = observer.beginChannel()
        observer.accept(token, maneuver())
        observer.accept(token, route())
        val before = logs.size
        repeat(20) { observer.accept(token, route(distance = 200 - it)) }
        assertEquals(before, logs.size)
        now = 5_000_000_000L
        observer.tick()
        assertEquals(before + 1, logs.size)
        observer.accept(token, Iap2Frame(Iap2RouteState.ROUTE_GUIDANCE_UPDATE, fields(tlv(1, 2))))
        assertTrue(logs.last().contains("incomplete-or-expired"))
    }
}
