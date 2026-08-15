// Top-level build file where you can add configuration options common to all sub-projects/modules.
buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        // ObjectBox does not publish a Gradle Plugin Portal marker, so it's applied
        // the classic way (classpath + apply(plugin = ...)) in app/build.gradle.kts.
        classpath("io.objectbox:objectbox-gradle-plugin:3.7.1")
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}