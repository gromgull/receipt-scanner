import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Release signing: read from keystore.properties (local, git-ignored) or environment variables (CI).
// Without either, release builds are left unsigned and debug builds are unaffected.
val keystoreProps = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
fun signingValue(property: String, env: String): String? =
    keystoreProps.getProperty(property) ?: System.getenv(env)?.takeIf { it.isNotEmpty() }

android {
    namespace = "app.receiptscanner"
    compileSdk = 35

    defaultConfig {
        // Must match the package name of the Android OAuth client in Google Cloud (see README).
        applicationId = "app.receiptscanner"
        minSdk = 26
        targetSdk = 35
        // Set by CI (run number / tag); local builds get a fixed dev version.
        versionCode = System.getenv("VERSION_CODE")?.toIntOrNull() ?: 1
        versionName = System.getenv("VERSION_NAME") ?: "0.0.0-local"
    }

    val releaseSigning = signingValue("storeFile", "ANDROID_KEYSTORE_PATH")?.let { path ->
        signingConfigs.create("release") {
            storeFile = rootProject.file(path)
            storePassword = signingValue("storePassword", "ANDROID_KEYSTORE_PASSWORD")
            keyAlias = signingValue("keyAlias", "ANDROID_KEY_ALIAS")
            // PKCS12 keystores (keytool's default) use the store password for the key too.
            keyPassword = signingValue("keyPassword", "ANDROID_KEY_PASSWORD") ?: storePassword
        }
    }

    buildTypes {
        release {
            signingConfig = releaseSigning
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.datastore.preferences)

    // On-device ML: document scanner (crop/deskew) + OCR
    implementation(libs.mlkit.document.scanner)
    implementation(libs.mlkit.text.recognition)

    // Google Drive auth (AuthorizationClient) + REST
    implementation(libs.play.services.auth)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.okhttp)

    implementation(libs.coil.compose)

    testImplementation(libs.junit)
}
