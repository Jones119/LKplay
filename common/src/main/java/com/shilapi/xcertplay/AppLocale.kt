// SPDX-License-Identifier: AGPL-3.0-only
package com.shilapi.xcertplay

import android.app.Activity
import android.app.AlertDialog
import android.os.Build
import android.content.Context
import android.content.res.Configuration
import com.shilapi.xcertplay.host.R
import java.util.Locale

/** Platform app locales on Android 13+, with a persisted context override on older Android. */
object AppLocale {
    const val SYSTEM = "system"
    const val ENGLISH = "en"
    const val SIMPLIFIED_CHINESE = "zh"
    const val TRADITIONAL_CHINESE = "zh-TW"
    const val ARABIC = "ar"
    const val RUSSIAN = "ru"
    const val SPANISH = "es"
    const val UKRAINIAN = "uk"

    val ALL = listOf(SYSTEM, ENGLISH, SIMPLIFIED_CHINESE, TRADITIONAL_CHINESE, ARABIC, RUSSIAN, SPANISH, UKRAINIAN)

    // CS11: Api33Locale 读写同一份 prefs（平台语言与老偏好之间的迁移），所以这几个键是 internal 的。
    internal const val PREFS = "diplay"
    internal const val KEY_LANGUAGE = "app_language"

    internal const val KEY_MIGRATED = "app_language_platform_migrated"

    fun preference(context: Context): String {
        // CS11: LocaleManager(33) 与 Locale#getScript(21) 都在 Api33Locale 里；本方法体内不得出现
        // 这两个越界 API，4.4/21–32 只走下面的 prefs 分支。
        if (Api33Locale.supported(Build.VERSION.SDK_INT)) {
            val tag = Api33Locale.preferredLanguageTag(context) ?: return SYSTEM
            return if (tag in ALL) tag else SYSTEM
        }
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LANGUAGE, SYSTEM)?.takeIf { it in ALL } ?: SYSTEM
    }

    fun save(context: Context, language: String) {
        require(language in ALL)
        // CS11: 同上——LocaleManager 只在 Api33Locale 里出现
        if (Api33Locale.supported(Build.VERSION.SDK_INT)) {
            Api33Locale.saveLanguage(context, locale(language))
        } else {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_LANGUAGE, language).apply()
        }
    }

    /** On Android 13+, the OS is the single source of truth for the app language. */
    fun wrap(context: Context): Context {
        // CS11: wrap() 由 attachBaseContext 在**每次** Activity 创建时调用（4.4 上也一样），所以
        // LocaleManager 的三处用法全部委托给 Api33Locale，本方法体内不得出现该类型。
        if (Api33Locale.supported(Build.VERSION.SDK_INT)) {
            Api33Locale.migrateLegacyPreferenceOnce(context, ::locale)
            return context
        }
        val locale = locale(preference(context)) ?: return context
        val configuration = Configuration(context.resources.configuration).apply {
            setLocale(locale)
            setLayoutDirection(locale)
        }
        return context.createConfigurationContext(configuration)
    }

    fun showPicker(activity: Activity) {
        var selected = ALL.indexOf(preference(activity)).coerceAtLeast(0)
        AlertDialog.Builder(activity)
            .setTitle(R.string.language_app_language)
            .setSingleChoiceItems(ALL.map { displayName(activity, it) }.toTypedArray(), selected) { _, index ->
                selected = index
            }
            .setPositiveButton(R.string.language_apply) { _, _ ->
                val next = ALL[selected]
                if (next != preference(activity)) {
                    save(activity, next)
                    // LocaleManager recreates activities itself on Android 13+.
                    if (Build.VERSION.SDK_INT < 33) activity.recreate()
                }
            }
            .setNegativeButton(R.string.common_cancel, null)
            .show()
    }

    /** Names stay in their native form for every language; only "system default" is localized. */
    fun displayName(context: Context, language: String): String = when (language) {
        SYSTEM -> context.getString(R.string.language_system_default)
        ENGLISH -> "English"
        SIMPLIFIED_CHINESE -> "简体中文"
        TRADITIONAL_CHINESE -> "繁體中文"
        ARABIC -> "العربية"
        RUSSIAN -> "Русский"
        SPANISH -> "Español"
        UKRAINIAN -> "Українська"
        else -> language
    }

    private fun locale(language: String): Locale? = when (language) {
        ENGLISH -> Locale.ENGLISH
        SIMPLIFIED_CHINESE -> Locale.SIMPLIFIED_CHINESE
        TRADITIONAL_CHINESE -> Locale.TRADITIONAL_CHINESE
        ARABIC -> Locale("ar")
        RUSSIAN -> Locale("ru")
        SPANISH -> Locale("es")
        UKRAINIAN -> Locale("uk")
        else -> null
    }
}
