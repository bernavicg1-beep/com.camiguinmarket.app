plugins {
    // AGP 9.x: may built-in Kotlin na, busa wala na ang org.jetbrains.kotlin.android.
    id("com.android.application") version "9.5.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
    id("com.google.gms.google-services") version "4.6.0" apply false
}
