package com.shilapi.xcertplay.hud

import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class GeelyHudPreferencesTest {
    @Test fun `absent setting defaults on and manual off survives another preference read`() {
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("diplay_geely_hud", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        try {
            assertTrue(GeelyHudPreferences.enabled(context))
            GeelyHudPreferences.setEnabled(context, false)
            assertFalse(GeelyHudPreferences.enabled(context))
            GeelyHudPreferences.setEnabled(context, true)
            assertTrue(GeelyHudPreferences.enabled(context))
        } finally { prefs.edit().clear().commit() }
    }
}
