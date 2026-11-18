package com.shilapi.xcertplay.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeelyFactoryHotspotTest {
    @Test fun onlyOptedInExactFirmwareCanUseTheFactoryPath() {
        val display = "06.03.08600.H53.00060"
        assertTrue(GeelyFactoryHotspot.matches(18, "C3ALFUS", display, true))
        assertFalse(GeelyFactoryHotspot.matches(18, "C3ALFUS", display, false))
        assertFalse(GeelyFactoryHotspot.matches(18, "C3ALFUS", "$display.1", true))
        assertFalse(GeelyFactoryHotspot.matches(18, "another-board", display, true))
        assertFalse(GeelyFactoryHotspot.matches(19, "C3ALFUS", display, true))
    }

    @Test fun stationOffIsQueuedBeforeApAndDoesNotToggleAnIdleStation() {
        val actions = mutableListOf<String>()
        fun start(stationActive: Boolean) = requestGeelySavedAp(
            stationActive = stationActive,
            cancelled = { false },
            setStationEnabled = { actions.add("station=$it") },
            enableSavedAp = { actions.add("saved-ap") },
            restoreFailure = { error("Unexpected restore failure") },
        )
        assertTrue(start(true))
        assertEquals(listOf("station=false", "saved-ap"), actions)
        actions.clear()
        assertTrue(start(false))
        assertEquals(listOf("saved-ap"), actions)
    }

    @Test fun rejectionOrCancellationBeforeApRestoresStationButNeverStartsTwice() {
        for (cancelAfterStationOff in listOf(false, true)) {
            val actions = mutableListOf<String>()
            val accepted = requestGeelySavedAp(
                stationActive = true,
                cancelled = { cancelAfterStationOff && actions.isNotEmpty() },
                setStationEnabled = { actions.add("station=$it") },
                enableSavedAp = { actions.add("saved-ap"); false },
                restoreFailure = { error("Unexpected restore failure") },
            )
            assertFalse(accepted)
            assertEquals(
                if (cancelAfterStationOff) listOf("station=false", "station=true")
                else listOf("station=false", "saved-ap", "station=true"),
                actions,
            )
        }
    }
}
