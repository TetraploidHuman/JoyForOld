import java.io.File
import java.util.Properties

pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // Prefer locally built reduced-ops ORT AAR over Maven Central.
        exclusiveContent {
            forRepository {
                maven(url = uri("${rootDir}/tools/ort-custom/maven"))
            }
            filter {
                includeGroup("com.microsoft.onnxruntime")
            }
        }
        google()
        mavenCentral()
        maven("https://jitpack.io")
        maven("https://xdcobra.github.io/maven/")
    }
}

fun detectAndroidSdkDir(): File? {
    sequenceOf("ANDROID_SDK_ROOT", "ANDROID_HOME")
        .mapNotNull { System.getenv(it)?.takeIf(String::isNotBlank) }
        .map(::File)
        .firstOrNull { it.isDirectory }
        ?.let { return it }

    val home = System.getProperty("user.home").orEmpty()
    val os = System.getProperty("os.name").lowercase()
    val candidates = when {
        os.contains("win") -> listOf("$home\\AppData\\Local\\Android\\Sdk")
        os.contains("mac") -> listOf("$home/Library/Android/sdk")
        else -> listOf("$home/Android/Sdk", "$home/.android/sdk")
    }
    return candidates.map(::File).firstOrNull { it.isDirectory }
}

fun ensureLocalSdkDir(rootDir: File) {
    val localFile = File(rootDir, "local.properties")
    val props = Properties()
    if (localFile.exists()) {
        localFile.inputStream().use { props.load(it) }
    }
    if (!props.getProperty("sdk.dir").isNullOrBlank()) return

    val sdkDir = detectAndroidSdkDir() ?: run {
        logger.warn(
            "未找到 Android SDK：请设置 ANDROID_SDK_ROOT / ANDROID_HOME，" +
                "或在 local.properties 中手动填写 sdk.dir",
        )
        return
    }

    val escaped = sdkDir.absolutePath.replace("\\", "\\\\")
    if (!localFile.exists()) {
        localFile.writeText("sdk.dir=$escaped\n")
        return
    }

    val text = localFile.readText()
    if (text.lineSequence().any { it.trimStart().startsWith("sdk.dir=") }) return
    val prefix = if (text.endsWith("\n")) "" else "\n"
    localFile.appendText("${prefix}sdk.dir=$escaped\n")
}

ensureLocalSdkDir(settings.rootDir)

rootProject.name = "JoyForOld"
include(":app")
include(":core:common")
include(":assist-protocol")
include(":assist-server")
include(":uitreetest")
