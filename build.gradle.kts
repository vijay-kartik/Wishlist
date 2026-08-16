// Top-level build file where you can add configuration options common to all sub-projects/modules.
buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        // ObjectBox does not publish a Gradle Plugin Portal marker, so it's applied
        // the classic way (classpath + apply(plugin = ...)) in app/build.gradle.kts.
        // Keep this in sync with `objectbox` in gradle/libs.versions.toml. The
        // buildscript block cannot see the version catalog, so it is repeated here.
        // 5.x is required for on-device vector search (@HnswIndex); 3.7.1 has no
        // vector support at all.
        classpath("io.objectbox:objectbox-gradle-plugin:5.4.2")
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}