package com.shilapi.xcertplay.hud

import org.junit.Assert.*
import org.junit.Test

class GeelyNavigationFrameTest {
    @Test fun `candidate uses OEM ICON and preserves unknown optional values`() {
        val frame = GeelyNavigationFrame.from(snapshot(type = 1))!!
        val fields = frame.candidateFields()
        assertEquals(2, fields["ICON"])
        assertEquals(10001, fields["KEY_TYPE"])
        assertEquals(250, fields["SEG_REMAIN_DIS"])
        assertEquals("人民路", fields["NEXT_ROAD_NAME"])
        assertFalse(fields.containsKey("NEW_ICON"))
        assertFalse(fields.containsKey("EXTRA_STATE"))
        assertFalse(fields.containsKey("IS_BYD_MAP"))
        assertFalse(fields.containsKey("ROUTE_REMAIN_DIS"))
        assertFalse(fields.containsKey("ROUTE_REMAIN_TIME"))
    }

    @Test fun `turn direction and roundabout exit do not use BYD HUD glyph values`() {
        val cases = mapOf(1 to 2, 2 to 3, 13 to 4, 14 to 5, 47 to 6, 48 to 7,
            4 to 8, 3 to 9, 6 to 11, 7 to 12, 10 to 15)
        cases.forEach { (apple, icon) -> assertEquals(icon, GeelyNavigationFrame.from(snapshot(apple))!!.icon) }
        assertEquals(19, GeelyNavigationFrame.from(snapshot(4).copy(drivingSide = 1))!!.icon)
        val exit = GeelyNavigationFrame.from(snapshot(30))!!
        assertEquals(11, exit.icon)
        assertEquals(3, exit.roundaboutExit)
        assertEquals(17, GeelyNavigationFrame.from(snapshot(30).copy(drivingSide = 1))!!.icon)
    }

    @Test fun `unsupported or incomplete data cannot become an output candidate`() {
        assertNull(GeelyNavigationFrame.from(snapshot(255)))
        assertNull(GeelyNavigationFrame.from(snapshot().copy(distanceKnown = false)))
        assertNull(GeelyNavigationFrame.from(snapshot().copy(distanceMeters = -1)))
        assertNull(GeelyNavigationFrame.from(snapshot().copy(drivingSide = 2)))
        assertNull(GeelyNavigationFrame.from(snapshot().copy(drivingSideKnown = false)))
        assertNull(GeelyNavigationFrame.from(snapshot().copy(road = "路\uD83D\uDE97")))
        assertEquals("", GeelyNavigationFrame.from(snapshot().copy(road = ""))!!.road)
    }

    @Test fun `optional values are checked without inventing or wrapping numbers`() {
        val frame = GeelyNavigationFrame.from(snapshot().copy(remainingMeters = 0,
            remainingSeconds = Int.MAX_VALUE.toLong() + 1))!!
        assertEquals(0, frame.candidateFields()["ROUTE_REMAIN_DIS"])
        assertNull(frame.remainingSeconds)
    }

    private fun snapshot(type: Int = 2) = Iap2RouteManeuver(250, type, 0, "人民路", null, null, null, true, true)
}
