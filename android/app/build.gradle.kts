plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "pt.artur.motadash"
    compileSdk = 34

    defaultConfig {
        applicationId = "pt.artur.motadash"
        minSdk = 26
        targetSdk = 34
        // CI sets the build number so every APK installs over the last one.
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "0.1." + (System.getenv("GITHUB_RUN_NUMBER") ?: "0")
    }

    signingConfigs {
        // A fixed key kept in the repo, so each new APK updates the
        // installed app instead of needing an uninstall. Debug only: this
        // app is not published anywhere.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    // BRouter (offline road routes): its plain Java modules, checked out by CI next to the app.
    val brouterSrc = rootProject.file("brouter-src")
    if (brouterSrc.exists()) sourceSets["main"].java.srcDirs(
        listOf("brouter-core", "brouter-mapaccess", "brouter-util", "brouter-expressions", "brouter-codec")
            .map { "$brouterSrc/$it/src/main/java" })
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.03")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // Offline vector map (reads the PMTiles files built by the "Map data" workflow).
    implementation("org.maplibre.gl:android-sdk:12.3.1")
}
