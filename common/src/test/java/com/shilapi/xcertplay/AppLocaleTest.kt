package com.shilapi.xcertplay

import android.app.LocaleManager
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.os.LocaleList
import android.view.View
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
class AppLocaleTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val manager get() = context.getSystemService(LocaleManager::class.java)

    @Test fun pickerAndSystemSettingsShareTheSamePreference() {
        AppLocale.save(context, AppLocale.ARABIC)
        assertEquals("ar", manager.applicationLocales.toLanguageTags())
        manager.applicationLocales = LocaleList.forLanguageTags("es")
        assertEquals(AppLocale.SPANISH, AppLocale.preference(context))
        assertSame(context, AppLocale.wrap(context))
        AppLocale.save(context, AppLocale.SYSTEM)
        assertTrue(manager.applicationLocales.isEmpty)
        assertEquals(AppLocale.SYSTEM, AppLocale.preference(context))
    }

    @Test fun oldPreferenceMigratesOnceAndCannotOverrideLaterSystemChanges() {
        context.getSharedPreferences("diplay", Context.MODE_PRIVATE).edit()
            .putString("app_language", "ar").commit()
        AppLocale.wrap(context)
        assertEquals("ar", manager.applicationLocales.toLanguageTags())
        manager.applicationLocales = LocaleList.getEmptyLocaleList()
        AppLocale.wrap(context)
        assertEquals(AppLocale.SYSTEM, AppLocale.preference(context))
    }

    @Test fun existingSystemChoiceWinsOverLegacyPreference() {
        context.getSharedPreferences("diplay", Context.MODE_PRIVATE).edit()
            .putString("app_language", "ar").commit()
        manager.applicationLocales = LocaleList.forLanguageTags("zh-CN")
        AppLocale.wrap(context)
        assertEquals(AppLocale.SIMPLIFIED_CHINESE, AppLocale.preference(context))
        assertEquals("zh-CN", manager.applicationLocales.toLanguageTags())
    }

    @Test @Config(sdk = [28, 32])
    fun olderAndroidWrapsArabicAndReturnsToSystemWithoutChangingGlobalResources() {
        val original = context.resources.configuration.locales.toLanguageTags()
        AppLocale.save(context, AppLocale.ARABIC)
        val wrapped = AppLocale.wrap(context)
        assertEquals(Locale("ar"), wrapped.resources.configuration.locales[0])
        assertEquals(View.LAYOUT_DIRECTION_RTL, wrapped.resources.configuration.layoutDirection)
        assertEquals(original, context.resources.configuration.locales.toLanguageTags())
        AppLocale.save(context, AppLocale.SYSTEM)
        assertSame(context, AppLocale.wrap(context))
    }

    @Test @Config(sdk = [29], qualifiers = "w1280dp-h480dp-land")
    fun olderAndroidLeavesWindowAndFontConfigurationToTheSystem() {
        AppLocale.save(context, AppLocale.SIMPLIFIED_CHINESE)
        var override: Configuration? = null
        val activityContext = object : ContextWrapper(context) {
            override fun createConfigurationContext(overrideConfiguration: Configuration): Context {
                override = Configuration(overrideConfiguration)
                return super.createConfigurationContext(overrideConfiguration)
            }
        }

        val wrapped = AppLocale.wrap(activityContext)
        val configuration = requireNotNull(override)
        assertEquals(Locale.SIMPLIFIED_CHINESE, wrapped.resources.configuration.locales[0])
        assertEquals(0, configuration.screenWidthDp)
        assertEquals(0, configuration.screenHeightDp)
        assertEquals(0, configuration.densityDpi)
        assertEquals(Configuration.ORIENTATION_UNDEFINED, configuration.orientation)
        assertEquals(0f, configuration.fontScale)
    }
}
