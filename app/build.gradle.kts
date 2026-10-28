plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.example.aicalendar"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.aicalendar"
        minSdk = 26
        targetSdk = 36
        versionCode = 31
        versionName = "0.19.0"
    }

    buildFeatures {
        compose = true
    }

    kotlin {
        jvmToolchain(17)
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.06.01"))
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.work:work-runtime-ktx:2.11.2")
    implementation(platform("com.google.firebase:firebase-bom:34.17.0"))
    implementation("com.google.firebase:firebase-messaging")
    implementation("com.google.firebase:firebase-installations")
    testImplementation("junit:junit:4.13.2")
    // Real org.json for JVM unit tests (android.jar only ships stubs).
    testImplementation("org.json:json:20240303")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
