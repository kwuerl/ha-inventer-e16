// Top-level build file. AGP 9 compiles Kotlin itself ("built-in Kotlin"); the Kotlin
// Android plugin is only put on the classpath here to pin the Kotlin compiler version.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
