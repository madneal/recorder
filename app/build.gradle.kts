plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val releaseVersionName = providers.gradleProperty("releaseVersion").orElse("1.0.3").get()
val releaseVersionCode = providers.gradleProperty("releaseVersionCode").orElse("3").get().toInt()
val releaseKeystorePath = providers.environmentVariable("RECORDER_KEYSTORE_PATH").orNull
val releaseKeystorePassword = providers.environmentVariable("RECORDER_KEYSTORE_PASSWORD").orNull
val releaseKeyAlias = providers.environmentVariable("RECORDER_KEY_ALIAS").orNull
val releaseKeyPassword = providers.environmentVariable("RECORDER_KEY_PASSWORD").orNull
val releaseSigningConfigured = listOf(
    releaseKeystorePath,
    releaseKeystorePassword,
    releaseKeyAlias,
    releaseKeyPassword
).all { !it.isNullOrBlank() }

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

    signingConfigs {
        if (releaseSigningConfigured) {
            create("release") {
                storeFile = file(releaseKeystorePath!!)
                storePassword = releaseKeystorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (releaseSigningConfigured) {
                signingConfig = signingConfigs.getByName("release")
            }
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
