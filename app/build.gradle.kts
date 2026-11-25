import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

// AppMetrica API key is NOT stored in the repository. Put `appmetrica.apiKey=<key>` into the
// git-ignored local.properties (or export APPMETRICA_API_KEY). Without it analytics stays off.
val appMetricaKey: String = Properties().also { props ->
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { props.load(it) }
}.getProperty("appmetrica.apiKey") ?: System.getenv("APPMETRICA_API_KEY").orEmpty()

/**
 * Copies the APK of the `:wear` module (same build type as the phone variant) into a generated
 * assets directory as `wear_app.apk`, so the phone app can install it on the watch over ADB.
 */
abstract class EmbedWearApkTask : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val wearApkDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun embed() {
        val apks = wearApkDir.get().asFile.listFiles { f -> f.extension == "apk" }.orEmpty()
        val apk = apks.firstOrNull { !it.name.contains("unsigned") } ?: apks.firstOrNull()
            ?: throw GradleException("No wear APK found in ${wearApkDir.get().asFile}")
        if (apk.name.contains("unsigned")) {
            logger.warn(
                "Embedded wear APK is unsigned (${apk.name}): it cannot be installed on a watch. " +
                    "Configure keystore.properties so both modules are signed with the same key."
            )
        }
        val out = outputDir.get().asFile
        out.deleteRecursively()
        out.mkdirs()
        apk.copyTo(out.resolve("wear_app.apk"), overwrite = true)
    }
}

extensions.configure<ApplicationAndroidComponentsExtension> {
    onVariants { variant ->
        val cap = variant.name.replaceFirstChar { it.uppercase() }
        val wearApkDir = project(":wear").layout.buildDirectory.dir("outputs/apk/${variant.name}")
        val embed = tasks.register<EmbedWearApkTask>("embedWearApk$cap") {
            dependsOn(":wear:assemble$cap")
            this.wearApkDir.set(wearApkDir)
        }
        variant.sources.assets?.addGeneratedSourceDirectory(embed, EmbedWearApkTask::outputDir)
    }
}

android {
    namespace = "dev.handypvz"
    compileSdk {
        version = release(36)
    }

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        applicationId = "dev.handypvz"
        buildConfigField("String", "APPMETRICA_API_KEY", "\"$appMetricaKey\"")
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
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.play.services.wearable)
    implementation(libs.appmetrica) // Yandex AppMetrica analytics
    // ADB over Wi-Fi (TLS pairing + connect) used to install the :wear APK on the watch
    implementation(libs.libadb.android)
    implementation(libs.sun.security.android) // X.509 certificate generation for the ADB TLS key
    implementation(libs.conscrypt.android) // TLSv1.3 + exportKeyingMaterial for pairing
}
