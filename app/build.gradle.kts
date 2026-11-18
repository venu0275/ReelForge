import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Values that can be overridden in local.properties (never committed), e.g. "support.email=...".
val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

android {
    namespace = "com.reelforge.editor"
    compileSdk = 36

    defaultConfig {
        // The Play Store package name. It can never change once the app is published.
        applicationId = "com.reelforge.editor"
        minSdk = 29
        targetSdk = 36
        versionCode = 4
        versionName = "1.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "SUPPORT_EMAIL", "\"${localProps.getProperty("support.email", "developervt0909@gmail.com").trim()}\"")
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
        compose = true
        buildConfig = true
    }
}

dependencies {
    val media3 = "1.4.1"
    implementation("androidx.media3:media3-common:$media3")
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-extractor:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    implementation("androidx.media3:media3-effect:$media3")
    implementation("androidx.media3:media3-transformer:$media3")

    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")

    implementation("androidx.annotation:annotation-experimental:1.4.1")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Auto captions: offline speech recognition (Apache 2.0). Speech models are downloaded on first use.
    // 0.3.70 and JNA 5.17 are the first with 16 KB page support, which Google Play requires.
    implementation("com.alphacephei:vosk-android:0.3.70@aar")
    implementation("net.java.dev.jna:jna:5.17.0@aar")
    // Remove background: runs Google's selfie segmentation model on the phone (Apache 2.0).
    // The plain LiteRT runtime is used, not MediaPipe Tasks, which would send usage data to Google.
    implementation("com.google.ai.edge.litert:litert:1.4.0")
    // "Update available" check: asks the Play Store app on the phone whether a newer version is
    // published, and installs it from inside the app. Free Google Play library; sends nothing of ours.
    implementation("com.google.android.play:app-update-ktx:2.1.0")

    // Unit tests (run on the build machine).
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")

    // Device tests (run on an emulator or phone).
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.10.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
