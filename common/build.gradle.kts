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
        unitTests.all { it.maxHeapSize = "1g" }
    }

    // CS11: lint 只作清单使用，不阻断构建（M2 修复期）
    lint {
        abortOnError = false
        // lint 自身 bug：ExperimentalDetector 在 CarPlayController 解析崩溃
        disable += listOf("UnsafeOptInUsageError", "UnsafeOptInUsageWarning")
    }
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
