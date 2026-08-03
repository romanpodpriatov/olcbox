import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.internal.os.OperatingSystem
import org.gradle.api.tasks.bundling.Zip
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import java.net.URI
import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    implementation(project(":sharedUI"))
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.jna)
    implementation(libs.zxing.core)
}

abstract class DownloadFileTask : DefaultTask() {
    @get:Input
    abstract val sourceUrl: Property<String>

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @TaskAction
    fun download() {
        val output = outputFile.get().asFile
        output.parentFile.mkdirs()
        URI(sourceUrl.get())
            .toURL()
            .openStream()
            .use { input ->
                output.outputStream().use { outputStream ->
                    input.copyTo(outputStream)
                }
            }
    }
}

abstract class ExtractZipEntryTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val zipFile: RegularFileProperty

    @get:Input
    abstract val entrySuffix: Property<String>

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @TaskAction
    fun extract() {
        val zip = zipFile.get().asFile
        val output = outputFile.get().asFile
        output.parentFile.mkdirs()

        ZipFile(zip).use { archive ->
            val entry = archive.entries().asSequence()
                .firstOrNull { it.name.endsWith(entrySuffix.get()) }
                ?: error("${entrySuffix.get()} entry was not found in ${zip.absolutePath}")

            archive.getInputStream(entry).use { input ->
                output.outputStream().use { outputStream ->
                    input.copyTo(outputStream)
                }
            }
        }
    }
}

abstract class VerifyNativeResourcesTask : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val resourcesDir: DirectoryProperty

    @get:Input
    abstract val requiredPaths: ListProperty<String>

    @TaskAction
    fun verify() {
        val root = resourcesDir.get().asFile
        val missing = requiredPaths.get()
            .map { root.resolve(it) }
            .filterNot { it.isFile }

        require(missing.isEmpty()) {
            "Missing desktop native resources:\n" +
                    missing.joinToString(separator = "\n") { "- ${it.relativeTo(root).invariantSeparatorsPath}" }
        }
    }
}

val defaultOlcRtcRepo = rootProject.layout.projectDirectory.asFile.parentFile
    .resolve("olcrtc")
    .absolutePath
val olcrtcRepo = providers.environmentVariable("OLCRTC_REPO")
    .orElse(defaultOlcRtcRepo)
val olcrtcRepoDir = olcrtcRepo.map { rootProject.file(it) }
val generatedNativeResources = layout.buildDirectory.dir("generated/desktopNativeResources")
val hevSocks5TunnelSourceDir = rootProject.layout.projectDirectory.dir("androidApp/src/main/jni/hev-socks5-tunnel")
val currentBuildOs = OperatingSystem.current()
// Installed application name (ProofKit.app / ProofKit.exe / ProofKit.AppImage).
// NOT an identity: macOS bundleID and the Windows upgradeUuid below are unchanged,
// so Windows still upgrades in place. DesktopPaths.appDataDir() keeps its own
// hardcoded "Olcbox" directory so existing installs keep their subscriptions.
val desktopPackageName = "ProofKit"
val desktopPackageVersion = providers.gradleProperty("olcbox.version").orElse("1.0.0").get()
val tun2SocksVersion = "2.6.0"
val wintunVersion = "0.14.1"
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
    workingDir = olcrtcRepoDir.get()
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

    // Notarisation rejected the build over exactly this kind of file: the olcrtc
    // engine is produced here, long after CI has signed sing-box and xray, and it
    // travels inside the app's jar. Apple scans in there and wants a Developer ID
    // signature, a secure timestamp and the hardened runtime on every Mach-O.
    //
    // Signed at execution time with plain ProcessBuilder and System.getenv, and
    // with no reference to `project` — a task action that touches the project
    // breaks the configuration cache this build relies on.
    val signTarget = outputFile.map { it.asFile.absolutePath }
    val entitlementsPath = layout.projectDirectory.file("macos-entitlements.plist").asFile.absolutePath
    val signOnDarwin = goos == "darwin"
    doLast {
        val identity = System.getenv("MACOS_SIGN_IDENTITY")
        if (signOnDarwin && !identity.isNullOrBlank()) {
            val exit = ProcessBuilder(
                "codesign", "--force", "--timestamp", "--options", "runtime",
                "--entitlements", entitlementsPath,
                "--sign", identity, signTarget.get()
            ).inheritIO().start().waitFor()
            check(exit == 0) { "codesign failed for ${signTarget.get()}" }
        }
    }
}

fun registerOlcRtcLibraryBuildTask(
    taskName: String,
    goos: String,
    goarch: String,
    outputName: String
) = tasks.register<Exec>(taskName) {
    val outputFile = generatedNativeResources.map { it.file("native/$outputName") }

    outputs.file(outputFile)
    workingDir = olcrtcRepoDir.get()
    environment("GOOS", goos)
    environment("GOARCH", goarch)
    environment("CGO_ENABLED", "1")
    commandLine(
        "go",
        "build",
        "-buildmode=c-shared",
        "-trimpath",
        "-ldflags",
        "-s -w",
        "-o",
        outputFile.get().asFile.absolutePath,
        "./cmd/olcrtc-cgo"
    )

    doFirst {
        outputFile.get().asFile.parentFile.mkdirs()
    }

    // Notarisation rejected the build over exactly this kind of file: the olcrtc
    // engine is produced here, long after CI has signed sing-box and xray, and it
    // travels inside the app's jar. Apple scans in there and wants a Developer ID
    // signature, a secure timestamp and the hardened runtime on every Mach-O.
    //
    // Signed at execution time with plain ProcessBuilder and System.getenv, and
    // with no reference to `project` — a task action that touches the project
    // breaks the configuration cache this build relies on.
    val signTarget = outputFile.map { it.asFile.absolutePath }
    val entitlementsPath = layout.projectDirectory.file("macos-entitlements.plist").asFile.absolutePath
    val signOnDarwin = goos == "darwin"
    doLast {
        val identity = System.getenv("MACOS_SIGN_IDENTITY")
        if (signOnDarwin && !identity.isNullOrBlank()) {
            val exit = ProcessBuilder(
                "codesign", "--force", "--timestamp", "--options", "runtime",
                "--entitlements", entitlementsPath,
                "--sign", identity, signTarget.get()
            ).inheritIO().start().waitFor()
            check(exit == 0) { "codesign failed for ${signTarget.get()}" }
        }
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

val buildOlcRtcLibDarwinArm64 = registerOlcRtcLibraryBuildTask(
    taskName = "buildOlcRtcLibDarwinArm64",
    goos = "darwin",
    goarch = "arm64",
    outputName = "libolcrtc-darwin-arm64.dylib"
)

val buildOlcRtcLibDarwinAmd64 = registerOlcRtcLibraryBuildTask(
    taskName = "buildOlcRtcLibDarwinAmd64",
    goos = "darwin",
    goarch = "amd64",
    outputName = "libolcrtc-darwin-amd64.dylib"
)

val buildOlcRtcLibLinuxAmd64 = registerOlcRtcLibraryBuildTask(
    taskName = "buildOlcRtcLibLinuxAmd64",
    goos = "linux",
    goarch = "amd64",
    outputName = "libolcrtc-linux-amd64.so"
)

val buildOlcRtcLibLinuxArm64 = registerOlcRtcLibraryBuildTask(
    taskName = "buildOlcRtcLibLinuxArm64",
    goos = "linux",
    goarch = "arm64",
    outputName = "libolcrtc-linux-arm64.so"
)

val buildOlcRtcLibWindowsAmd64 = registerOlcRtcLibraryBuildTask(
    taskName = "buildOlcRtcLibWindowsAmd64",
    goos = "windows",
    goarch = "amd64",
    outputName = "olcrtc-windows-amd64.dll"
)

val copyOlcRtcDataAssets = tasks.register<Copy>("copyOlcRtcDataAssets") {
    from(olcrtcRepoDir.map { it.resolve("data") }) {
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
    buildOlcRtcLibDarwinArm64,
    buildOlcRtcLibDarwinAmd64,
    buildOlcRtcLibLinuxAmd64,
    buildOlcRtcLibLinuxArm64,
    buildOlcRtcLibWindowsAmd64,
    copyOlcRtcDataAssets
)
val hostDesktopNativeAssetTasks = mutableListOf<Any>(
    copyOlcRtcDataAssets
)

when {
    currentBuildOs.isMacOsX -> when (hostDesktopArch) {
        "amd64" -> {
            hostDesktopNativeAssetTasks.add(buildOlcRtcDarwinAmd64)
            hostDesktopNativeAssetTasks.add(buildOlcRtcLibDarwinAmd64)
        }
        "arm64" -> {
            hostDesktopNativeAssetTasks.add(buildOlcRtcDarwinArm64)
            hostDesktopNativeAssetTasks.add(buildOlcRtcLibDarwinArm64)
        }
    }
    currentBuildOs.isWindows -> {
        hostDesktopNativeAssetTasks.add(buildOlcRtcWindowsAmd64)
        hostDesktopNativeAssetTasks.add(buildOlcRtcLibWindowsAmd64)
    }
    currentBuildOs.isLinux -> when (hostDesktopArch) {
        "amd64" -> {
            hostDesktopNativeAssetTasks.add(buildOlcRtcLinuxAmd64)
            hostDesktopNativeAssetTasks.add(buildOlcRtcLibLinuxAmd64)
        }
        "arm64" -> {
            hostDesktopNativeAssetTasks.add(buildOlcRtcLinuxArm64)
            hostDesktopNativeAssetTasks.add(buildOlcRtcLibLinuxArm64)
        }
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

if (currentBuildOs.isWindows) {
    val tun2SocksWindowsOutput = generatedNativeResources.map {
        it.file("native/tun2socks-windows-amd64.exe")
    }
    val wintunWindowsOutput = generatedNativeResources.map {
        it.file("native/wintun.dll")
    }

    val downloadTun2SocksWindowsAmd64 = tasks.register<DownloadFileTask>("downloadTun2SocksWindowsAmd64") {
        sourceUrl.set("https://github.com/xjasonlyu/tun2socks/releases/download/v$tun2SocksVersion/tun2socks-windows-amd64.zip")
        outputFile.set(layout.buildDirectory.file("tmp/tun2socks/tun2socks-windows-amd64-$tun2SocksVersion.zip"))
    }

    val extractTun2SocksWindowsAmd64 = tasks.register<ExtractZipEntryTask>("extractTun2SocksWindowsAmd64") {
        zipFile.set(downloadTun2SocksWindowsAmd64.flatMap { it.outputFile })
        entrySuffix.set("tun2socks-windows-amd64.exe")
        outputFile.set(tun2SocksWindowsOutput)
    }

    val downloadWintunWindowsAmd64 = tasks.register<DownloadFileTask>("downloadWintunWindowsAmd64") {
        sourceUrl.set("https://www.wintun.net/builds/wintun-$wintunVersion.zip")
        outputFile.set(layout.buildDirectory.file("tmp/wintun/wintun-$wintunVersion.zip"))
    }

    val extractWintunWindowsAmd64 = tasks.register<ExtractZipEntryTask>("extractWintunWindowsAmd64") {
        zipFile.set(downloadWintunWindowsAmd64.flatMap { it.outputFile })
        entrySuffix.set("/bin/amd64/wintun.dll")
        outputFile.set(wintunWindowsOutput)
    }

    desktopNativeAssetTasks.add(extractTun2SocksWindowsAmd64)
    desktopNativeAssetTasks.add(extractWintunWindowsAmd64)
    hostDesktopNativeAssetTasks.add(extractTun2SocksWindowsAmd64)
    hostDesktopNativeAssetTasks.add(extractWintunWindowsAmd64)
}

fun requiredHostNativeResourcePaths(): List<String> = buildList {
    add("olcrtc-data/names")
    add("olcrtc-data/surnames")
    when {
        currentBuildOs.isMacOsX -> {
            add("native/olcrtc-darwin-$hostDesktopArch")
            add("native/libolcrtc-darwin-$hostDesktopArch.dylib")
        }
        currentBuildOs.isWindows -> {
            add("native/olcrtc-windows-amd64.exe")
            add("native/olcrtc-windows-amd64.dll")
            add("native/tun2socks-windows-amd64.exe")
            add("native/wintun.dll")
        }
        currentBuildOs.isLinux -> {
            add("native/olcrtc-linux-$hostDesktopArch")
            add("native/libolcrtc-linux-$hostDesktopArch.so")
            add("native/hev-socks5-tunnel-linux-$hostDesktopArch")
        }
    }
}

val verifyDesktopNativeResources = tasks.register<VerifyNativeResourcesTask>("verifyDesktopNativeResources") {
    dependsOn(hostDesktopNativeAssetTasks.toList())
    resourcesDir.set(generatedNativeResources)
    requiredPaths.set(requiredHostNativeResourcePaths())
}

tasks.register("buildDesktopNativeAssets") {
    dependsOn(desktopNativeAssetTasks)
    dependsOn(verifyDesktopNativeResources)
}

sourceSets {
    main {
        resources.srcDir(generatedNativeResources)
        resources.srcDir(layout.projectDirectory.dir("appIcons"))
    }
}

if (currentBuildOs.isWindows) {
    val jpackageAppRootDir = layout.buildDirectory.dir("compose/binaries/main-release/app")

    tasks.register<Zip>("packageReleasePortableZip") {
        group = "distribution"
        description = "Packages a portable Windows zip from the jpackage app image."

        dependsOn("createReleaseDistributable")
        from(jpackageAppRootDir)
        archiveFileName.set("$desktopPackageName-$desktopPackageVersion-windows-amd64-portable.zip")
        destinationDirectory.set(layout.buildDirectory.dir("compose/binaries/main-release/portable"))

        doFirst {
            val appRoot = jpackageAppRootDir.get().asFile
            val appEntries = appRoot.listFiles().orEmpty()
            require(appRoot.isDirectory && appEntries.isNotEmpty()) {
                "Windows portable app image was not created at ${appRoot.absolutePath}"
            }
        }
    }
}

tasks.named("processResources") {
    dependsOn(verifyDesktopNativeResources)
}

listOf(
    "run",
    "createReleaseDistributable",
    "packageReleaseDistributionForCurrentOS",
    "packageReleaseExe",
    "packageReleaseMsi",
    "packageReleaseDmg",
    "packageReleaseAppImage",
    "packageReleasePortableZip"
).forEach { taskName ->
    tasks.matching { it.name == taskName }.configureEach {
        dependsOn(verifyDesktopNativeResources)
    }
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
                menuGroup = "Olcbox"
                shortcut = true
                dirChooser = true
                upgradeUuid = "6f0aaf78-dbed-4745-9d95-9e63f10a30de"
            }
            macOS {
                iconFile.set(project.file("appIcons/MacosIcon.icns"))
                bundleID = "org.olcbox.app.desktopApp"

                // Signed only when CI has the Developer ID identity in its
                // keychain; a developer without it still gets a build, exactly
                // like the Windows job behaves without its certificate.
                //
                // Unsigned is not merely "shows a warning" on Apple Silicon —
                // the kernel refuses to execute an unsigned binary at all, which
                // is why the unsigned DMG had to be talked through xattr.
                val signIdentity = providers.environmentVariable("MACOS_SIGN_IDENTITY").orNull
                if (!signIdentity.isNullOrBlank()) {
                    signing {
                        sign.set(true)
                        identity.set(signIdentity)
                    }
                    // Hardened runtime is required for notarisation, and a JVM
                    // app cannot start under it without these.
                    entitlementsFile.set(project.file("macos-entitlements.plist"))
                    runtimeEntitlementsFile.set(project.file("macos-entitlements.plist"))
                }
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

// ---------------------------------------------------------------------------
// macOS: the packet-tunnel system extension, and the bridge the JVM calls it
// through.
//
// Built with swiftc from Gradle rather than by adding an Xcode project. The
// desktop app has no Xcode project and one bundle does not justify a second way
// to build things; a system extension is an ordinary bundle — Info.plist, a
// binary, a signature — and Gradle already builds and signs native artifacts
// here.
//
// The ordering below is the part that is easy to get wrong. Compose signs the
// .app inside `createReleaseDistributable`, so anything added afterwards
// invalidates that signature: adding a bundle to a signed bundle does not
// re-sign the outer one, and the failure appears at launch as a broken app
// rather than as a signing error. Hence sign inner-out and re-sign the app
// itself last, with --force. `--deep` would be the short way and is the wrong
// one: it re-signs nested code with the *outer* entitlements, which would give
// the extension the app's and strip its NetworkExtension entitlement.
// ---------------------------------------------------------------------------
if (currentBuildOs.isMacOsX) {
    val sysextSourceDir = layout.projectDirectory.dir("systemextension")
    val bridgeSourceDir = layout.projectDirectory.dir("nativebridge")
    val appImageDir = layout.buildDirectory.dir("compose/binaries/main-release/app/$desktopPackageName.app")
    val sysextStageDir = layout.buildDirectory.dir("macos/systemextension")
    val packageVersion = desktopPackageVersion

    val embedMacosSystemExtension = tasks.register<Exec>("embedMacosSystemExtension") {
        group = "distribution"
        description = "Builds, signs and embeds the packet-tunnel system extension into the .app."

        dependsOn("createReleaseDistributable")
        inputs.dir(sysextSourceDir)
        inputs.dir(bridgeSourceDir)
        outputs.dir(sysextStageDir)

        commandLine(
            "bash",
            "-c",
            """
            set -euo pipefail

            app_dir="${'$'}1"
            sysext_src="${'$'}2"
            bridge_src="${'$'}3"
            stage="${'$'}4"
            version="${'$'}5"

            bundle_id="org.olcbox.app.desktopApp.PacketTunnel"
            sysext="${'$'}stage/PacketTunnel.systemextension"

            rm -rf "${'$'}stage"
            mkdir -p "${'$'}sysext/Contents/MacOS"

            # The provider. Linked against NetworkExtension; no cores yet — this
            # slice only has to prove macOS will load it from inside this app.
            swiftc -O -target arm64-apple-macos13.0 \
                -framework NetworkExtension -framework Foundation \
                -o "${'$'}sysext/Contents/MacOS/PacketTunnel" \
                "${'$'}sysext_src/PacketTunnelProvider.swift"

            sed -e "s/__MARKETING_VERSION__/${'$'}version/" \
                -e "s/__BUILD_VERSION__/${'$'}version/" \
                "${'$'}sysext_src/Info.plist" > "${'$'}sysext/Contents/Info.plist"

            # The bridge, a plain dylib the JVM opens with JNA.
            mkdir -p "${'$'}stage/lib"
            swiftc -O -target arm64-apple-macos13.0 -emit-library \
                -framework SystemExtensions -framework Foundation \
                -o "${'$'}stage/lib/libolcboxne.dylib" \
                "${'$'}bridge_src/OlcboxSystemExtension.swift"

            identity="${'$'}{MACOS_SIGN_IDENTITY:-}"
            if [ -n "${'$'}identity" ]; then
                # Inner-out, each with its own entitlements.
                codesign --force --timestamp --options runtime \
                    --entitlements "${'$'}sysext_src/PacketTunnel.entitlements" \
                    --sign "${'$'}identity" "${'$'}sysext"
                codesign --force --timestamp --options runtime \
                    --sign "${'$'}identity" "${'$'}stage/lib/libolcboxne.dylib"
            else
                echo "MACOS_SIGN_IDENTITY unset — embedding unsigned; macOS will refuse to install this extension."
            fi

            mkdir -p "${'$'}app_dir/Contents/Library/SystemExtensions"
            rm -rf "${'$'}app_dir/Contents/Library/SystemExtensions/PacketTunnel.systemextension"
            cp -R "${'$'}sysext" "${'$'}app_dir/Contents/Library/SystemExtensions/"
            cp "${'$'}stage/lib/libolcboxne.dylib" "${'$'}app_dir/Contents/MacOS/"

            if [ -n "${'$'}identity" ]; then
                codesign --force --timestamp --options runtime \
                    --entitlements "${'$'}{PWD}/macos-entitlements.plist" \
                    --sign "${'$'}identity" "${'$'}app_dir"
                codesign --verify --deep --strict --verbose=2 "${'$'}app_dir"
            fi
            """.trimIndent(),
            "bash",
            appImageDir.get().asFile.absolutePath,
            sysextSourceDir.asFile.absolutePath,
            bridgeSourceDir.asFile.absolutePath,
            sysextStageDir.get().asFile.absolutePath,
            packageVersion
        )
    }

    // The DMG must contain the app *after* the extension went in, not before.
    tasks.matching { it.name == "packageReleaseDmg" }.configureEach {
        dependsOn(embedMacosSystemExtension)
    }
}
