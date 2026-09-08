plugins {
    id("com.android.application")
}

android {
    namespace = "com.dhrubo.neonrift"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.dhrubo.neonrift"
        minSdk = 23
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
}
