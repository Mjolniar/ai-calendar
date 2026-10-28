// Headless UI harness for the phone app.
//
// It compiles the app's REAL sources and resources from ../app/src/main, leaves out the files
// that need Firebase, WorkManager, the network or the Keystore, and puts the stand-ins from
// src/stubs in their place. Robolectric runs the real Compose screens on the JVM and Roborazzi
// saves pictures of them, so the XP look and the UI behavior can be checked without a phone.
//
// Nothing here is ever installed. The application id differs from the app's on purpose: if a build
// from this folder did reach a phone it would sit next to the real app instead of replacing it
// with one that cannot sync.
//
// Keep the plugin and library versions below in step with ../build.gradle.kts and
// ../app/build.gradle.kts.
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("com.android.application") version "8.11.0"
    id("org.jetbrains.kotlin.android") version "2.1.20"
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.20"
}

val appMain = "../app/src/main"

// Real files replaced by src/stubs/java/com/example/aicalendar/Stubs.kt. A new app file that imports
// Firebase, WorkManager or java.net needs a line here and a stand-in there.
val stubbedOut = listOf(
    "BridgeApi.kt",
    "BridgeSyncWorker.kt",
    "CalendarMessagingService.kt",
    "EventImportParser.kt",
    "FirebaseBootstrap.kt",
    "PendingChanges.kt",
    "RemoteEventParser.kt",
    "SecureTokenStore.kt",
)

android {
    namespace = "com.example.aicalendar"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.aicalendar.uiharness"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "harness"
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets["main"].apply {
        // The app's manifest without the Firebase service and the xml configs.
        manifest.srcFile("src/harness/AndroidManifest.xml")
        java.setSrcDirs(listOf("$appMain/java", "src/stubs/java"))
        res.setSrcDirs(listOf("$appMain/res"))
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                // Roborazzi writes its pictures instead of comparing them with stored ones.
                it.systemProperty("roborazzi.test.record", "true")
                it.maxHeapSize = "2g"
                // Robolectric reads its Android runtime jar back through an undecoded file URL, so
                // native graphics fail when that jar sits under a folder with a space in its name
                // (%USERPROFILE%\.m2 on this PC). run.ps1 copies the jar to a plain folder; use it.
                val plainDeps = file("../build/robolectric-deps")
                if (plainDeps.isDirectory && (plainDeps.listFiles()?.any { file -> file.name.endsWith(".jar") } == true)) {
                    it.systemProperty("robolectric.offline", "true")
                    it.systemProperty("robolectric.dependency.dir", plainDeps.absolutePath)
                }
            }
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

tasks.withType<KotlinCompile>().configureEach {
    exclude { element -> element.file.name in stubbedOut }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.06.01"))
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.core:core-ktx:1.17.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.50.0")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.50.0")
    testImplementation(platform("androidx.compose:compose-bom:2026.06.01"))
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
