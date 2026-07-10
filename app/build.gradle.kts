plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

import java.util.Properties

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) {
        file.inputStream().use { load(it) }
    }
}

android {
    namespace = "com.tetraploid.joyforold"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.tetraploid.joyforold"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField(
            "String",
            "DEEPSEEK_API_KEY",
            "\"${localProperties.getProperty("deepseek.api.key", "")}\"",
        )
        buildConfigField(
            "String",
            "DEEPSEEK_MODEL",
            "\"${localProperties.getProperty("deepseek.model", "deepseek-v4-flash")}\"",
        )
        buildConfigField(
            "String",
            "VOLC_ASR_API_KEY",
            "\"${localProperties.getProperty("volc.asr.api_key", "")}\"",
        )
        buildConfigField(
            "String",
            "VOLC_ASR_APP_ID",
            "\"${localProperties.getProperty("volc.asr.app_id", "")}\"",
        )
        buildConfigField(
            "String",
            "VOLC_ASR_ACCESS_TOKEN",
            "\"${localProperties.getProperty("volc.asr.access_token", "")}\"",
        )
        buildConfigField(
            "String",
            "VOLC_ASR_RESOURCE_ID",
            "\"${localProperties.getProperty("volc.asr.resource_id", "volc.bigasr.sauc.duration")}\"",
        )
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.belerweb:pinyin4j:2.5.1")
    implementation("androidx.activity:activity-compose:1.12.4")
    implementation(platform("androidx.compose:compose-bom:2026.06.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("com.xdcobra.sherpa:sherpa-onnx:1.13.2-1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}