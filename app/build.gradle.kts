import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    id("com.google.gms.google-services")
}

// Réglages privés (non versionnés) : local.properties ou variables d'environnement.
val localProps = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
fun privateSetting(key: String): String =
    localProps.getProperty(key) ?: System.getenv(key.uppercase().replace('.', '_')) ?: ""

android {
    namespace = "com.siwel.siwel"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.siwel.siwel"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        buildConfigField("String", "TURN_HOST", "\"${privateSetting("siwel.turn.host")}\"")
        buildConfigField("String", "TURN_USER", "\"${privateSetting("siwel.turn.user")}\"")
        buildConfigField("String", "TURN_PASS", "\"${privateSetting("siwel.turn.pass")}\"")
        buildConfigField("String", "FIREBASE_DATABASE_URL", "\"${privateSetting("siwel.firebase.url")}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    // Firebase
    implementation(platform("com.google.firebase:firebase-bom:33.1.0"))
    implementation("com.google.firebase:firebase-database-ktx")
    implementation("com.google.firebase:firebase-auth-ktx")
    implementation("com.google.firebase:firebase-messaging-ktx")

    // Security (session chiffrée)
    implementation("androidx.security:security-crypto:1.1.0")

    // WebRTC
    implementation("io.getstream:stream-webrtc-android:1.1.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // ViewModel + LiveData
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.7.0")
    implementation("androidx.activity:activity-ktx:1.9.0")

    // Standard AndroidX
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.7.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
}