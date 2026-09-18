plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val releaseVersionName = providers.gradleProperty("releaseVersion").orElse("1.0.3").get()
val releaseVersionCode = providers.gradleProperty("releaseVersionCode").orElse("3").get().toInt()

android {
    namespace = "com.neal.recorder"
    compileSdk = 35

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        applicationId = "com.neal.recorder"
        minSdk = 29
        targetSdk = 35
        versionCode = releaseVersionCode
        versionName = releaseVersionName
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}
