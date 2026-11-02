package com.shilapi.xcertplay

import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class GeelyAudioPreferenceTest {
    @Test fun navigationDuckingDefaultsOffAndBothManualChoicesPersist() {
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        try {
            assertFalse(AirPlayPersistence.loadNavigationDuckingEnabled(context))
            AirPlayPersistence.saveNavigationDuckingEnabled(context, true)
            assertTrue(AirPlayPersistence.loadNavigationDuckingEnabled(context))
            AirPlayPersistence.saveNavigationDuckingEnabled(context, false)
            assertFalse(AirPlayPersistence.loadNavigationDuckingEnabled(context))
        } finally { prefs.edit().clear().commit() }
    }

    @Test fun explicitRoutingChoicesSurviveReadsAndGenericPlatformsStayOff() {
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        try {
            assertFalse(AirPlayPersistence.loadGeelyAudioRouting(context))
            AirPlayPersistence.saveGeelyAudioRouting(context, true)
            assertTrue(AirPlayPersistence.loadGeelyAudioRouting(context))
            assertFalse(AirPlayPersistence.loadGeelyNavigationAlert(context))
            AirPlayPersistence.saveGeelyAudioRouting(context, false)
            assertFalse(AirPlayPersistence.loadGeelyAudioRouting(context))
        } finally { prefs.edit().clear().commit() }
    }
}
