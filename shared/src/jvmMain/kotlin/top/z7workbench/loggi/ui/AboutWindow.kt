package top.z7workbench.loggi.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState
import loggi.shared.generated.resources.Res
import loggi.shared.generated.resources.loggi_icon
import org.jetbrains.compose.resources.painterResource
import top.z7workbench.loggi.engine.EngineFile
import top.z7workbench.loggi.i18n.LocalStrings

private const val LOGGI_HOMEPAGE = "https://github.com/z7workbench/loggi"

/** Trim trailing ".0" components of a version ("1.0.0" → "1.0"). */
private fun displayVersion(v: String): String {
    val parts = v.split('.')
    var end = parts.size
    while (end > 2 && parts[end - 1] == "0") end--
    return parts.take(end).joinToString(".")
}

/**
 * About window: app name + version, engine version, license and homepage.
 * A separate window (not a dialog) so it can be moved/resized independently
 * of the main window (M10).
 */
@Composable
fun AboutWindow(onDismiss: () -> Unit) {
    val strings = LocalStrings.current
    val uriHandler = LocalUriHandler.current
    val engineVersion = remember { EngineFile.engineVersion() } // e.g. "loggi-engine 1.0.0"
    val appVersion = remember(engineVersion) {
        val space = engineVersion.lastIndexOf(' ')
        displayVersion(if (space >= 0) engineVersion.substring(space + 1) else engineVersion)
    }
    val engineDisplay = remember(engineVersion) {
        val space = engineVersion.lastIndexOf(' ')
        if (space >= 0) engineVersion.substring(0, space + 1) + displayVersion(engineVersion.substring(space + 1))
        else engineVersion
    }

    Window(
        onCloseRequest = onDismiss,
        title = strings.aboutTitle,
        icon = painterResource(Res.drawable.loggi_icon),
        resizable = false,
        state = rememberWindowState(size = DpSize(400.dp, 320.dp)),
    ) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Image(
                        painterResource(Res.drawable.loggi_icon),
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                    )
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text(
                            "Loggi",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            "${strings.aboutVersionLabel} $appVersion",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
                Text(strings.aboutText(engineDisplay), fontSize = 13.sp, lineHeight = 19.sp)
                Spacer(Modifier.height(10.dp))
                Text(
                    strings.aboutLicense,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "github.com/z7workbench/loggi",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .weight(1f)
                            .clickable { uriHandler.openUri(LOGGI_HOMEPAGE) },
                    )
                    CompactButton(text = strings.okButton, onClick = onDismiss)
                }
            }
        }
    }
}
