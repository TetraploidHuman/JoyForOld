plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    alias(libs.plugins.kotlin.serialization)
}

import java.util.Properties

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) {
        file.inputStream().use { load(it) }
    }
}

val llmBaseUrl = localProperties.getProperty("llm.base_url")
    ?: "https://ark.cn-beijing.volces.com/api/v3/responses"
val usesVolcResponses = llmBaseUrl.contains("/api/v3/responses", ignoreCase = true)

val llmApiKey = localProperties.getProperty("llm.api.key")
    ?: localProperties.getProperty("volc.llm.api.key")
    ?: if (!usesVolcResponses) {
        localProperties.getProperty("zhipu.api.key")
            ?: localProperties.getProperty("deepseek.api.key", "")
    } else {
        ""
    }

val llmModel = localProperties.getProperty("llm.model")
    ?: localProperties.getProperty("volc.llm.model")
    ?: if (usesVolcResponses) {
        "doubao-seed-2-0-mini-260428"
    } else {
        localProperties.getProperty("zhipu.model", "glm-4.6v-flash")
    }

val assistServerHttp = localProperties.getProperty("assist.server.url", "http://10.0.2.2:8787")
val assistServerWs = localProperties.getProperty("assist.server.ws", "ws://10.0.2.2:8787/ws")

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
            "LLM_API_KEY",
            "\"$llmApiKey\"",
        )
        buildConfigField(
            "String",
            "LLM_MODEL",
            "\"$llmModel\"",
        )
        buildConfigField(
            "String",
            "LLM_BASE_URL",
            "\"$llmBaseUrl\"",
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
        buildConfigField(
            "String",
            "ASSIST_SERVER_URL",
            "\"$assistServerHttp\"",
        )
        buildConfigField(
            "String",
            "ASSIST_SERVER_WS",
            "\"$assistServerWs\"",
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
    implementation(project(":assist-protocol"))
    implementation(libs.kotlinx.serialization.json)
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.apache.commons:commons-compress:1.27.1")
    implementation("com.belerweb:pinyin4j:2.5.1")
    implementation("androidx.activity:activity-compose:1.12.4")
    implementation(platform("androidx.compose:compose-bom:2026.06.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.foundation:foundation")
    implementation("com.xdcobra.sherpa:sherpa-onnx:1.13.2-1")
    implementation(project(":core:common"))
    implementation(libs.koin.android)
    implementation(libs.koin.androidx.compose)
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation(libs.koin.test)
    testImplementation(libs.koin.test.junit4)
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}