import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties
import java.io.FileInputStream

plugins {
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.android.application)
}

val keystoreProperties = Properties()
val keystorePropertiesFile = rootProject.file("keystore.properties")

if (keystorePropertiesFile.exists()) {
    FileInputStream(keystorePropertiesFile).use { input ->
        keystoreProperties.load(input)
    }
}

val hasReleaseKeystore =
    keystorePropertiesFile.exists() &&
        listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
            .all { key -> !keystoreProperties.getProperty(key).isNullOrBlank() }
val olcboxVersion = providers.gradleProperty("olcbox.version").orElse("1.0.0")
val olcboxVersionCode = providers.gradleProperty("olcbox.versionCode")
    .map { it.toInt() }
    .orElse(1)
val defaultAndroidAbiFilters = listOf("armeabi-v7a", "arm64-v8a", "x86_64")
val androidAbiFilters = providers.gradleProperty("olcbox.android.abiFilters")
    .map { value ->
        value.split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }
    .getOrElse(defaultAndroidAbiFilters)

require(androidAbiFilters.isNotEmpty()) {
    "olcbox.android.abiFilters must contain at least one Android ABI"
}

android {
    namespace = "org.olcbox.app"
    compileSdk = 37
    ndkVersion = "28.2.13676358"

    defaultConfig {
        minSdk = 23
        targetSdk = 37

        applicationId = "org.olcbox.app"
        versionCode = olcboxVersionCode.get()
        versionName = olcboxVersion.get()

        ndk {
            abiFilters += androidAbiFilters
        }
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            if (hasReleaseKeystore) {
                signingConfig = signingConfigs.getByName("release")
            }

            isMinifyEnabled = false
            isShrinkResources = false
        }

        release {
            if (hasReleaseKeystore) {
                signingConfig = signingConfigs.getByName("release")
            }

            isMinifyEnabled = true
            isShrinkResources = true

            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("src/main/jniLibs", "jniLibs")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    externalNativeBuild {
        ndkBuild {
            path = file("src/main/jni/Android.mk")
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

// In AGP 9.0+ Kotlin settings for Android are configured like this:
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":sharedUI"))
    implementation(libs.androidx.activityCompose)
    implementation(libs.androidx.datastore.preferences)
}

// --- Unified-client core binaries (arm64-v8a) ------------------------------
// sing-box + Xray are exec'd from nativeLibraryDir (v2rayNG pattern): packaged
// as lib*.so in jniLibs, extracted by useLegacyPackaging. Only arm64-v8a is
// bundled (Xray publishes no armv7 android binary; armv7 devices keep
// reality/hy2/olcrtc). Downloaded at build time from the pinned releases.
abstract class DownloadCoreTask : DefaultTask() {
    @get:Input abstract val sourceUrl: Property<String>
    @get:OutputFile abstract val outputFile: RegularFileProperty
    @TaskAction fun run() {
        val out = outputFile.get().asFile
        out.parentFile.mkdirs()
        java.net.URI(sourceUrl.get()).toURL().openStream().use { i ->
            out.outputStream().use { o -> i.copyTo(o) }
        }
    }
}

val singboxCoreVersion = "1.11.15"
val xrayCoreVersion = "25.3.6"
val coreJniDir = layout.projectDirectory.dir("jniLibs/arm64-v8a")

val downloadSingboxArm64 by tasks.registering(DownloadCoreTask::class) {
    sourceUrl.set("https://github.com/SagerNet/sing-box/releases/download/v$singboxCoreVersion/sing-box-$singboxCoreVersion-android-arm64.tar.gz")
    outputFile.set(layout.buildDirectory.file("cores/singbox-android-arm64.tar.gz"))
}
val placeSingboxCore by tasks.registering(Copy::class) {
    dependsOn(downloadSingboxArm64)
    from({ tarTree(downloadSingboxArm64.get().outputFile.asFile) }) {
        include("**/sing-box")
        eachFile { path = "libsingboxcore.so" }
    }
    into(coreJniDir)
    includeEmptyDirs = false
}

val downloadXrayArm64 by tasks.registering(DownloadCoreTask::class) {
    sourceUrl.set("https://github.com/XTLS/Xray-core/releases/download/v$xrayCoreVersion/Xray-android-arm64-v8a.zip")
    outputFile.set(layout.buildDirectory.file("cores/xray-android-arm64.zip"))
}
val placeXrayCore by tasks.registering(Copy::class) {
    dependsOn(downloadXrayArm64)
    from({ zipTree(downloadXrayArm64.get().outputFile.asFile) }) {
        include("xray")
        eachFile { path = "libxraycore.so" }
    }
    into(coreJniDir)
    includeEmptyDirs = false
}

tasks.named("preBuild") { dependsOn(placeSingboxCore, placeXrayCore) }
