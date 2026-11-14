plugins {
    // AGP 9.x: Kotlin is built in, so org.jetbrains.kotlin.android is gone.
    id("com.android.application") version "9.5.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
    id("com.google.gms.google-services") version "4.6.0" apply false
}
