package top.z7workbench.loggi.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState
import top.z7workbench.loggi.i18n.LocalStrings
import top.z7workbench.loggi.settings.AppSettings
import top.z7workbench.loggi.settings.LocaleSetting
import top.z7workbench.loggi.settings.SearchLayout
import top.z7workbench.loggi.settings.TabPlacement
import top.z7workbench.loggi.settings.ThemeMode
import top.z7workbench.loggi.vm.AppViewModel
import top.z7workbench.loggi.vm.ColorPickerRequest

/**
 * Settings in their own window (M8.5 — replaces the in-window AlertDialog):
 * general, appearance, display, tabs, search, highlighters, color presets.
 * All rows use the compact-density controls.
 */
@Composable
fun SettingsWindow(app: AppViewModel, onDismiss: () -> Unit) {
    val strings = LocalStrings.current
    val settings = app.settings

    Window(
        onCloseRequest = onDismiss,
        title = strings.settingsTitle,
        resizable = true,
        state = rememberWindowState(size = DpSize(560.dp, 680.dp)),
    ) {
        Surface(Modifier.fillMaxSize()) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Section(strings.sectionGeneral)
                SwitchRow(strings.reopenOnStartupLabel, settings.reopenOnStartup) { v ->
                    app.updateSettings { it.copy(reopenOnStartup = v) }
                }

                Section(strings.sectionAppearance)
                ChipRow(
                    label = strings.themeLabel,
                    options = listOf(
                        ThemeMode.SYSTEM to strings.themeSystem,
                        ThemeMode.LIGHT to strings.themeLight,
                        ThemeMode.DARK to strings.themeDark,
                    ),
                    selected = settings.themeMode,
                ) { mode -> app.updateSettings { it.copy(themeMode = mode) } }
                ChipRow(
                    label = strings.languageLabel,
                    options = listOf(
                        LocaleSetting.SYSTEM to strings.languageSystem,
                        LocaleSetting.EN to "English",
                        LocaleSetting.ZH to "中文",
                    ),
                    selected = settings.locale,
                ) { loc -> app.updateSettings { it.copy(locale = loc) } }

                Section(strings.sectionDisplay)
                FontFamilyRow(app, settings)
                SpinnerRow(
                    label = strings.fontSizeLabel,
                    value = settings.fontSizeSp,
                    range = 8f..32f,
                    step = 1f,
                    decimals = 0,
                    suffix = "sp",
                ) { v -> app.updateSettings { it.copy(fontSizeSp = v) } }
                SpinnerRow(
                    label = strings.lineHeightLabel,
                    value = settings.lineHeightFactor,
                    range = 1.0f..2.0f,
                    step = 0.05f,
                    decimals = 2,
                ) { v -> app.updateSettings { it.copy(lineHeightFactor = v) } }
                SpinnerRow(
                    label = strings.tabStopLabel,
                    value = settings.tabStop.toFloat(),
                    range = 1f..16f,
                    step = 1f,
                    decimals = 0,
                ) { v -> app.updateSettings { s -> s.copy(tabStop = v.toInt()) } }
                SwitchRow(strings.wrapLabel, settings.wrapLines) { v -> app.updateSettings { it.copy(wrapLines = v) } }
                SwitchRow(strings.expandTabsLabel, settings.expandTabs) { v -> app.updateSettings { it.copy(expandTabs = v) } }

                Section(strings.sectionTabs)
                ChipRow(
                    label = strings.tabPlacementLabel,
                    options = listOf(
                        TabPlacement.HORIZONTAL to strings.tabHorizontal,
                        TabPlacement.VERTICAL to strings.tabVertical,
                    ),
                    selected = settings.tabPlacement,
                ) { p -> app.updateSettings { it.copy(tabPlacement = p) } }

                Section(strings.sectionSearch)
                ChipRow(
                    label = strings.layoutLabel,
                    options = listOf(
                        SearchLayout.SIDE to strings.menuLayoutSide,
                        SearchLayout.BOTTOM to strings.menuLayoutBottom,
                        SearchLayout.DETACHED to strings.menuLayoutDetached,
                    ),
                    selected = settings.searchLayout,
                ) { l -> app.updateSettings { it.copy(searchLayout = l) } }
                ColorRow(strings.matchColorLabel, settings.searchMatchColorArgb) { picked ->
                    app.updateSettings { it.copy(searchMatchColorArgb = picked) }
                }
                SwitchRow(strings.searchMatchWholeLineLabel, settings.searchMatchWholeLine) { v ->
                    app.updateSettings { it.copy(searchMatchWholeLine = v) }
                }

                Section(strings.sectionHighlighters)
                Text(
                    strings.addHighlighterHint,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
                CompactButton(
                    text = "${strings.highlightersTitle}…",
                    onClick = { app.showHighlighters = true },
                    modifier = Modifier.padding(vertical = 2.dp),
                )

                Section(strings.sectionPresets)
                Row(Modifier.padding(vertical = 4.dp)) {
                    settings.highlightPresets.forEachIndexed { index, argb ->
                        ColorSwatch(argb) { picked ->
                            app.updateSettings { s ->
                                s.copy(
                                    highlightPresets = s.highlightPresets.mapIndexed { i, c ->
                                        if (i == index) picked else c
                                    },
                                )
                            }
                        }
                        Spacer(Modifier.width(6.dp))
                    }
                }
            }
        }
    }
}

/** System font families (Skia FontMgr enumeration) behind a compact dropdown. */
@Composable
private fun FontFamilyRow(app: AppViewModel, settings: AppSettings) {
    val strings = LocalStrings.current
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 3.dp)) {
        Text(strings.fontFamilyLabel, fontSize = 13.sp, modifier = Modifier.width(110.dp))
        Box {
            var open by remember { mutableStateOf(false) }
            CompactButton(text = "${settings.fontFamily}  ▾", onClick = { open = true })
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                pickableFontFamilies.forEach { name ->
                    CompactMenuItem(
                        text = name,
                        onClick = {
                            open = false
                            app.updateSettings { it.copy(fontFamily = name) }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun Section(title: String) {
    Column {
        Text(
            title,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
            modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
        )
        HorizontalDivider()
    }
}

@Composable
private fun <T> ChipRow(
    label: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 3.dp)) {
        Text(label, fontSize = 13.sp, modifier = Modifier.width(110.dp))
        options.forEach { (value, text) ->
            CompactButton(
                text = text,
                selected = selected == value,
                onClick = { onSelect(value) },
                modifier = Modifier.padding(end = 4.dp),
            )
        }
    }
}

@Composable
private fun SpinnerRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    step: Float,
    decimals: Int,
    suffix: String = "",
    onValue: (Float) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 3.dp)) {
        Text(label, fontSize = 13.sp, modifier = Modifier.width(110.dp))
        CompactNumberSpinner(value, onValue, range, step, decimals)
        if (suffix.isNotEmpty()) {
            Text(
                suffix,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 6.dp),
            )
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 3.dp)) {
        Text(label, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun ColorRow(label: String, argb: Long, onPick: (Long) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 3.dp)) {
        Text(label, fontSize = 13.sp, modifier = Modifier.width(180.dp))
        ColorSwatch(argb, onPick)
    }
}

@Composable
private fun ColorSwatch(argb: Long, onPick: (Long) -> Unit) {
    val host = LocalColorPickerHost.current
    Box(
        Modifier
            .size(22.dp)
            .background(Color(argb), RoundedCornerShape(4.dp))
            .clickable { host(ColorPickerRequest(argb, onPick)) },
    )
}
