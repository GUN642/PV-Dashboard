plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// In GitHub Actions zählt die Build-Nummer hoch, damit jede APK als Update installierbar ist.
val buildNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1

fun envOrNull(name: String): String? = System.getenv(name)?.takeIf { it.isNotBlank() }

android {
    namespace = "de.gun642.pvdashboard"
    compileSdk = 35

    defaultConfig {
        applicationId = "de.gun642.pvdashboard"
        minSdk = 26
        targetSdk = 35
        versionCode = buildNumber
        versionName = "0.2.$buildNumber"
    }

    signingConfigs {
        // Standard: der Schlüssel im Repo. Optional per GitHub-Secrets ersetzbar (siehe README).
        create("release") {
            storeFile = envOrNull("SIGNING_KEYSTORE_FILE")?.let { file(it) } ?: file("signing/pv-dashboard.jks")
            storePassword = envOrNull("SIGNING_STORE_PASSWORD") ?: "pvdashboard"
            keyAlias = envOrNull("SIGNING_KEY_ALIAS") ?: "pvdashboard"
            keyPassword = envOrNull("SIGNING_KEY_PASSWORD") ?: "pvdashboard"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
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
        compose = true
        buildConfig = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")

    testImplementation("junit:junit:4.13.2")
    // Echte org.json-Implementierung für Unit-Tests (Android stellt sie nur zur Laufzeit bereit)
    testImplementation("org.json:json:20240303")
}
