import org.jetbrains.compose.desktop.application.dsl.TargetFormat

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

compose.desktop {
    application {
        mainClass = "top.z7workbench.loggi.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "Loggi"
            packageVersion = "1.0.0"
            description = "A log viewer for very large files"
            vendor = "loggi"

            macOS {
                iconFile.set(rootProject.file("packaging/icon.icns"))
                bundleID = "top.z7workbench.loggi"
            }
            windows {
                iconFile.set(rootProject.file("packaging/icon.ico"))
            }
            linux {
                iconFile.set(rootProject.file("packaging/icon-512.png"))
            }
        }
    }
}
