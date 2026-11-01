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
        // Kein Schlüssel im Repository: signiert wird nur mit dem geheimen Schlüssel aus den GitHub-Secrets
        // (der Workflow legt ihn als Datei ab und setzt die Umgebungsvariablen, siehe README).
        envOrNull("SIGNING_KEYSTORE_FILE")?.let { path ->
            create("release") {
                storeFile = file(path)
                storePassword = envOrNull("SIGNING_STORE_PASSWORD") ?: error("SIGNING_STORE_PASSWORD fehlt")
                keyAlias = envOrNull("SIGNING_KEY_ALIAS") ?: error("SIGNING_KEY_ALIAS fehlt")
                keyPassword = envOrNull("SIGNING_KEY_PASSWORD") ?: envOrNull("SIGNING_STORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
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
    // Hintergrundprüfungen (Überschuss-Hinweis, Vertragserinnerungen)
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    testImplementation("junit:junit:4.13.2")
    // Echte org.json-Implementierung für Unit-Tests (Android stellt sie nur zur Laufzeit bereit)
    testImplementation("org.json:json:20240303")
}
