package top.z7workbench.loggi.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState
import top.z7workbench.loggi.i18n.LocalStrings
import top.z7workbench.loggi.settings.HighlighterRule
import top.z7workbench.loggi.vm.AppViewModel
import top.z7workbench.loggi.vm.ColorPickerRequest

/**
 * Highlighter management window (M8.5): every rule's pattern, color and
 * ignore-case / regex / whole-line flags are editable here. Edits apply to
 * the log views live and persist through the settings flow.
 */
@Composable
fun HighlightersWindow(app: AppViewModel, onDismiss: () -> Unit) {
    val strings = LocalStrings.current
    Window(
        onCloseRequest = onDismiss,
        title = strings.highlightersTitle,
        resizable = true,
        state = rememberWindowState(size = DpSize(680.dp, 420.dp)),
    ) {
        Surface(Modifier.fillMaxSize()) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    CompactButton(
                        text = "＋ ${strings.highlighterAddLabel}",
                        onClick = {
                            app.addHighlighter(
                                HighlighterRule(
                                    pattern = "",
                                    colorArgb = app.settings.highlightPresets.first(),
                                ),
                            )
                        },
                    )
                }
                if (app.settings.highlighters.isEmpty()) {
                    Text(
                        strings.addHighlighterHint,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 6.dp),
                    )
                }
                app.settings.highlighters.forEachIndexed { index, rule ->
                    HighlighterRow(
                        rule = rule,
                        onChange = { updated ->
                            app.updateSettings { s ->
                                s.copy(
                                    highlighters = s.highlighters.mapIndexed { i, r ->
                                        if (i == index) updated else r
                                    },
                                )
                            }
                        },
                        onRemove = { app.removeHighlighter(index) },
                    )
                }
            }
        }
    }
}

@Composable
private fun HighlighterRow(
    rule: HighlighterRule,
    onChange: (HighlighterRule) -> Unit,
    onRemove: () -> Unit,
) {
    val strings = LocalStrings.current
    val picker = LocalColorPickerHost.current
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Box(
            Modifier
                .size(20.dp)
                .background(Color(rule.colorArgb), RoundedCornerShape(4.dp))
                .clickable { picker(ColorPickerRequest(rule.colorArgb) { picked -> onChange(rule.copy(colorArgb = picked)) }) },
        )
        if (rule.anchorLine != null) {
            // Line-anchored selection highlight: the range is fixed, so the
            // pattern is a read-only preview; only color / removal apply.
            Text(
                strings.anchoredHighlightLabel(rule.anchorLine + 1),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 6.dp),
            )
            Text(
                rule.pattern,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(horizontal = 6.dp),
            )
        } else {
            CompactSearchField(
                value = rule.pattern,
                onValueChange = { onChange(rule.copy(pattern = it)) },
                placeholder = strings.highlighterPatternPlaceholder,
                isError = rule.regex && rule.pattern.isNotEmpty() && runCatching { Regex(rule.pattern) }.isFailure,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(),
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 6.dp),
            )
            CompactButton(
                text = strings.ignoreCaseLabel,
                selected = rule.ignoreCase,
                onClick = { onChange(rule.copy(ignoreCase = !rule.ignoreCase)) },
            )
            CompactButton(
                text = strings.regexLabel,
                selected = rule.regex,
                onClick = { onChange(rule.copy(regex = !rule.regex)) },
                modifier = Modifier.padding(start = 4.dp),
            )
            CompactButton(
                text = strings.wholeLineLabel,
                selected = rule.wholeLine,
                onClick = { onChange(rule.copy(wholeLine = !rule.wholeLine)) },
                modifier = Modifier.padding(start = 4.dp),
            )
        }
        Text(
            "×",
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier
                .clickable(onClick = onRemove)
                .padding(horizontal = 6.dp),
        )
    }
}
