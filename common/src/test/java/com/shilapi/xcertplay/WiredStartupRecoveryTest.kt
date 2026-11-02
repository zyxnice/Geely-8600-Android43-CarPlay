package com.shilapi.xcertplay

import org.junit.Assert.*
import org.junit.Test

class WiredStartupRecoveryTest {
    private val reason = "CarPlay transport error: Wired media TCP did not connect after StartSession"

    @Test fun `only first two startup attempts use fast recovery`() {
        assertTrue(WiredStartupRecovery.fastRetry(true, false, 0, reason))
        assertTrue(WiredStartupRecovery.fastRetry(true, false, 1, reason))
        assertFalse(WiredStartupRecovery.fastRetry(true, false, 2, reason))
        assertFalse(WiredStartupRecovery.fastRetry(true, false, -1, reason))
        assertTrue(WiredStartupRecovery.fastRetry(true, false, 0,
            "CarPlay transport error: NCM bulk OUT did not become ready within 15000ms"))
    }

    @Test fun `established media wireless and unrelated failures keep normal backoff`() {
        assertFalse(WiredStartupRecovery.fastRetry(true, true, 0, reason))
        assertFalse(WiredStartupRecovery.fastRetry(false, false, 0, reason))
        assertFalse(WiredStartupRecovery.fastRetry(true, false, 0, "AirPlay session ended"))
        assertFalse(WiredStartupRecovery.fastRetry(true, false, 0, "CarPlay transport error: other failure"))
    }
}
