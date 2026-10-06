plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.android.vpndialogs"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.android.vpndialogs"
        minSdk = 19
        targetSdk = 19
        versionCode = 1
        versionName = "1.0-cs11"
    }

    lint {
        abortOnError = false
    }
}
