import java.util.Locale

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
}

// ---------------------------------------------------------------------------
// Rust JNI bridge (crates/engine-jni) wiring.
//
// `cargoBuildJni` builds the cdylib with cargo (debug by default;
// `-Ploggi.jni.profile=release` for packaging) and `copyJniNatives` stages it
// under `natives/<os>-<arch>/` inside the jar resources. At runtime
// `NativeLoader` extracts it to a temp dir and `System.load`s it. During
// development the loader also probes `target/<profile>/` at the repo root, so
// a plain `cargo build -p loggi-engine-jni` is enough for `./gradlew run`.
// ---------------------------------------------------------------------------

val cargoProfile = providers.gradleProperty("loggi.jni.profile").orElse("debug").get()
val cargoProfileDir = if (cargoProfile.equals("release", ignoreCase = true)) "release" else "debug"

data class HostTarget(val os: String, val arch: String, val libFile: String)

fun detectHostTarget(): HostTarget {
    val os = System.getProperty("os.name").lowercase(Locale.ROOT)
    val arch = System.getProperty("os.arch").lowercase(Locale.ROOT)
    val osPart = when {
        os.contains("win") -> "windows"
        os.contains("mac") -> "macos"
        else -> "linux"
    }
    val archPart = when {
        arch.contains("aarch64") || arch.contains("arm") -> "aarch64"
        else -> "x86_64"
    }
    val lib = when (osPart) {
        "windows" -> "loggi_engine_jni.dll"
        "macos" -> "libloggi_engine_jni.dylib"
        else -> "libloggi_engine_jni.so"
    }
    return HostTarget(osPart, archPart, lib)
}

val hostTarget = detectHostTarget()

// skiko runtime classifier for the host OS/arch (jvmTest UI tests render through skiko).
val skikoRuntimeModule = when {
    hostTarget.os == "windows" -> "skiko-awt-runtime-windows-x64"
    hostTarget.os == "macos" && hostTarget.arch == "aarch64" -> "skiko-awt-runtime-macos-arm64"
    hostTarget.os == "macos" -> "skiko-awt-runtime-macos-x64"
    hostTarget.arch == "aarch64" -> "skiko-awt-runtime-linux-arm64"
    else -> "skiko-awt-runtime-linux-x64"
}

val cargoBuildJni = tasks.register<Exec>("cargoBuildJni") {
    group = "loggi"
    description = "Build the Rust JNI bridge with cargo ($cargoProfileDir profile)."
    workingDir(rootProject.projectDir)
    val args = mutableListOf("cargo", "build", "-p", "loggi-engine-jni")
    if (cargoProfileDir == "release") args.add("--release")
    commandLine(args)
}

val copyJniNatives = tasks.register<Copy>("copyJniNatives") {
    group = "loggi"
    description = "Stage the JNI cdylib into generated jar resources."
    dependsOn(cargoBuildJni)
    from(File(rootProject.projectDir, "target/$cargoProfileDir/${hostTarget.libFile}"))
    into(layout.buildDirectory.dir("loggi-natives/natives/${hostTarget.os}-${hostTarget.arch}"))
}

// desktopApp uses the generated Res class (window icon), so it must be public.
compose.resources {
    publicResClass = true
}

kotlin {
    jvm()

    sourceSets {
        commonMain.dependencies {
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
            implementation(libs.compose.components.resources)
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
        jvmMain.dependencies {
            implementation(libs.kotlinx.coroutinesCore)
            implementation(libs.kotlinx.coroutinesSwing)
            implementation(libs.kotlinx.serializationJson)
        }
        jvmTest.dependencies {
            implementation(libs.kotlin.testJunit)
            implementation(libs.junit)
            implementation(libs.compose.uiTest)
            // UI tests render through skiko; the test classpath does not get the
            // host native transitively — add it explicitly.
            implementation("org.jetbrains.skiko:$skikoRuntimeModule:${libs.versions.skiko.get()}")
        }
    }
}

tasks.named<Copy>("jvmProcessResources") {
    dependsOn(copyJniNatives)
    from(layout.buildDirectory.dir("loggi-natives"))
}
