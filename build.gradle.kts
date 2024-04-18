// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    id("com.android.application") version "8.2.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.22" apply false
}

buildscript {
    val kotlinVersion = "1.9.22"

    repositories {
        google()
        mavenCentral()
        maven {
            url = uri("https://jitpack.io")
        }

        dependencies {
            classpath("com.android.tools.build:gradle:8.2.2")
            classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:$kotlinVersion")
        }
    }
}

//allprojects {
//    repositories {
//        google()
//        mavenCentral()
//        maven { url = uri("https://jitpack.io") }
//    }
//}