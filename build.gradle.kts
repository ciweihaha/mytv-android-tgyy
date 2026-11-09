// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    // Round 4: AGP 9.x has built-in Kotlin support and no longer allows
    // applying the Kotlin Gradle plugin (org.jetbrains.kotlin.android);
    // the compose compiler and serialization compiler plugins stay.
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.kotlin.serialization) apply (false)
}