package top.z7workbench.loggi.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState
import top.z7workbench.loggi.i18n.LocalStrings
import top.z7workbench.loggi.vm.AppViewModel

/**
 * Global search history window: review entries, delete individual ones, or
 * clear everything. The history is persisted in `loggi.conf` and shared by
 * all files (M8.5).
 */
@Composable
fun SearchHistoryWindow(app: AppViewModel, onDismiss: () -> Unit) {
    val strings = LocalStrings.current
    Window(
        onCloseRequest = onDismiss,
        title = strings.searchHistoryTitle,
        resizable = true,
        state = rememberWindowState(size = DpSize(480.dp, 420.dp)),
    ) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp)) {
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    CompactButton(
                        text = strings.clearAllLabel,
                        onClick = { app.clearSearchHistory() },
                        enabled = app.searchHistory.isNotEmpty(),
                    )
                }
                if (app.searchHistory.isEmpty()) {
                    Text(
                        strings.searchHistoryEmptyLabel,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 6.dp),
                    )
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    app.searchHistory.forEach { pattern ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
                        ) {
                            Text(
                                pattern,
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                "×",
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier
                                    .clickable { app.removeSearchHistory(pattern) }
                                    .padding(horizontal = 6.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
