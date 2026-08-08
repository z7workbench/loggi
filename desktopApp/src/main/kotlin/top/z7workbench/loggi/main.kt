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
import loggi.shared.generated.resources.Res
import loggi.shared.generated.resources.loggi_icon
import org.jetbrains.compose.resources.painterResource
import top.z7workbench.loggi.vm.AppViewModel

fun main() = application {
    val app = remember { AppViewModel() }

    fun shutdown() {
        app.shutdown()
        exitApplication()
    }

    Window(
        onCloseRequest = ::shutdown,
        title = "Loggi",
        icon = painterResource(Res.drawable.loggi_icon),
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
