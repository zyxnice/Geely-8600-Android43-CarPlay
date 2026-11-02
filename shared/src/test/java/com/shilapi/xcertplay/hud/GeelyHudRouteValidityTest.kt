package com.shilapi.xcertplay.hud

import com.shilapi.xcertplay.hud.NavigationTestFrames.fields
import com.shilapi.xcertplay.hud.NavigationTestFrames.maneuver
import com.shilapi.xcertplay.hud.NavigationTestFrames.route
import com.shilapi.xcertplay.hud.NavigationTestFrames.tlv
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import org.junit.Assert.*
import org.junit.Test

/** Exercises the actual decoder -> observer -> output policy seam with a deterministic clock. */
class GeelyHudRouteValidityTest {
    @Test fun `garage distance pause retains output until whole route stream expires`() {
        val f = Fixture()
        f.start()
        f.now = 6_000_000_000L
        f.observer.tick()
        assertEquals(listOf("begin", "guide:250"), f.events)
        f.now = 30_000_000_000L
        repeat(3) { f.observer.tick() }
        assertEquals(listOf("begin", "guide:250", "end"), f.events)
    }

    @Test fun `live route keepalives retain same maneuver distance without fabricating timestamps`() {
        val f = Fixture()
        f.start()
        repeat(9) {
            f.now += 5_000_000_000L
            f.send(fields(tlv(1, 1)))
            f.observer.tick()
        }
        assertEquals(listOf("begin", "guide:250"), f.events)
        assertTrue(f.logs.last().contains("distanceUpdatedNs=0"))
        f.observer.accept(f.token, route(distance = 100))
        assertEquals(listOf("begin", "guide:250", "guide:100"), f.events)
    }

    @Test fun `new maneuver cannot display previous maneuver distance`() {
        val f = Fixture()
        f.start()
        f.observer.accept(f.token, maneuver(index = 2))
        f.send(fields(tlv(1, 1), tlv(13, 0, 2)))
        assertEquals("end", f.events.last())
        f.observer.accept(f.token, route(index = 2, distance = 20))
        assertEquals(listOf("begin", "guide:20"), f.events.takeLast(2))
    }

    @Test fun `explicit no route and arrival clear immediately without waiting for timeout`() {
        for (state in listOf(0, 2)) {
            val f = Fixture()
            f.start()
            f.send(fields(tlv(1, state)))
            f.observer.tick()
            assertEquals(listOf("begin", "guide:250", "end"), f.events)
        }
    }

    @Test fun `whole stream timeout cannot revive cached distance on a state only keepalive`() {
        val f = Fixture()
        f.start()
        f.now = 31_000_000_000L
        f.observer.tick()
        f.send(fields(tlv(1, 1)))
        assertEquals(listOf("begin", "guide:250", "end"), f.events)
        f.observer.accept(f.token, route(distance = 10))
        assertEquals(listOf("begin", "guide:10"), f.events.takeLast(2))
    }

    @Test fun `empty list distance is counted as received but not applied to old maneuver`() {
        val f = Fixture()
        f.start()
        f.now = 5_000_000_000L
        f.send(fields(tlv(10, 0, 0, 0, 50), tlv(13)))
        assertTrue(f.logs.last().contains("distanceSeen=2 distanceApplied=1 emptyListDistanceSkips=1"))
        assertEquals(listOf("begin", "guide:250"), f.events)
        f.now += 3_000_000_000L
        f.observer.tick()
        assertEquals("end", f.events.last())
    }

    @Test fun `malformed packets cannot keep route alive and appear in diagnostics`() {
        val f = Fixture()
        f.start()
        f.now = 29_000_000_000L
        f.send(byteArrayOf(0, 8, 0, 1, 1))
        assertTrue(f.logs.last().contains("malformed=1"))
        f.now = 30_000_000_000L
        f.observer.tick()
        assertEquals("end", f.events.last())
    }

    @Test fun `pause and controller close clear and late data cannot restore output`() {
        val f = Fixture()
        f.start()
        f.observer.pause()
        f.observer.accept(f.token, route())
        f.observer.close()
        assertFalse(f.observer.accept(f.token, route()))
        assertEquals(listOf("begin", "guide:250", "end"), f.events)
    }

    private class Fixture {
        var now = 0L
        val events = mutableListOf<String>()
        val logs = mutableListOf<String>()
        private val policy = GeelyHudTrialPolicy(object : GeelyHudTrialPort {
            override fun begin() { events += "begin" }
            override fun guidance(frame: GeelyNavigationFrame) { events += "guide:${frame.distanceMeters}" }
            override fun end() { events += "end" }
        }, { true }, {}, { true }, logs::add, { now })
        val observer = GeelyNavigationObserver(logs::add) { now }
        val token: Long

        init {
            observer.onCandidate = { _, frame -> policy.update(frame) }
            token = observer.beginChannel()
        }

        fun start() {
            observer.accept(token, maneuver())
            observer.accept(token, route())
        }

        fun send(payload: ByteArray) {
            observer.accept(token, Iap2Frame(Iap2RouteState.ROUTE_GUIDANCE_UPDATE, payload))
        }
    }
}
