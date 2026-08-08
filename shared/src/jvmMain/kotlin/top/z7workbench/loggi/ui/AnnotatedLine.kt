package top.z7workbench.loggi.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import top.z7workbench.loggi.model.expandTabsRemap
import top.z7workbench.loggi.vm.FileViewModel

/**
 * Display text of one line with highlighter + search-match spans applied
 * (tab expansion per settings, engine-provided match positions). Shared by
 * the log view and the results pane.
 */
@Composable
fun rememberAnnotatedLine(vm: FileViewModel, line: Long, raw: String): AnnotatedString {
    val settings = vm.app.settings
    val spans = remember(
        raw,
        settings.highlighters,
        vm.searchPattern,
        vm.ignoreCase,
        vm.useRegex,
        settings.searchMatchColorArgb,
        settings.searchMatchWholeLine,
    ) {
        vm.computeLineSpans(line, raw)
    }
    val tabStop = if (settings.expandTabs) settings.tabStop else 0
    return remember(raw, spans, tabStop) {
        val (text, remapped) = expandTabsRemap(raw, tabStop, spans.map { it.span })
        buildAnnotatedString {
            append(text)
            remapped.forEachIndexed { i, s ->
                val start = s.start.coerceIn(0, text.length)
                val end = s.end.coerceIn(start, text.length)
                if (end > start) addStyle(SpanStyle(background = spans[i].color), start, end)
            }
        }
    }
}
