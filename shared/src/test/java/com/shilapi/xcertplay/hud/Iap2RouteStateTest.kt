package com.shilapi.xcertplay.hud

import com.shilapi.xcertplay.hud.NavigationTestFrames.fields
import com.shilapi.xcertplay.hud.NavigationTestFrames.maneuver
import com.shilapi.xcertplay.hud.NavigationTestFrames.route
import com.shilapi.xcertplay.hud.NavigationTestFrames.tlv
import org.junit.Assert.*
import org.junit.Test

class Iap2RouteStateTest {
    @Test fun `missing distance is distinct from a real zero metres`() {
        val state = Iap2RouteState()
        val step = maneuver()
        state.accept(step.messageId, step.payload)
        state.accept(Iap2RouteState.ROUTE_GUIDANCE_UPDATE, fields(tlv(1, 1), tlv(13, 0, 1)))
        assertFalse(state.current()!!.distanceKnown)
        val update = route(distance = 0)
        state.accept(update.messageId, update.payload)
        assertTrue(state.current()!!.distanceKnown)
        assertEquals(0, state.current()!!.distanceMeters)
    }

    @Test fun `new maneuver cannot borrow old maneuver distance`() {
        val state = Iap2RouteState()
        listOf(maneuver(), route(), maneuver(index = 2)).forEach { state.accept(it.messageId, it.payload) }
        state.accept(Iap2RouteState.ROUTE_GUIDANCE_UPDATE, fields(tlv(13, 0, 2)))
        assertFalse(state.current()!!.distanceKnown)
        val update = route(index = 2, distance = 20)
        state.accept(update.messageId, update.payload)
        assertEquals(20, state.current()!!.distanceMeters)
        assertTrue(state.current()!!.distanceKnown)
    }

    @Test fun `out of range distance cannot become a valid candidate after int clamp`() {
        val state = Iap2RouteState()
        val step = maneuver()
        state.accept(step.messageId, step.payload)
        state.accept(Iap2RouteState.ROUTE_GUIDANCE_UPDATE,
            fields(tlv(1, 1), tlv(13, 0, 1), tlv(10, 255, 255, 255, 255)))
        assertEquals(Int.MAX_VALUE, state.current()!!.distanceMeters) // Existing BYD behavior retained.
        assertFalse(state.current()!!.distanceKnown)
    }

    @Test fun `malformed frame does not refresh freshness or mutate route`() {
        var now = 0L
        val state = Iap2RouteState { now }
        listOf(maneuver(), route()).forEach { state.accept(it.messageId, it.payload) }
        now = 29_000_000_000L
        assertEquals(Iap2RouteChange.NONE,
            state.accept(Iap2RouteState.ROUTE_GUIDANCE_UPDATE, byteArrayOf(0, 8, 0, 1, 1)))
        now = 30_000_000_000L
        assertNull(state.current())
    }

    @Test fun `route keepalive cannot refresh an expired distance`() {
        var now = 0L
        val state = Iap2RouteState { now }
        listOf(maneuver(), route()).forEach { state.accept(it.messageId, it.payload) }
        now = 30_000_000_000L
        assertNull(state.current())
        state.accept(Iap2RouteState.ROUTE_GUIDANCE_UPDATE, fields(tlv(1, 1)))
        assertFalse(state.current()!!.distanceKnown)
        assertNull(GeelyNavigationFrame.from(state.current()!!))
        val fresh = route(distance = 20)
        state.accept(fresh.messageId, fresh.payload)
        assertEquals(20, GeelyNavigationFrame.from(state.current()!!)!!.distanceMeters)
    }

    @Test fun `missing driving side keeps legacy default but not a valid Geely candidate`() {
        val state = Iap2RouteState()
        state.accept(Iap2RouteState.ROUTE_GUIDANCE_MANEUVER_UPDATE, fields(tlv(1, 0, 1), tlv(3, 4)))
        val update = route()
        state.accept(update.messageId, update.payload)
        assertEquals(0, state.current()!!.drivingSide)
        assertFalse(state.current()!!.drivingSideKnown)
        assertNull(GeelyNavigationFrame.from(state.current()!!))
    }
}
