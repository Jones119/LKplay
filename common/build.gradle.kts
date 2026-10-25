plugins {
    id("com.android.library")
}

android {
    namespace = "com.shilapi.xcertplay.host"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 19
        // CS11: core library desugaring 要求 multidex（AGP 硬性检查）
        multiDexEnabled = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
        isCoreLibraryDesugaringEnabled = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        // Production code logs through android.util.Log, which the JVM tests otherwise reject with
        // "Method w in android.util.Log not mocked". Returning defaults makes those calls no-ops
        // (this fixed thirteen tests in :shared) so the tests check the behaviour they describe.
        unitTests.isReturnDefaultValues = true
        // The UI suite covers several SDKs and locale-specific resource sandboxes.
        unitTests.all {
            it.maxHeapSize = "1g"
            // CS11: android.util.Log is a no-op here (`isReturnDefaultValues` above), so a test that needs
            // to show why an assertion failed has only println. Gradle swallows it unless this is on.
            it.testLogging { events("failed"); showStandardStreams = true }
        }
    }

    // CS11: NewApi now blocks the build. The remaining checks below are either lint's own bugs or
    // meaningless with targetSdk 19 (runtime BLE permissions, Play target-level policy).
    lint {
        abortOnError = true
        disable += listOf(
            "UnsafeOptInUsageError", "UnsafeOptInUsageWarning",
            "MissingPermission", "ExpiredTargetSdkVersion", "ForegroundServiceType",
            "StringFormatMatches", "StringFormatInvalid", "RestrictedApi",
            "SuspiciousIndentation", "PropertyEscape", "MissingTranslation",
        )
    }

    // CS11 / API 19 盲区：开启 core library desugaring 后，lint 的 NewApi 只覆盖 `android.*` 平台 API，
    // **不报** `java.*` 库 API（Math.floorDiv(API 24)、Objects.requireNonNullElse、java.nio.file、
    // CompletableFuture…）。API19 审计的三份 lint 报告里 `java.` 命中为 0，而 NtpClock.kt 当时在 4.4 上
    // 无条件调用 Math.floorDiv。替代守卫是 JVM 单测
    // `shared/src/test/java/com/shilapi/xcertplay/airplay/ForbiddenLibraryApiTest.kt`（每次跑单测都会
    // 扫描 shared/common/mobile 的 main 源码）；说明见 docs/_digest/49-API19越界API审计.md。
}

dependencies {
    api(project(":shared"))
    implementation(libs.androidx.activity)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    coreLibraryDesugaring(libs.desugarJdkLibs)
    testImplementation(libs.junit)
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation("org.mockito:mockito-core:5.20.0")
    testImplementation(libs.jmdns)
}
