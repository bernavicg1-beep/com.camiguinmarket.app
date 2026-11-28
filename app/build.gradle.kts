import java.util.Properties

plugins {
    // Since AGP 9.0 Kotlin support is built in, so no kotlin-android plugin is needed.
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.gms.google-services")
    id("com.google.firebase.crashlytics")
}

// Secrets (Workers URL, signing): put them in local.properties
// (not committed), or in a Gradle property (-PR2_UPLOAD_URL=...), or in an
// environment variable. Never commit them to a public repository.
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun secret(name: String): String =
    localProperties.getProperty(name)
        ?: (project.findProperty(name) as String?)
        ?: System.getenv(name)
        ?: ""

// Maps use Leaflet with OpenStreetMap tiles, so no Google or Mapbox key is needed.
// Base URL of the Cloudflare Worker that uploads photos to R2.
// Example: https://camiguin-market-images.<subdomain>.workers.dev
val r2UploadUrl: String = secret("R2_UPLOAD_URL")

// Keystore for the release APK. Without it the release APK is unsigned.
val releaseKeystoreFile: String = secret("RELEASE_KEYSTORE_FILE")
val releaseKeystorePassword: String = secret("RELEASE_KEYSTORE_PASSWORD")
val releaseKeyAlias: String = secret("RELEASE_KEY_ALIAS")
val releaseKeyPassword: String = secret("RELEASE_KEY_PASSWORD")

android {
    namespace = "com.camiguinmarket.app"
    // compileSdk 37 is required by AGP 9.5.x, androidx.core 1.19.x and Compose 2026.x.
    compileSdk = 37

    defaultConfig {
        applicationId = "com.camiguinmarket.app"
        minSdk = 24
        targetSdk = 36
        versionCode = 41
        versionName = "0.41"

        // The Worker that stores photos in Cloudflare R2 (no secret here).
        buildConfigField("String", "R2_UPLOAD_URL", "\"$r2UploadUrl\"")
    }

    signingConfigs {
        // Created only when a keystore is provided (CI or local.properties).
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
            // null without a keystore: the APK is unsigned (installable only when signed).
            signingConfig = signingConfigs.findByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // With built-in Kotlin in AGP 9, kotlinOptions lives in the top-level kotlin {}
    // block and jvmTarget follows the targetCompatibility above.
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.core:core-ktx:1.19.1")

    implementation(platform("com.google.firebase:firebase-bom:35.2.0"))
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-firestore")
    implementation("com.google.firebase:firebase-messaging")
    implementation("com.google.firebase:firebase-crashlytics")
    implementation("com.google.firebase:firebase-analytics")

    // Maps use osmdroid (OpenStreetMap) natively: pan/zoom/markers, no API key.
    implementation("org.osmdroid:osmdroid-android:6.1.18")
    implementation("com.facebook.android:facebook-login:16.3.0")

    testImplementation("junit:junit:4.13.2")

    androidTestImplementation(platform("androidx.compose:compose-bom:2026.09.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
}
