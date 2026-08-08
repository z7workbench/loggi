import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import java.time.Year

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

dependencies {
    implementation(project(":shared"))

    implementation(compose.desktop.currentOs)
    implementation(libs.compose.components.resources)
    implementation(libs.kotlinx.coroutinesSwing)

    implementation(libs.compose.uiToolingPreview)
}

// ---------------------------------------------------------------------------
// Version: -Ploggi.version=… overrides the default (read from the Cargo
// workspace Cargo.toml so Rust + desktop app share a single source of truth).
// Used by the jpackage version + the Windows MSI/Exe and Linux Deb/Rpm
// packageVersion fields. Release CI sets this from the tag.
// ---------------------------------------------------------------------------
val loggiVersion: String = run {
    val override = providers.gradleProperty("loggi.version").orNull?.takeIf { it.isNotBlank() }
    if (override != null) {
        override
    } else {
        val cargo = rootProject.file("Cargo.toml")
        if (cargo.exists()) {
            Regex("""^\s*version\s*=\s*"([^"]+)"\s*$""", RegexOption.MULTILINE)
                .find(cargo.readText())
                ?.groupValues?.get(1)
        } else null
    } ?: "0.1.0"
}

compose.desktop {
    application {
        mainClass = "top.z7workbench.loggi.MainKt"

        nativeDistributions {
            // M10: every supported OS family. Rpm is new in M10 (PLAN.md §3).
            // Pkg (macOS installer) and Exe (Windows NSIS) are best-effort.
            targetFormats(
                TargetFormat.Dmg, TargetFormat.Pkg,
                TargetFormat.Msi, TargetFormat.Exe,
                TargetFormat.Deb, TargetFormat.Rpm,
            )
            packageName = "Loggi"
            packageVersion = loggiVersion
            description = "A log viewer for very large files"
            copyright = "MIT — ${Year.now().value}"
            vendor = "loggi"

            // M11: OS-level "Open with Loggi" on every file extension.
            // jpackage turns this into:
            //   - macOS: CFBundleDocumentTypes in Info.plist (LSItemContentTypes
            //     = [public.data]);
            //   - Windows: registry entries under HKCR\* — we additionally
            //     re-register HKCU\* at runtime (per-user, locale-aware) since
            //     jpackage's MSI writes to HKLM and that needs admin;
            //   - Linux: MimeType= in the installed .desktop file.
            macOS {
                iconFile.set(rootProject.file("packaging/icon.icns"))
                bundleID = "top.z7workbench.loggi"
                fileAssociation(extension = "*", mimeType = "public.data", description = "Any file")

                // macOS signing + notarization are best-effort: only wired
                // when the matching env vars / secrets are present in the
                // release runner. See docs/release.md.
                signing {
                    val identity = System.getenv("MACOS_SIGNING_IDENTITY")
                    if (!identity.isNullOrBlank()) {
                        sign.set(true)
                        this.identity.set(identity)
                        val keychain = System.getenv("MACOS_SIGNING_KEYCHAIN")
                        if (!keychain.isNullOrBlank()) {
                            this.keychain.set(keychain)
                        }
                    }
                }
                notarization {
                    val appleID = System.getenv("MACOS_NOTARIZATION_APPLE_ID")
                    val teamID = System.getenv("MACOS_NOTARIZATION_TEAM_ID")
                    val password = System.getenv("MACOS_NOTARIZATION_PASSWORD")
                    if (!appleID.isNullOrBlank() && !teamID.isNullOrBlank() && !password.isNullOrBlank()) {
                        this.appleID.set(appleID)
                        this.teamID.set(teamID)
                        this.password.set(password)
                    }
                }
            }
            windows {
                iconFile.set(rootProject.file("packaging/icon.ico"))
                perUserInstall = true
                dirChooser = true
                fileAssociation(extension = "*", mimeType = "public.data", description = "Any file")
                // Windows Authenticode signing is applied post-build in
                // `release.yml` via signtool. The DSL does not expose
                // `--win-sign-tool` directly, so a post-build step in the
                // release pipeline is the cleanest hook.
            }
            linux {
                iconFile.set(rootProject.file("packaging/icon-512.png"))
                fileAssociation(extension = "*", mimeType = "public.data", description = "Any file")
                appCategory = "Utility"
                menuGroup = "Utility"
                rpmLicenseType = "MIT"
                // loggi-user.desktop is also written at runtime by
                // `os.LinuxFileAssociation` (per-user, locale-aware) so the
                // verb shows up without root or a relogin.
            }
        }
    }
}

// Surface the version for release CI / docs. The version is resolved at
// execution time (so the configuration cache can serialize the task) and
// mirrors the packageVersion applied by jpackage.
tasks.register("printVersion") {
    group = "loggi"
    description = "Print the resolved loggi version (used by release CI)."
    val versionProvider = providers.gradleProperty("loggi.version")
    val cargoFile = rootProject.file("Cargo.toml")
    inputs.property("versionOverride", versionProvider).optional(true)
    inputs.file(cargoFile).optional(true).withPropertyName("cargo")
    doLast {
        val override = versionProvider.orNull
        val resolved = override?.takeIf { it.isNotBlank() }
            ?: if (cargoFile.exists()) {
                Regex("""^\s*version\s*=\s*"([^"]+)"\s*$""", RegexOption.MULTILINE)
                    .find(cargoFile.readText())?.groupValues?.get(1)
            } else null
            ?: "0.1.0"
        println(resolved)
    }
}
