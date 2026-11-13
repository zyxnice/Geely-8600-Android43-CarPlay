package com.shilapi.xcertplay.media

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeelyWifiMediaGuardTest {
    @Test fun acceptsOnlyVerifiedFirmware() {
        assertTrue(GeelyWifiMediaGuard.supportsVehicle(18, "C3ALFUS", "06.03.08600.H53.00060"))
        assertFalse(GeelyWifiMediaGuard.supportsVehicle(19, "C3ALFUS", "06.03.08600.H53.00060"))
        assertFalse(GeelyWifiMediaGuard.supportsVehicle(18, "other", "06.03.08600.H53.00060"))
        assertFalse(GeelyWifiMediaGuard.supportsVehicle(18, "C3ALFUS", "06.03.08600.H52.00060"))
        assertFalse(GeelyWifiMediaGuard.supportsVehicle(18, "C3ALFUS", "06.03.08600.H53.00061"))
        assertFalse(GeelyWifiMediaGuard.supportsVehicle(18, null, null))
    }
}
