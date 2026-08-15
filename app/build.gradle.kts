plugins {
    alias(libs.plugins.android.application)
    // With android.builtInKotlin=false (see gradle.properties), AGP no longer compiles
    // Kotlin itself, so the classic Kotlin Android plugin must be applied explicitly.
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.kapt)
    id("kotlin-parcelize")
}

// ObjectBox has no Gradle Plugin Portal marker artifact, so it can't be applied via
// `alias(libs.plugins.objectbox)` / the plugins{} DSL — that fails with "could not
// resolve plugin artifact 'io.objectbox:io.objectbox.gradle.plugin'". Instead it's
// added as a buildscript classpath dependency (see root build.gradle.kts) and applied
// the classic way here, last, per ObjectBox's own setup docs.
apply(plugin = "io.objectbox")

android {
    namespace = "com.example.app.wishlist"
    compileSdk {
        version = release(37) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.example.app.wishlist"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
    buildFeatures {
        compose = true
    }
}

// Pins Kotlin compilation (incl. kapt's stub-generation task) to the same JDK as
// compileOptions above. Without this, Kotlin falls back to whatever JDK is running
// Gradle (often newer than 17), causing "Inconsistent JVM Target Compatibility
// Between Java and Kotlin Tasks" — a toolchain removes the machine-dependent guesswork.
kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.core)

    // ObjectBox (Knowledge Graph Storage)
    implementation(libs.objectbox.kotlin)
    kapt("io.objectbox:objectbox-processor:3.7.1")

    // Logging
    implementation(libs.timber)

    // JSON Processing
    implementation(libs.gson)

    // Background Tasks
    implementation(libs.androidx.work.runtime.ktx)

    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}