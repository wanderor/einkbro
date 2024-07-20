// Top-level build file where you can add configuration options common to all sub-projects/modules.

buildscript {
    repositories {
        google()
        gradlePluginPortal()
    }
    dependencies {
        classpath("com.android.tools.build:gradle:8.4.0")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.0.0")
        classpath("org.jetbrains.kotlin:kotlin-serialization:2.0.0")
        classpath("com.google.devtools.ksp:symbol-processing-gradle-plugin:2.0.0-1.0.21")
    }
}

allprojects {
    repositories {
        google()
        mavenCentral()
    }
}

