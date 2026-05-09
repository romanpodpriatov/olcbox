import org.gradle.internal.os.OperatingSystem
import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    implementation(project(":sharedUI"))
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.lifecycle.runtime)
}

val defaultOlcRtcRepo = rootProject.layout.projectDirectory.asFile.parentFile
    .resolve("olcrtc-original")
    .absolutePath
val olcrtcRepo = providers.environmentVariable("OLCRTC_REPO")
    .orElse(defaultOlcRtcRepo)
val generatedNativeResources = layout.buildDirectory.dir("generated/desktopNativeResources")
val hevSocks5TunnelSourceDir = rootProject.layout.projectDirectory.dir("androidApp/src/main/jni/hev-socks5-tunnel")
val currentBuildOs = OperatingSystem.current()
val desktopPackageName = "Olcbox"
val desktopPackageVersion = "1.0.0"
val currentBuildTargetFormats = when {
    currentBuildOs.isMacOsX -> arrayOf(TargetFormat.Dmg)
    currentBuildOs.isWindows -> arrayOf(TargetFormat.Exe, TargetFormat.Msi)
    currentBuildOs.isLinux -> arrayOf(TargetFormat.AppImage)
    else -> emptyArray()
}

fun desktopArchName(arch: String): String = when (arch.lowercase()) {
    "x86_64", "amd64" -> "amd64"
    "aarch64", "arm64" -> "arm64"
    else -> error("Unsupported desktop architecture: $arch")
}

fun shellQuote(value: String): String = "'${value.replace("'", "'\"'\"'")}'"

val hostDesktopArch = desktopArchName(System.getProperty("os.arch"))

fun registerOlcRtcBuildTask(
    taskName: String,
    goos: String,
    goarch: String,
    outputName: String
) = tasks.register<Exec>(taskName) {
    val outputFile = generatedNativeResources.map { it.file("native/$outputName") }

    outputs.file(outputFile)
    workingDir = file(olcrtcRepo.get())
    environment("GOOS", goos)
    environment("GOARCH", goarch)
    environment("CGO_ENABLED", "0")
    commandLine(
        "go",
        "build",
        "-trimpath",
        "-ldflags",
        "-s -w",
        "-o",
        outputFile.get().asFile.absolutePath,
        "./cmd/olcrtc"
    )

    doFirst {
        outputFile.get().asFile.parentFile.mkdirs()
    }
}

val buildOlcRtcDarwinArm64 = registerOlcRtcBuildTask(
    taskName = "buildOlcRtcDarwinArm64",
    goos = "darwin",
    goarch = "arm64",
    outputName = "olcrtc-darwin-arm64"
)

val buildOlcRtcDarwinAmd64 = registerOlcRtcBuildTask(
    taskName = "buildOlcRtcDarwinAmd64",
    goos = "darwin",
    goarch = "amd64",
    outputName = "olcrtc-darwin-amd64"
)

val buildOlcRtcWindowsAmd64 = registerOlcRtcBuildTask(
    taskName = "buildOlcRtcWindowsAmd64",
    goos = "windows",
    goarch = "amd64",
    outputName = "olcrtc-windows-amd64.exe"
)

val buildOlcRtcLinuxAmd64 = registerOlcRtcBuildTask(
    taskName = "buildOlcRtcLinuxAmd64",
    goos = "linux",
    goarch = "amd64",
    outputName = "olcrtc-linux-amd64"
)

val buildOlcRtcLinuxArm64 = registerOlcRtcBuildTask(
    taskName = "buildOlcRtcLinuxArm64",
    goos = "linux",
    goarch = "arm64",
    outputName = "olcrtc-linux-arm64"
)

val copyOlcRtcDataAssets = tasks.register<Copy>("copyOlcRtcDataAssets") {
    from(olcrtcRepo.map { file(it).resolve("data") }) {
        include("names", "surnames")
    }
    into(generatedNativeResources.map { it.dir("olcrtc-data") })
}

val desktopNativeAssetTasks = mutableListOf<Any>(
    buildOlcRtcDarwinArm64,
    buildOlcRtcDarwinAmd64,
    buildOlcRtcWindowsAmd64,
    buildOlcRtcLinuxAmd64,
    buildOlcRtcLinuxArm64,
    copyOlcRtcDataAssets
)
val hostDesktopNativeAssetTasks = mutableListOf<Any>(
    copyOlcRtcDataAssets
)

when {
    currentBuildOs.isMacOsX -> when (hostDesktopArch) {
        "amd64" -> hostDesktopNativeAssetTasks.add(buildOlcRtcDarwinAmd64)
        "arm64" -> hostDesktopNativeAssetTasks.add(buildOlcRtcDarwinArm64)
    }
    currentBuildOs.isWindows -> hostDesktopNativeAssetTasks.add(buildOlcRtcWindowsAmd64)
    currentBuildOs.isLinux -> when (hostDesktopArch) {
        "amd64" -> hostDesktopNativeAssetTasks.add(buildOlcRtcLinuxAmd64)
        "arm64" -> hostDesktopNativeAssetTasks.add(buildOlcRtcLinuxArm64)
    }
}

if (currentBuildOs.isLinux) {
    val buildHevSocks5TunnelLinux = tasks.register<Exec>("buildHevSocks5TunnelLinux") {
        val outputFile = generatedNativeResources.map {
            it.file("native/hev-socks5-tunnel-linux-$hostDesktopArch")
        }
        val output = outputFile.get().asFile

        outputs.file(outputFile)
        workingDir = hevSocks5TunnelSourceDir.asFile
        commandLine(
            "sh",
            "-c",
            "mkdir -p ${shellQuote(output.parentFile.absolutePath)} && make clean exec && install -m 0755 bin/hev-socks5-tunnel ${shellQuote(output.absolutePath)}"
        )
    }
    desktopNativeAssetTasks.add(buildHevSocks5TunnelLinux)
    hostDesktopNativeAssetTasks.add(buildHevSocks5TunnelLinux)
}

tasks.register("buildDesktopNativeAssets") {
    dependsOn(desktopNativeAssetTasks)
}

sourceSets {
    main {
        resources.srcDir(generatedNativeResources)
    }
}

tasks.named("processResources") {
    dependsOn(hostDesktopNativeAssetTasks)
}

compose.desktop {
    application {
        mainClass = "MainKt"

        buildTypes.release.proguard {
            isEnabled.set(false)
        }

        nativeDistributions {
            modules("jdk.httpserver")
            targetFormats(*currentBuildTargetFormats)
            packageName = desktopPackageName
            packageVersion = desktopPackageVersion

            linux {
                iconFile.set(project.file("appIcons/LinuxIcon.png"))
            }
            windows {
                iconFile.set(project.file("appIcons/WindowsIcon.ico"))
            }
            macOS {
                iconFile.set(project.file("appIcons/MacosIcon.icns"))
                bundleID = "org.olcbox.app.desktopApp"
            }
        }
    }
}

if (currentBuildOs.isLinux) {
    val appImageTool = providers.environmentVariable("APPIMAGETOOL").orElse("appimagetool")
    val jpackageAppDir = layout.buildDirectory.dir("compose/binaries/main-release/app/$desktopPackageName")
    val appDir = layout.buildDirectory.dir("compose/binaries/main-release/appimage/AppDir")
    val linuxIconFile = layout.projectDirectory.file("appIcons/LinuxIcon.png")
    val appImageFile = layout.buildDirectory.file(
        "compose/binaries/main-release/appimage/$desktopPackageName-$desktopPackageVersion-$hostDesktopArch.AppImage"
    )

    val prepareReleaseLinuxAppDir = tasks.register<Exec>("prepareReleaseLinuxAppDir") {
        group = "distribution"
        description = "Prepares the AppDir layout used by appimagetool."

        dependsOn("packageReleaseAppImage")
        inputs.dir(jpackageAppDir)
        inputs.file(linuxIconFile)
        outputs.dir(appDir)

        commandLine(
            "sh",
            "-c",
            """
            set -eu

            source_dir="${'$'}1"
            target_dir="${'$'}2"
            icon_file="${'$'}3"

            rm -rf "${'$'}target_dir"
            mkdir -p "${'$'}target_dir"
            cp -R "${'$'}source_dir/." "${'$'}target_dir/"

            cat > "${'$'}target_dir/AppRun" <<'APPRUN'
            #!/bin/sh
            HERE="${'$'}(dirname "${'$'}(readlink -f "${'$'}0")")"
            exec "${'$'}HERE/bin/$desktopPackageName" "${'$'}@"
            APPRUN
            chmod +x "${'$'}target_dir/AppRun"

            cat > "${'$'}target_dir/org.olcbox.app.desktopApp.desktop" <<'DESKTOP'
            [Desktop Entry]
            Type=Application
            Name=$desktopPackageName
            Exec=$desktopPackageName
            Icon=olcbox
            Categories=Network;Utility;
            Terminal=false
            DESKTOP

            cp "${'$'}icon_file" "${'$'}target_dir/olcbox.png"
            """.trimIndent(),
            "prepareReleaseLinuxAppDir",
            jpackageAppDir.get().asFile.absolutePath,
            appDir.get().asFile.absolutePath,
            linuxIconFile.asFile.absolutePath
        )
    }

    val packageReleaseLinuxAppImage = tasks.register<Exec>("packageReleaseLinuxAppImage") {
        group = "distribution"
        description = "Packages the Linux desktop app as a real .AppImage file."

        dependsOn(prepareReleaseLinuxAppDir)
        inputs.dir(appDir)
        outputs.file(appImageFile)

        commandLine(
            appImageTool.get(),
            appDir.get().asFile.absolutePath,
            appImageFile.get().asFile.absolutePath
        )
    }

    tasks.matching { it.name == "packageReleaseDistributionForCurrentOS" }.configureEach {
        dependsOn(packageReleaseLinuxAppImage)
    }
}
