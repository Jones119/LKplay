package com.shilapi.xcertplay.airplay

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * CS11: the module manifests must not declare attributes or permissions that only exist above API 19.
 * With `targetSdk = 19` each one is dead weight and a sign that a higher-version path came back.
 */
class Api19ManifestTest {
    private val forbidden = listOf(
        "android:resizeableActivity",
        "android:dataExtractionRules",
        "android:localeConfig",
        "android:roundIcon",
        "android:foregroundServiceType",
        "android:usesCleartextTraffic",
        "android.permission.POST_NOTIFICATIONS",
        "android.permission.NEARBY_WIFI_DEVICES",
        "android.permission.BLUETOOTH_CONNECT",
        "android.permission.FOREGROUND_SERVICE",
    )

    @Test
    fun manifestsDeclareNoHigherApiAttributesOrPermissions() {
        val root = repositoryRoot()
        val offenders = mutableListOf<String>()
        for (module in listOf("shared", "common", "mobile")) {
            val manifest = File(root, "$module/src/main/AndroidManifest.xml")
            if (!manifest.isFile) continue
            val text = manifest.readText()
            for (item in forbidden) {
                if (item in text) offenders += "$item in ${manifest.relativeTo(root).path}"
            }
        }
        assertTrue(
            "Manifest entries that require API 21+ came back:\n" + offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    private fun repositoryRoot(): File {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        while (!File(dir, "settings.gradle.kts").isFile) {
            dir = dir.parentFile ?: error("repository root not found from ${System.getProperty("user.dir")}")
        }
        return dir
    }
}
