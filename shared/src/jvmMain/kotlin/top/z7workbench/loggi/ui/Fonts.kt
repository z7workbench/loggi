package top.z7workbench.loggi.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.platform.Typeface as ComposeTypeface
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.FontStyle as SkiaFontStyle
import top.z7workbench.loggi.settings.AppSettings

/**
 * Every pickable font family: the Compose generic aliases first, then the
 * installed system families enumerated via Skia's [FontMgr] (M8.5 system
 * font picker).
 */
val pickableFontFamilies: List<String> by lazy {
    val system = runCatching {
        val mgr = FontMgr.default
        (0 until mgr.familiesCount).map { mgr.getFamilyName(it) }
    }.getOrDefault(emptyList())
    AppSettings.FONT_FAMILIES + system.map { it.trim() }.filter { it.isNotEmpty() }.distinct().sorted()
}

/**
 * Resolve a stored family name to a [FontFamily]: generic aliases map to the
 * Compose defaults; anything else is looked up in the system fonts via Skia
 * (falling back to Monospace when the family vanished between installs).
 */
fun fontFamilyForName(name: String): FontFamily = when (name) {
    AppSettings.FONT_SERIF -> FontFamily.Serif
    AppSettings.FONT_SANS_SERIF -> FontFamily.SansSerif
    AppSettings.FONT_MONOSPACE -> FontFamily.Monospace
    else -> runCatching {
        val skia = FontMgr.default.matchFamilyStyle(name, SkiaFontStyle.NORMAL)
        if (skia != null) FontFamily(ComposeTypeface(skia, name)) else FontFamily.Monospace
    }.getOrDefault(FontFamily.Monospace)
}

/** Composition-cached [fontFamilyForName]. */
@Composable
fun rememberLogFontFamily(name: String): FontFamily = remember(name) { fontFamilyForName(name) }
