package com.shilapi.xcertplay.airplay

import org.junit.Assert.*
import org.junit.Test

class GeelyPhoneNavigationProbeTest {
    @Test fun fixedMapProbeRoundTripsThroughActualBinaryPlistCodec() {
        val decoded = BplistCodec.decode(BplistCodec.encode(GeelyPhoneNavigationProbe.command("amap-map"))) as Map<*, *>
        assertEquals("requestUI", decoded["type"])
        assertEquals("iosamap://rootmap?sourceApplication=DiPlay8600", (decoded["params"] as Map<*, *>)["url"])
        assertFalse((decoded["params"] as Map<*, *>).containsKey("latitude"))
    }

    @Test fun navigationRequiresExplicitCoordinatesAndCoordinateSystem() {
        for (args in listOf(Triple(null, 121.0, "gcj02"), Triple(Double.NaN, 121.0, "gcj02"),
            Triple(31.0, 181.0, "gcj02"), Triple(31.0, 121.0, null))) {
            try {
                GeelyPhoneNavigationProbe.command("amap-navi", args.first, args.second, args.third)
                fail("Missing or invalid destination must not create a navigation URL")
            } catch (_: IllegalArgumentException) { }
        }
        val gcj = GeelyPhoneNavigationProbe.command("amap-navi", 31.0, 121.0, "gcj02").toString()
        val wgs = GeelyPhoneNavigationProbe.command("amap-navi", 31.0, 121.0, "wgs84").toString()
        assertTrue(gcj.contains("lat=31.0&lon=121.0&dev=0"))
        assertTrue(wgs.contains("dev=1"))
    }

    @Test fun arbitraryUrlsAreNotAccepted() {
        try { GeelyPhoneNavigationProbe.command("https://example.com"); fail("not an allowed probe") }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun staleOrReconnectedRequestsAreNotReplayed() {
        assertTrue(GeelyPhoneNavigationProbe.maySend(1, 2, true))
        assertFalse(GeelyPhoneNavigationProbe.maySend(1, 2, false))
        assertFalse(GeelyPhoneNavigationProbe.maySend(1, 2_000_000_002, true))
        assertFalse(GeelyPhoneNavigationProbe.maySend(2, 1, true))
    }

    @Test fun repliesAreCorrelatedOnceAndStorageIsBounded() {
        val receipts = PhoneProbeReceipts()
        for (i in 1..10) receipts.sent(i, "probe-$i")
        assertNull(receipts.take(1))
        assertNull(receipts.take(2))
        assertEquals("probe-3", receipts.take(3))
        assertNull(receipts.take(3))
        assertNull(receipts.take(999))
    }
}
