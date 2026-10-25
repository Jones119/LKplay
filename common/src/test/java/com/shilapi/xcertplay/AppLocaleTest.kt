package com.shilapi.xcertplay

import android.view.View
import com.shilapi.xcertplay.host.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Locale

/** CS11: the persisted preference and the per-context override; there is no LocaleManager on API 19. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 29, 30, 31, 32], manifest = Config.NONE)
class AppLocaleTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Test
    fun arabicWrapsResourcesAndReturnsToSystemWithoutChangingGlobalResources() {
        val original = context.resources.configuration.locales.toLanguageTags()
        AppLocale.save(context, AppLocale.ARABIC)
        assertEquals(AppLocale.ARABIC, AppLocale.preference(context))
        val wrapped = AppLocale.wrap(context)
        assertEquals(Locale("ar"), wrapped.resources.configuration.locales[0])
        assertEquals(View.LAYOUT_DIRECTION_RTL, wrapped.resources.configuration.layoutDirection)
        assertEquals(original, context.resources.configuration.locales.toLanguageTags())
        AppLocale.save(context, AppLocale.SYSTEM)
        assertSame(context, AppLocale.wrap(context))
    }

    @Test
    fun traditionalChineseWrapsResources() {
        AppLocale.save(context, AppLocale.TRADITIONAL_CHINESE)
        assertEquals(AppLocale.TRADITIONAL_CHINESE, AppLocale.preference(context))
        val wrapped = AppLocale.wrap(context)
        assertEquals("zh-TW", wrapped.resources.configuration.locales.toLanguageTags())
        assertEquals("應用程式語言", wrapped.getString(R.string.language_app_language))
    }

    @Test
    fun systemTraditionalRegionsResolveTraditionalResourcesWithoutAnOverride() {
        AppLocale.save(context, AppLocale.SYSTEM)
        for (tag in listOf("zh-TW", "zh-HK", "zh-MO", "zh-Hant")) {
            val systemContext = context.createConfigurationContext(
                android.content.res.Configuration(context.resources.configuration).apply {
                    setLocale(Locale.forLanguageTag(tag))
                },
            )
            assertSame(systemContext, AppLocale.wrap(systemContext))
            assertEquals(tag, "應用程式語言", systemContext.getString(R.string.language_app_language))
        }
    }
}
