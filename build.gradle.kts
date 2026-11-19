// Same toolchain as MarkOut: AGP 9.4.1 compiles Kotlin itself, so there is no
// org.jetbrains.kotlin.android plugin; Kotlin compiler plugins are applied directly.
plugins {
    id("com.android.application") version "9.4.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.2.10" apply false
}
