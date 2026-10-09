package com.shilapi.xcertplay

import android.app.LocaleManager
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.LocaleList
import com.shilapi.xcertplay.compat.systemService
import java.util.Locale

/**
 * CS11: 版本隔离持有者——`android.app.LocaleManager` 是 API 33 才有的类型。按本项目约定
 * （`docs/_digest/08 §4.3`、`docs/_digest/17 §2` 第 3 条），它的引用只能出现在"只在 33+ 上被加载
 * 的类"里：[AppLocale.wrap] 由**每次 Activity 创建的 `attachBaseContext`** 调用，在 4.4 上同样会
 * 执行，所以那段方法体绝不能出现 `LocaleManager`，否则车机上是 `NoClassDefFoundError`。
 *
 * 同理，`Locale#getScript`（API 21）和 `Locale.toString()` 的 BCP-47 归一化也放在这里：
 * 显式 script 只可能来自 33+ 的平台语言设置，读它的代码和 `LocaleManager` 一起隔离，
 * 就不必在 4.4 会执行的方法体里放版本三元判断。
 *
 * 本 object 的方法只在 `SDK_INT >= 33` 的分支里被调用（[AppLocale] 负责守卫），4.4/21–32 走
 * prefs + `createConfigurationContext` 的老路径，完全不受影响。
 */
internal object Api33Locale {
    /** 33 is where `LocaleManager` exists; the guard reads as one decision for the 4.4 path. */
    fun supported(level: Int): Boolean = level >= Build.VERSION_CODES.TIRAMISU

    /** The OS-chosen app locale, or null when Android has none for this app. */
    private fun preferredLocale(context: Context): Locale? {
        // LocaleList is not Iterable and getFirst() is API 34, so index it explicitly.
        val locales = context.systemService(LocaleManager::class.java).applicationLocales
        return if (locales.isEmpty) null else locales[0]
    }

    /**
     * The platform's app language as this app's own tag (one of [AppLocale.ALL], never
     * [AppLocale.SYSTEM]), or null when Android has no app locale of its own.
     *
     * Chinese needs the script, not just the region: `zh-Hans-TW` (simplified in a traditional
     * region) and `zh-Hant-CN` both exist, and the region alone would invert them.
     */
    fun preferredLanguageTag(context: Context): String? {
        val selected = preferredLocale(context) ?: return null
        if (selected.language != "zh") return selected.language
        return when (selected.script) {
            "Hant" -> AppLocale.TRADITIONAL_CHINESE
            "Hans" -> AppLocale.SIMPLIFIED_CHINESE
            // No explicit script: the region's usual script decides, as Android 13 resolves it.
            else -> when (selected.country) {
                "TW", "HK", "MO" -> AppLocale.TRADITIONAL_CHINESE
                else -> AppLocale.SIMPLIFIED_CHINESE
            }
        }
    }

    /** Writes the platform app locale; null clears it back to "follow the system". */
    fun saveLanguage(context: Context, locale: Locale?) {
        context.systemService(LocaleManager::class.java).applicationLocales =
            locale?.let { LocaleList(it) } ?: LocaleList.getEmptyLocaleList()
        prefs(context).edit().putBoolean(AppLocale.KEY_MIGRATED, true).remove(AppLocale.KEY_LANGUAGE).apply()
    }

    /**
     * One-time migration of the pre-33 preference into the platform's app locale. A language already
     * chosen through Android Settings wins: the legacy pref is only applied while the platform has no
     * explicit app locale.
     */
    fun migrateLegacyPreferenceOnce(context: Context, localeOf: (String) -> Locale?) {
        val preferences = prefs(context)
        if (preferences.getBoolean(AppLocale.KEY_MIGRATED, false)) return
        val manager = context.systemService(LocaleManager::class.java)
        val previous = localeOf(
            preferences.getString(AppLocale.KEY_LANGUAGE, AppLocale.SYSTEM) ?: AppLocale.SYSTEM,
        )
        // Never overwrite a language already chosen through Android Settings.
        if (manager.applicationLocales.isEmpty && previous != null) {
            manager.applicationLocales = LocaleList(previous)
        }
        preferences.edit().putBoolean(AppLocale.KEY_MIGRATED, true).remove(AppLocale.KEY_LANGUAGE).apply()
    }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(AppLocale.PREFS, Context.MODE_PRIVATE)
}
