package com.shilapi.xcertplay.hud

import com.shilapi.xcertplay.hud.NavigationTestFrames.fields
import com.shilapi.xcertplay.hud.NavigationTestFrames.maneuver
import com.shilapi.xcertplay.hud.NavigationTestFrames.route
import com.shilapi.xcertplay.hud.NavigationTestFrames.tlv
import org.junit.Assert.*
import org.junit.Test

class GeelyManeuverHandoffTest {
    private var now = 0L
    private val state = Iap2RouteState { now }
    private fun start(): Iap2RouteManeuver {
        listOf(maneuver(), route()).forEach { state.accept(it.messageId, it.payload) }
        return state.currentForGeely()!!
    }
    private fun switch(index: Int = 2) {
        state.accept(Iap2RouteState.ROUTE_GUIDANCE_UPDATE,
            fields(tlv(13, 0, index), tlv(10, 0, 0, 0, 40), tlv(7, 0, 0, 3, 232)))
    }

    @Test fun `late details retain entire old snapshot then replace without end`() {
        val old = start()
        val events = mutableListOf<String>()
        val policy = GeelyHudTrialPolicy(object : GeelyHudTrialPort {
            override fun begin() { events += "begin" }
            override fun guidance(frame: GeelyNavigationFrame) { events += "guide:${frame.distanceMeters}" }
            override fun end() { events += "end" }
        }, { true }, {}, { true }, {}, { now })
        fun publish() = policy.update(state.currentForGeely()?.let(GeelyNavigationFrame::from))
        publish()
        now = 100_000_000L
        switch()
        assertEquals(old, state.currentForGeely())
        assertNull(state.current()) // Legacy consumers do not inherit Geely's grace period.
        publish()
        now += 500_000_000L
        val details = maneuver(index = 2, type = 1)
        state.accept(details.messageId, details.payload)
        publish()
        assertEquals(40, state.currentForGeely()!!.distanceMeters)
        assertEquals(1000L, state.currentForGeely()!!.remainingMeters)
        assertEquals(listOf("begin", "guide:250", "guide:40"), events)
    }

    @Test fun `consecutive missing indices cannot renew one second deadline`() {
        val old = start()
        switch()
        now = 900_000_000L
        switch(3)
        assertEquals(old, state.currentForGeely())
        now = 1_000_000_000L
        assertNull(state.currentForGeely())
        now += 100_000_000L
        switch(4)
        assertNull(state.currentForGeely())
    }

    @Test fun `explicit end and clear discard pending snapshot immediately`() {
        for (end in listOf(0, 2)) {
            start(); switch()
            state.accept(Iap2RouteState.ROUTE_GUIDANCE_UPDATE, tlv(1, end))
            assertNull(state.currentForGeely())
        }
        start(); switch(); state.clear()
        assertNull(state.currentForGeely())
    }

    @Test fun `known next details still require its own distance`() {
        val old = start()
        val details = maneuver(index = 2)
        state.accept(details.messageId, details.payload)
        state.accept(Iap2RouteState.ROUTE_GUIDANCE_UPDATE, tlv(13, 0, 2))
        assertEquals(old, state.currentForGeely())
        now = 500_000_000L
        state.accept(Iap2RouteState.ROUTE_GUIDANCE_UPDATE, tlv(10, 0, 0, 0, 40))
        assertEquals(40, state.currentForGeely()!!.distanceMeters)
    }

    @Test fun `initial incomplete route has no old snapshot to display`() {
        switch()
        assertNull(state.currentForGeely())
    }

    @Test fun `expired whole route cannot seed handoff`() {
        start()
        now = 30_000_000_000L
        switch()
        assertNull(state.currentForGeely())
    }

    @Test fun `expired empty list cannot seed handoff`() {
        start()
        state.accept(Iap2RouteState.ROUTE_GUIDANCE_UPDATE, tlv(13))
        now = 3_000_000_000L
        switch()
        assertNull(state.currentForGeely())
    }

    @Test fun `missing traffic side does not prematurely replace the held snapshot`() {
        val old = start()
        switch()
        state.accept(Iap2RouteState.ROUTE_GUIDANCE_MANEUVER_UPDATE,
            fields(tlv(1, 0, 2), tlv(3, 1)))
        now = 999_999_999L
        assertEquals(old, state.currentForGeely())
        // The legacy snapshot still exposes its historical side default without inheriting grace.
        assertFalse(state.current()!!.drivingSideKnown)
        now = 1_000_000_000L
        assertNull(state.currentForGeely()?.let(GeelyNavigationFrame::from))
        val details = maneuver(index = 2, type = 1)
        state.accept(details.messageId, details.payload)
        assertEquals(40, state.currentForGeely()!!.distanceMeters)
    }

    @Test fun `late details after deadline publish only the new maneuver distance`() {
        val old = start()
        switch()
        now = 1_000_000_000L
        assertNull(state.currentForGeely())
        val details = maneuver(index = 2, type = 2)
        state.accept(details.messageId, details.payload)
        val current = state.currentForGeely()!!
        assertEquals(40, current.distanceMeters)
        assertEquals(2, current.type)
        assertNotEquals(old, current)
    }

    @Test fun `cleared channel cannot reuse an old handoff snapshot for a new incomplete route`() {
        start()
        switch()
        state.clear()
        now = 100_000_000L
        switch(3)
        assertNull(state.currentForGeely())
        assertNull(state.current())
    }
}
