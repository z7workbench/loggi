package top.z7workbench.loggi

import androidx.compose.runtime.remember
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import top.z7workbench.loggi.vm.AppViewModel
import java.io.File

/**
 * Parse command-line arguments into absolute file paths. The first positional
 * arg is the file to open (M11 "Open with Loggi" entry point); remaining
 * positional args open additional tabs.
 *
 * Only the path is consumed here — every other flag the launcher might pass
 * (`-psn_0_…` on macOS, `--enable-preview`, etc.) is ignored so packaged
 * launches stay robust.
 */
private fun parseInitialFiles(args: Array<String>): List<String> = args
    .asSequence()
    .filter { !it.startsWith("-") }
    .map { File(it).absolutePath }
    .filter { it.isNotBlank() }
    .toList()

fun main(args: Array<String>) {
    // Compose Desktop reads the macOS top-of-screen menu-bar app name from the
    // `app.name` system property, which defaults to the mainClass suffix
    // (e.g. "MainKt"). Set it explicitly so the menu shows "Loggi" while
    // running via `./gradlew :desktopApp:run` (packaged DMGs read CFBundleName
    // from Info.plist, so this is a no-op there).
    System.setProperty("app.name", "Loggi")

    // M11: command-line file entry. Each positional arg opens one tab. Every
    // flag the launcher might pass (`-psn_0_…` on macOS, `--enable-preview`,
    // JVM args consumed before us, etc.) is ignored so packaged launches stay
    // robust.
    val initialFiles = parseInitialFiles(args)

    application {
        val app = remember { AppViewModel() }
        initialFiles.forEach(app::openFile)

        fun shutdown() {
            app.shutdown()
            exitApplication()
        }

        Window(
            onCloseRequest = ::shutdown,
            title = "Loggi",
            // No `icon = ...` here on purpose: the 512×512 macOS app-icon-style
            // drawable (`loggi_icon`) renders as a thick rounded-rect with dot
            // texture when scaled to the 18 pt menu-bar slot on macOS, which
            // reads as oversized. Packaged DMGs still get the proper icon from
            // packaging/icon.icns (CFBundleIconFile); dev runs have no menu-bar
            // icon, matching the typical Swing/Java dev experience.
            onPreviewKeyEvent = { ev ->
                if (ev.type == KeyEventType.KeyDown && (ev.isCtrlPressed || ev.isMetaPressed)) {
                    when (ev.key) {
                        Key.Minus -> {
                            app.updateSettings { it.copy(fontSizeSp = (it.fontSizeSp - 1f).coerceAtLeast(8f)) }
                            true
                        }

                        Key.Equals -> {
                            app.updateSettings { it.copy(fontSizeSp = (it.fontSizeSp + 1f).coerceAtMost(32f)) }
                            true
                        }

                        Key.L -> {
                            app.activeVm?.let { vm -> app.goToLineFor = vm }
                            true
                        }

                        Key.W -> {
                            app.activeTab?.let(app::closeTab)
                            true
                        }

                        else -> false
                    }
                } else {
                    false
                }
            },
        ) {
            App(app, onExit = ::shutdown)
        }
    }
}
