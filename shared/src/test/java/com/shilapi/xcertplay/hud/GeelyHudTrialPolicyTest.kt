package com.shilapi.xcertplay.hud

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class GeelyHudTrialPolicyTest {
    @Test fun `enabled output starts directly without factory navigation evidence`() {
        val f = Fixture()
        f.policy.update(null)
        assertTrue(f.events.isEmpty())
        f.policy.update(f.frame())
        assertEquals(listOf("begin", "guide:250"), f.events)
    }

    @Test fun `disabled observation neither checks receiver nor dispatches`() {
        val f = Fixture()
        f.enabled = false
        f.verified = false
        f.policy.update(f.frame())
        assertTrue(f.events.isEmpty())
        assertEquals(0, f.receiverChecks)
    }

    @Test fun `first fresh route begins once and changed distance updates without another begin`() {
        val f = Fixture()
        f.policy.update(f.frame())
        f.policy.update(f.frame())
        f.policy.update(f.frame().copy(distanceMeters = 200))
        assertEquals(listOf("begin", "guide:250", "guide:200"), f.events)
    }

    @Test fun `fresh timestamp updates resend unchanged guidance after dedup rollback`() {
        val f = Fixture()
        val initial = f.frame()
        f.policy.update(initial)
        repeat(10) {
            f.now += 1_000_000_000L
            f.policy.update(initial.copy(distanceUpdatedNanos = f.now))
        }
        assertEquals(1, f.events.count { it == "begin" })
        assertEquals(11, f.events.count { it == "guide:250" })
        val refreshed = initial.copy(distanceUpdatedNanos = f.now)
        f.now += 5_000_000_000L
        f.policy.update(refreshed)
        assertEquals(11, f.events.count { it == "guide:250" })
        f.policy.update(initial.copy(distanceUpdatedNanos = f.now))
        assertEquals(1, f.events.count { it == "begin" })
        assertEquals(12, f.events.count { it == "guide:250" })
    }

    @Test fun `every changed OEM guidance field including optional presence is sent`() {
        val initial = Fixture().frame()
        val changes = listOf(initial.copy(icon = 3), initial.copy(roundaboutExit = 2),
            initial.copy(distanceMeters = 200), initial.copy(road = "New road"),
            initial.copy(remainingMeters = 0), initial.copy(remainingSeconds = 0))
        for (changed in changes) {
            val f = Fixture()
            f.policy.update(initial)
            f.policy.update(changed)
            f.policy.update(changed)
            f.policy.update(initial)
            assertEquals("changed fields=${changed.candidateFields()}", 4, f.events.size)
            assertEquals(1, f.events.count { it == "begin" })
        }
    }

    @Test fun `directionless guidance remains between one begin and one actual route end`() {
        val f = Fixture()
        f.policy.update(f.frame())
        f.policy.update(f.frame().copy(icon = 1, distanceMeters = 0))
        assertEquals(listOf("begin", "guide:250", "guide:0"), f.events)
        f.policy.update(null)
        f.policy.update(null)
        assertEquals(listOf("begin", "guide:250", "guide:0", "end"), f.events)
    }

    @Test fun `distance age alone never clears a decoder validated route`() {
        val f = Fixture()
        val frame = f.frame()
        f.policy.update(frame)
        f.now += 4_999_999_999L
        f.policy.update(frame)
        assertEquals(listOf("begin", "guide:250"), f.events)
        f.now++
        repeat(3) { f.policy.update(frame) }
        assertEquals(listOf("begin", "guide:250"), f.events)
        f.policy.update(null)
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

    @Test fun `unverified receiver prevents start and global end`() {
        val f = Fixture()
        f.verified = false
        f.policy.update(f.frame())
        assertTrue(f.events.isEmpty())
        assertFalse(f.enabled)
    }

    @Test fun `receiver identity lost after start prevents further writes`() {
        val f = Fixture()
        f.policy.update(f.frame())
        f.verified = false
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
        f.policy.update(f.frame())
        assertTrue(f.enabled)
        assertEquals(events, f.events)
    }

    private class Fixture {
        var enabled = true
        var verified = true
        var now = 1_000_000_000L
        var failBegin = false
        var failEnd = false
        var receiverChecks = 0
        val events = mutableListOf<String>()
        val logs = mutableListOf<String>()
        val policy = GeelyHudTrialPolicy(object : GeelyHudTrialPort {
            override fun begin() { events += "begin"; if (failBegin) throw IOException("begin failed") }
            override fun guidance(frame: GeelyNavigationFrame) { events += "guide:${frame.distanceMeters}" }
            override fun end() { events += "end"; if (failEnd) throw IOException("end failed") }
        }, { enabled }, { enabled = false }, {
            receiverChecks++
            verified
        }, logs::add, { now })

        fun frame() = GeelyNavigationFrame(2, 0, 250, "人民路", null, null, now)
    }
}
