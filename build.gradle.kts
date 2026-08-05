// Top-level build file where you can add configuration options common to all sub-projects/modules.
// Kotlin support is built into AGP 9 — no org.jetbrains.kotlin.android plugin needed.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
}
