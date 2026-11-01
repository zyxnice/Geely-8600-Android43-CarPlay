package com.shilapi.xcertplay.hud

import org.junit.Assert.*
import org.junit.Test

/** Exercise asynchronous Settings callbacks without Android platform or preference side effects. */
class GeelyHudStatusRaceTest {
    @Test fun `late unknown-state report cannot replace manual OFF`() = withRuntime { lease, token ->
        lease.request(false)
        write("currentStatus", GeelyHudTrialStatus.OFF)
        GeelyHudTrialSettings.report(token, GeelyHudTrialStatus.NAVIGATION_STATE_UNKNOWN)
        assertEquals(GeelyHudTrialStatus.OFF, GeelyHudTrialSettings.status())
        // Real recovery failures remain visible even when the requested output is OFF.
        GeelyHudTrialSettings.report(token, GeelyHudTrialStatus.CLEAR_FAILED)
        assertEquals(GeelyHudTrialStatus.CLEAR_FAILED, GeelyHudTrialSettings.status())
    }

    @Test fun `teardown preserves clear failure while preparing a distinct next controller`() = withRuntime { lease, token ->
        write("currentStatus", GeelyHudTrialStatus.CLEAR_FAILED)
        GeelyHudTrialSettings.release(token)
        assertEquals(GeelyHudTrialStatus.CLEAR_FAILED, GeelyHudTrialSettings.status())
        val next = Any()
        assertTrue(lease.acquire(next))
        GeelyHudTrialSettings.release(token)
        assertTrue(lease.owns(next))
        lease.release(next)
    }

    private fun withRuntime(check: (GeelyHudTrialLease, Any) -> Unit) {
        val names = listOf("currentStatus", "requestedEnabled", "factoryIdleConfirmed")
        val saved = names.associateWith(::read)
        val lease = read("lease") as GeelyHudTrialLease
        val token = Any()
        lease.request(true)
        assertTrue(lease.acquire(token))
        write("requestedEnabled", true)
        try { check(lease, token) }
        finally {
            lease.release(token)
            lease.request(false)
            saved.forEach { (name, value) -> write(name, value) }
        }
    }

    // Reflection stays in tests; no platform bypass or mutable test API is added to production.
    private fun read(name: String): Any? = GeelyHudTrialSettings::class.java.getDeclaredField(name)
        .apply { isAccessible = true }.get(null)
    private fun write(name: String, value: Any?) {
        GeelyHudTrialSettings::class.java.getDeclaredField(name).apply { isAccessible = true }.set(null, value)
    }
}
