// Mappls' build plugin turns the app's two config files into what its map SDK reads at startup (without it the SDK throws
// "Please apply plugin com.mappls.services.android"). Applied in app/build.gradle.kts.
buildscript {
    repositories {
        google()
        mavenCentral()
        maven(url = "https://maven.mappls.com/repository/mappls/")
    }
    dependencies {
        classpath("com.mappls.services:mappls-services:1.0.1")
    }
}

plugins {
    id("com.android.application") version "9.4.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10" apply false
}
