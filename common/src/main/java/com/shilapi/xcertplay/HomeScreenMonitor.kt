package com.shilapi.xcertplay

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper

/**
 * Whether a home screen is in front (the car's normal home, its map home or MyCar, or whichever
 * launcher is the default home, such as a third-party car launcher), from the newest resumed
 * activity in the owner's Usage Access events or Accessibility events.
 * Overlays and panels are not activities, so they leave the answer as it is.
 */
internal class HomeScreenMonitor(context: Context, private val onChange: (Boolean) -> Unit) {
    private val context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var reported: Boolean? = null
    @Volatile private var homePackages = HOME_PACKAGES + KNOWN_CAR_LAUNCHERS
    private val listener: (String) -> Unit = ::handleForegroundPackage
    @Volatile private var active = false

    val running: Boolean get() = active

    /** Main thread. */
    fun start() {
        if (running) return
        active = true
        reported = null
        homePackages = queryHomePackages(context)

        // Register for external foreground updates (e.g. from AccessibilityService)
        foregroundListener = listener
    }

    /** Main thread. */
    fun stop() {
        active = false
        if (foregroundListener === listener) {
            foregroundListener = null
        }
        main.removeCallbacksAndMessages(null)
    }

    private fun handleForegroundPackage(pkg: String) {
        if (!running) return
        val visible = isHomePackage(pkg)
        if (visible != reported) {
            reported = visible
            main.post { if (running) onChange(visible) }
        }
    }

    private fun isHomePackage(pkg: String): Boolean = pkg in homePackages

    companion object {
        @Volatile private var foregroundListener: ((String) -> Unit)? = null

        /** Notifies of a foreground package change from an accessibility or system service. */
        fun notifyForegroundPackage(pkg: String) {
            foregroundListener?.invoke(pkg)
        }

        // The car's own home activities, in addition to whichever launcher Android uses by default.
        val HOME_PACKAGES = setOf("com.android.launcher3")

        // Known third-party car launchers
        val KNOWN_CAR_LAUNCHERS = setOf(
            "com.smg.dydesktop",          // 迪友桌面 (常见主流包名)
            "com.smg.dydesktop.pro",      // 迪友桌面 Pro
            "com.dy.launcher",            // 迪友桌面 (部分渠道)
            "com.king.dyzm",              // 迪友桌面
            "com.king.diyou",
            "com.dudu.android.launcher",  // 嘟嘟桌面
            "com.dudu.android.launcher.mini",
            "com.tencent.autolauncher",   // 腾讯车联
            "com.mx.launcher",            // 喵驾桌面
        )

        /** CS11: API 19 has no UsageStatsManager; foreground detection relies on accessibility events. */
        fun hasAccess(context: Context): Boolean = false

        /** Query all launcher packages declared on the system. */
        fun queryHomePackages(context: Context): Set<String> {
            val set = (HOME_PACKAGES + KNOWN_CAR_LAUNCHERS).toMutableSet()
            defaultHome(context)?.let { set.add(it) }
            runCatching {
                val pm = context.packageManager
                val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                val list = pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
                for (info in list) {
                    val pkg = info.activityInfo?.packageName
                    if (!pkg.isNullOrEmpty() && pkg != "android" && pkg != context.packageName) {
                        set.add(pkg)
                    }
                }
            }
            return set
        }

        /** The launcher Android uses as home now, unless that is the chooser or DiPlay itself. */
        fun defaultHome(context: Context): String? = runCatching {
            context.packageManager.resolveActivity(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
                PackageManager.MATCH_DEFAULT_ONLY,
            )?.activityInfo?.packageName
        }.getOrNull()?.takeIf { it != "android" && it != context.packageName }
    }
}
