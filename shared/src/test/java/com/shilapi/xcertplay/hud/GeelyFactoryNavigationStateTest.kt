package com.shilapi.xcertplay.hud

import org.junit.Assert.*
import org.junit.Test

class GeelyFactoryNavigationStateTest {
    @Test fun `background heartbeat is not evidence of active navigation`() {
        val state = GeelyFactoryNavigationState()
        repeat(5) { state.observe(10001, null, 0, 0, "") }
        assertEquals(GeelyFactoryNavigationState.Activity.UNKNOWN, state.activity)
    }

    @Test fun `real and simulated lifecycle start and end follow the H53 contract`() {
        for ((start, end) in listOf(8 to 9, 10 to 12)) {
            val state = GeelyFactoryNavigationState()
            assertEquals(GeelyFactoryNavigationState.Activity.ACTIVE, state.observe(10019, start, null, null, null))
            assertEquals(GeelyFactoryNavigationState.Activity.INACTIVE, state.observe(10019, end, null, null, null))
        }
    }

    @Test fun `complete guidance catches a route that started before registration`() {
        val state = GeelyFactoryNavigationState()
        state.observe(10001, null, 5, 0, "出口")
        assertEquals(GeelyFactoryNavigationState.Activity.ACTIVE, state.activity)
    }

    @Test fun `empty and unrelated events cannot erase known active navigation`() {
        val state = GeelyFactoryNavigationState()
        state.observe(10019, 8, null, null, null)
        state.observe(10001, null, 0, 0, "")
        state.observe(10019, 1, null, null, null)
        state.observe(10002, 9, null, null, null)
        assertEquals(GeelyFactoryNavigationState.Activity.ACTIVE, state.activity)
    }

    @Test fun `partial or invalid guidance does not create a route`() {
        val state = GeelyFactoryNavigationState()
        state.observe(10001, null, 5, null, "路")
        state.observe(10001, null, 0, -1, "")
        state.observe(10001, null, null, null, " ")
        assertEquals(GeelyFactoryNavigationState.Activity.UNKNOWN, state.activity)
    }

    @Test fun `unknown factory icons and blank roads still protect a zero distance route`() {
        val state = GeelyFactoryNavigationState()
        state.observe(10001, null, 99, 0, "")
        assertEquals(GeelyFactoryNavigationState.Activity.ACTIVE, state.activity)
    }

    @Test fun `remaining route evidence protects navigation without a maneuver icon`() {
        val state = GeelyFactoryNavigationState()
        state.observe(10001, null, 0, null, "", remainingMeters = 100)
        assertEquals(GeelyFactoryNavigationState.Activity.ACTIVE, state.activity)
    }
}
