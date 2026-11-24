import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Upload key lives outside the repo; keystore.properties (git-ignored) points to it.
// Without that file, release builds are simply unsigned.
val keystoreProps: Properties? = rootProject.file("keystore.properties")
    .takeIf { it.exists() }
    ?.let { f -> Properties().also { p -> f.inputStream().use { p.load(it) } } }

android {
    namespace = "com.scaletape"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.lukeadair.blueprintplus"
        minSdk = 26
        targetSdk = 36
        versionCode = 5
        versionName = "2.1"
        // shortcut: arm64 only (ML Kit adds ~11 MB per ABI); no x86 emulator or 32-bit phones. Add ABIs here if needed.
        ndk { abiFilters += "arm64-v8a" }
    }

    signingConfigs {
        if (keystoreProps != null) create("release") {
            storeFile = file(keystoreProps["storeFile"] as String)
            storePassword = keystoreProps["storePassword"] as String
            keyAlias = keystoreProps["keyAlias"] as String
            keyPassword = keystoreProps["keyPassword"] as String
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
            // shortcut: no R8 shrinking; the release runs the same code that was tested on the phone. Turn on
            // isMinifyEnabled/isShrinkResources once a release build can be smoke-tested (emulator or spare phone).
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.10.01")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")

    // On-device OCR, bundled model (no network): last-resort room labels for pages with no readable text.
    implementation("com.google.mlkit:text-recognition:16.0.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303") // Android's org.json is a stub in JVM unit tests
}
