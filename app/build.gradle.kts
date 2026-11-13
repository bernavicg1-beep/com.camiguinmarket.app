import java.util.Properties

plugins {
    // Sukad AGP 9.0, built-in na ang Kotlin support, busa wala nay kotlin-android plugin.
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.gms.google-services")
}

// Mga sekreto (Google Maps API key, Mapbox token): ibutang sa local.properties
// (wala kini gi-commit), o sa Gradle property (-PMAPS_API_KEY=...), o sa
// environment variable. Ayaw gyud ibutang sa public repository.
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun secret(name: String): String =
    localProperties.getProperty(name)
        ?: (project.findProperty(name) as String?)
        ?: System.getenv(name)
        ?: ""

val mapsApiKey: String = secret("MAPS_API_KEY")
val mapboxAccessToken: String = secret("MAPBOX_ACCESS_TOKEN")

// Keystore alang sa release APK. Kon wala niini, unsigned ang release APK.
val releaseKeystoreFile: String = secret("RELEASE_KEYSTORE_FILE")
val releaseKeystorePassword: String = secret("RELEASE_KEYSTORE_PASSWORD")
val releaseKeyAlias: String = secret("RELEASE_KEY_ALIAS")
val releaseKeyPassword: String = secret("RELEASE_KEY_PASSWORD")

android {
    namespace = "com.camiguinmarket.app"
    // compileSdk 37 ang gikinahanglan sa AGP 9.5.x, androidx.core 1.19.x ug Compose 2026.x.
    compileSdk = 37

    defaultConfig {
        applicationId = "com.camiguinmarket.app"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"

        // Gigamit sa AndroidManifest.xml: ${MAPS_API_KEY}
        manifestPlaceholders["MAPS_API_KEY"] = mapsApiKey
        // Gigamit sa Mapbox WebView screen.
        buildConfigField("String", "MAPBOX_ACCESS_TOKEN", "\"$mapboxAccessToken\"")
        // Gigamit sa Directions API (ruta A→B) ug Geocoding API (search bar).
        buildConfigField("String", "MAPS_API_KEY", "\"$mapsApiKey\"")
    }

    signingConfigs {
        // Buhaton lang kon may keystore nga gihatag (CI o local.properties).
        if (releaseKeystoreFile.isNotBlank()) {
            create("release") {
                storeFile = file(releaseKeystoreFile)
                storePassword = releaseKeystorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // null kon walay keystore: unsigned ang APK (installable lang kon i-sign).
            signingConfig = signingConfigs.findByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // Sa AGP 9 built-in Kotlin, ang kotlinOptions naa na sa top-level kotlin {}
    // block ug ang jvmTarget mo-follow na sa targetCompatibility sa ibabaw.
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.core:core-ktx:1.19.1")

    implementation(platform("com.google.firebase:firebase-bom:35.2.0"))
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-firestore")

    // Google Map sulod sa Compose app (Option A).
    implementation("com.google.maps.android:maps-compose:9.0.1")
    implementation("com.google.android.gms:play-services-location:21.4.0")
}
