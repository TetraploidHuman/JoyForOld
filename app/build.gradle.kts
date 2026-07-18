import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

fun loadLocalProperties(rootDir: File): Properties {
    val properties = Properties()
    val file = rootDir.resolve("local.properties")
    if (!file.exists()) return properties
    file.inputStream().use { inputStream ->
        properties.load(inputStream)
    }
    return properties
}

fun Properties.propertyOrEmpty(key: String): String = getProperty(key)?.trim().orEmpty()

val localProperties = loadLocalProperties(rootProject.projectDir)

val llmBaseUrl = localProperties.getProperty("llm.base_url")
    ?: "https://ark.cn-beijing.volces.com/api/v3/responses"
val usesArkResponsesApi = llmBaseUrl.contains("/api/v3/responses", ignoreCase = true)

val llmApiKey = localProperties.getProperty("llm.api.key")
    ?: localProperties.getProperty("volc.llm.api.key")
    ?: if (!usesArkResponsesApi) {
        localProperties.getProperty("zhipu.api.key")
            ?: localProperties.getProperty("deepseek.api.key", "")
    } else {
        ""
    }

val llmModel = localProperties.getProperty("llm.model")
    ?: localProperties.getProperty("volc.llm.model")
    ?: if (usesArkResponsesApi) {
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

        buildConfigField("String", "LLM_API_KEY", "\"$llmApiKey\"")
        buildConfigField("String", "LLM_MODEL", "\"$llmModel\"")
        buildConfigField("String", "LLM_BASE_URL", "\"$llmBaseUrl\"")
        buildConfigField(
            "String",
            "VOLC_ASR_API_KEY",
            "\"${localProperties.propertyOrEmpty("volc.asr.api_key")}\"",
        )
        buildConfigField(
            "String",
            "VOLC_ASR_APP_ID",
            "\"${localProperties.propertyOrEmpty("volc.asr.app_id")}\"",
        )
        buildConfigField(
            "String",
            "VOLC_ASR_ACCESS_TOKEN",
            "\"${localProperties.propertyOrEmpty("volc.asr.access_token")}\"",
        )
        buildConfigField(
            "String",
            "VOLC_ASR_RESOURCE_ID",
            "\"${localProperties.getProperty("volc.asr.resource_id", "volc.bigasr.sauc.duration")}\"",
        )
        buildConfigField("String", "ASSIST_SERVER_URL", "\"$assistServerHttp\"")
        buildConfigField("String", "ASSIST_SERVER_WS", "\"$assistServerWs\"")
        // 高德 Web 服务 Key：用于地点检索→坐标→androidamap://navi 直达导航（跳过候选列表）
        buildConfigField(
            "String",
            "AMAP_WEB_KEY",
            "\"${localProperties.propertyOrEmpty("amap.web.key")}\"",
        )
    }

    buildTypes {
        release {
            // 体验分发：无正式 keystore 时用 debug 签名，便于他人直接安装
            signingConfig = signingConfigs.getByName("debug")
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
    implementation(project(":core:common"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.core.ktx)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.websockets)
    implementation(libs.commons.compress)
    implementation(libs.pinyin4j)
    implementation(libs.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.ui)
    implementation(libs.ui.graphics)
    implementation(libs.ui.tooling.preview)
    implementation(libs.material3)
    implementation(libs.material.icons.extended)
    implementation(libs.foundation)
    implementation(libs.sherpa.onnx)
    implementation(libs.koin.android)
    implementation(libs.koin.androidx.compose)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.core)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.koin.test)
    testImplementation(libs.koin.test.junit4)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.ui.test.junit4)
    debugImplementation(libs.ui.tooling)
    debugImplementation(libs.ui.test.manifest)
}
