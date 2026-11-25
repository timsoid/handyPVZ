import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "dev.handypvz.wear"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "dev.handypvz"
        minSdk = 30
        targetSdk = 36
        versionCode = 2
        versionName = "1.0.1"
        // Real devices only (phones and watches are ARM): drops x86 emulator native libs
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    // Optional release signing: create <root>/keystore.properties with storeFile, storePassword,
    // keyAlias, keyPassword. Both modules MUST use the same key (Wear Data Layer requirement).
    val keystoreFile = rootProject.file("keystore.properties")
    if (keystoreFile.exists()) {
        val props = Properties().apply { keystoreFile.inputStream().use { load(it) } }
        signingConfigs {
            create("release") {
                storeFile = rootProject.file(props.getProperty("storeFile"))
                storePassword = props["storePassword"] as String
                keyAlias = props.getProperty("keyAlias")
                keyPassword = props["keyPassword"] as String
            }
        }
    }

    buildTypes {
        release {
            signingConfigs.findByName("release")?.let { signingConfig = it }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.play.services.wearable)
    implementation(libs.zxing.core)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
}
