plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    kotlin("plugin.serialization") version "2.2.10"
}

android {
    namespace = "carlink.com"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "carlink.com"
        minSdk = 26
        targetSdk = 37
        versionCode = 3
        versionName = "1.0.3"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    // Vosk offline keyword spotting (wake word "GUNNU") — no account needed
    implementation(libs.vosk.android)

    // AndroidX Media (MediaSession for steering wheel controls)
    implementation(libs.androidx.media)

    // Kotlin Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // DataStore Preferences (persist settings: AccessKey, wake word, etc.)
    implementation(libs.androidx.datastore.preferences)

    // Jetpack Navigation for Compose
    implementation(libs.androidx.navigation.compose)

    // App Updater
    implementation(libs.app.updater)

    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Fix for Jetifier namespace clash with AppUpdater
    implementation("androidx.vectordrawable:vectordrawable:1.1.0")
    implementation("androidx.vectordrawable:vectordrawable-animated:1.1.0")
}