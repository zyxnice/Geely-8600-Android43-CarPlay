package com.shilapi.xcertplay.hud

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class GeelyHudTrialPolicyTest {
    @Test fun `unknown factory state waits without revoking the default enabled preference`() {
        val f = Fixture()
        f.waiting = true
        repeat(3) { f.policy.update(f.frame()) }
        assertTrue(f.enabled)
        assertTrue(f.events.isEmpty())
        f.waiting = false
        f.policy.update(f.frame())
        assertEquals(listOf("begin", "guide:250"), f.events)
    }

    @Test fun `external end rebuilds begin and unchanged fresh guidance after shared receiver clears`() {
        val f = Fixture()
        val frame = f.frame()
        f.policy.update(frame)
        f.policy.foreignNavigationEnded()
        f.policy.update(frame)
        assertEquals(listOf("begin", "guide:250", "begin", "guide:250"), f.events)
    }

    @Test fun `external end does not automatically recover a known takeover`() {
        val f = Fixture()
        f.policy.update(f.frame())
        f.policy.foreignNavigation()
        f.policy.foreignNavigationEnded()
        f.policy.update(f.frame())
        assertEquals(listOf("begin", "guide:250"), f.events)
        assertFalse(f.enabled)
    }

    @Test fun `external end before output or after close cannot grant consent`() {
        val f = Fixture()
        f.enabled = false
        f.policy.foreignNavigationEnded()
        f.policy.close()
        f.policy.foreignNavigationEnded()
        assertTrue(f.events.isEmpty())
        assertFalse(f.enabled)
    }

    @Test fun `factory events before consent do not poison a later idle trial`() {
        val f = Fixture()
        f.enabled = false
        f.policy.foreignNavigation()
        f.enabled = true
        f.policy.update(f.frame())
        assertEquals(listOf("begin", "guide:250"), f.events)
    }

    @Test fun `disabled observation neither checks factory nor dispatches`() {
        val f = Fixture()
        f.enabled = false
        f.quiet = false
        f.policy.update(f.frame())
        assertTrue(f.events.isEmpty())
        assertEquals(0, f.factoryChecks)
    }

    @Test fun `first fresh route begins once and changed distance updates without another begin`() {
        val f = Fixture()
        f.policy.update(f.frame())
        f.policy.update(f.frame())
        f.policy.update(f.frame().copy(distanceMeters = 200))
        assertEquals(listOf("begin", "guide:250", "guide:200"), f.events)
    }

    @Test fun `five second distance expiry clears once despite repeated timer callbacks`() {
        val f = Fixture()
        val frame = f.frame()
        f.policy.update(frame)
        f.now += 4_999_999_999L
        f.policy.update(frame)
        assertEquals(listOf("begin", "guide:250"), f.events)
        f.now++
        repeat(3) { f.policy.update(frame) }
        assertEquals(listOf("begin", "guide:250", "end"), f.events)
    }

    @Test fun `absent timestamp and future timestamp cannot start a trial`() {
        val f = Fixture()
        f.policy.update(f.frame().copy(distanceUpdatedNanos = null))
        f.policy.update(f.frame().copy(distanceUpdatedNanos = f.now + 1))
        assertTrue(f.events.isEmpty())
    }

    @Test fun `oversize road is rejected without silent truncation`() {
        val f = Fixture()
        f.policy.update(f.frame().copy(road = "路".repeat(41)))
        assertTrue(f.events.isEmpty())
        f.policy.update(f.frame().copy(road = "路".repeat(40)))
        assertEquals(listOf("begin", "guide:250"), f.events)
    }

    @Test fun `route end and user disable clear only owned output`() {
        val f = Fixture()
        f.policy.update(null)
        assertTrue(f.events.isEmpty())
        f.policy.update(f.frame())
        f.policy.update(null)
        f.policy.update(f.frame())
        f.enabled = false
        f.policy.update(f.frame())
        assertEquals(listOf("begin", "guide:250", "end", "begin", "guide:250", "end"), f.events)
    }

    @Test fun `known source takeover withdraws without clearing the new route`() {
        val f = Fixture()
        f.policy.update(f.frame())
        f.policy.foreignNavigation()
        assertFalse(f.enabled)
        f.enabled = true
        f.policy.update(f.frame())
        f.policy.close()
        assertEquals(listOf("begin", "guide:250"), f.events)
        assertFalse(f.enabled)
    }

    @Test fun `running or unverified factory source prevents start and global end`() {
        val f = Fixture()
        f.quiet = false
        f.policy.update(f.frame())
        assertTrue(f.events.isEmpty())
        assertFalse(f.enabled)
    }

    @Test fun `factory appearing after start is not overwritten by an end`() {
        val f = Fixture()
        f.policy.update(f.frame())
        f.quiet = false
        f.policy.update(null)
        f.policy.close()
        assertEquals(listOf("begin", "guide:250"), f.events)
    }

    @Test fun `a partially failed begin attempts cleanup and disables further output`() {
        val f = Fixture()
        f.failBegin = true
        f.policy.update(f.frame())
        f.policy.update(f.frame())
        assertEquals(listOf("begin", "end"), f.events)
        assertFalse(f.enabled)
    }

    @Test fun `failed clearing retries at most three times and remains visible`() {
        val f = Fixture()
        f.policy.update(f.frame())
        f.failEnd = true
        repeat(10) { f.policy.update(null) }
        f.policy.close()
        assertEquals(3, f.events.count { it == "end" })
        assertFalse(f.enabled)
        assertTrue(f.logs.any { it.contains("clear failed attempt=3") })
    }

    @Test fun `late callbacks after close cannot revoke a subsequent trial`() {
        val f = Fixture()
        f.policy.update(f.frame())
        f.policy.close()
        val events = f.events.toList()
        f.enabled = true
        f.policy.foreignNavigation()
        f.policy.update(f.frame())
        assertTrue(f.enabled)
        assertEquals(events, f.events)
    }

    private class Fixture {
        var enabled = true
        var quiet = true
        var waiting = false
        var now = 1_000_000_000L
        var failBegin = false
        var failEnd = false
        var factoryChecks = 0
        val events = mutableListOf<String>()
        val logs = mutableListOf<String>()
        val policy = GeelyHudTrialPolicy(object : GeelyHudTrialPort {
            override fun begin() { events += "begin"; if (failBegin) throw IOException("begin failed") }
            override fun guidance(frame: GeelyNavigationFrame) { events += "guide:${frame.distanceMeters}" }
            override fun end() { events += "end"; if (failEnd) throw IOException("end failed") }
        }, { enabled }, { enabled = false }, {
            factoryChecks++
            when {
                !quiet -> GeelyHudFactoryReadiness.BLOCKED
                waiting -> GeelyHudFactoryReadiness.WAITING
                else -> GeelyHudFactoryReadiness.READY
            }
        }, logs::add, { now })

        fun frame() = GeelyNavigationFrame(2, 0, 250, "人民路", null, null, now)
    }
}
