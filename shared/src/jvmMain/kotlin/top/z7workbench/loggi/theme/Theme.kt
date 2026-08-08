package top.z7workbench.loggi.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import kotlinx.coroutines.delay
import org.jetbrains.skiko.SystemTheme
import org.jetbrains.skiko.currentSystemTheme
import top.z7workbench.loggi.settings.ColorScheme
import top.z7workbench.loggi.settings.ThemeMode

/** Extra semantic colors Material3 does not cover (gutter, overview, pins…). */
data class LoggiColors(
    val gutterBackground: Color,
    val gutterText: Color,
    val pinIndicator: Color,
    val overviewTick: Color,
    val overviewViewport: Color,
    val splitter: Color,
    val currentLineBackground: Color,
)

val LocalLoggiColors = compositionLocalOf {
    LoggiColors(
        gutterBackground = Color(0xFFF5F5F5),
        gutterText = Color(0xFF9E9E9E),
        pinIndicator = Color(0xFFE53935),
        overviewTick = Color(0xFFFFC107),
        overviewViewport = Color(0x332196F3),
        splitter = Color(0xFFBDBDBD),
        currentLineBackground = Color(0x1A1565C0),
    )
}

/**
 * Derived LoggiColors: gutters/splitter stay neutral, while the overview
 * viewport tint and the current-line background follow the scheme's primary
 * color so every scheme reads distinctly in both light and dark mode.
 */
private fun lightLoggi(primary: Color) = LoggiColors(
    gutterBackground = Color(0xFFF5F5F5),
    gutterText = Color(0xFF9E9E9E),
    pinIndicator = Color(0xFFE53935),
    overviewTick = Color(0xFFFFA000),
    overviewViewport = primary.copy(alpha = 0.20f),
    splitter = Color(0xFFBDBDBD),
    currentLineBackground = primary.copy(alpha = 0.10f),
)

private fun darkLoggi(primary: Color) = LoggiColors(
    gutterBackground = Color(0xFF252526),
    gutterText = Color(0xFF6E6E6E),
    pinIndicator = Color(0xFFEF5350),
    overviewTick = Color(0xFFFFB300),
    overviewViewport = primary.copy(alpha = 0.20f),
    splitter = Color(0xFF4A4A4A),
    currentLineBackground = primary.copy(alpha = 0.12f),
)

/** One Material3 scheme's seeds (accents + window fills) + the derived [LoggiColors]. */
private data class Palette(
    val primary: Color,
    val secondary: Color,
    val surfaceVariant: Color,
    val background: Color,
    val loggi: LoggiColors,
)

/**
 * Light + dark palettes per scheme. Backgrounds are tinted per scheme (subtle
 * in light mode, a dark hue in dark mode) so switching schemes is visible in
 * the window fills; foregrounds stay neutral to keep contrast constant.
 */
private fun paletteFor(scheme: ColorScheme, dark: Boolean): Palette = when (scheme) {
    ColorScheme.VIOLET ->
        if (dark) Palette(Color(scheme.darkPrimary), Color(0xFF90CAF9), Color(0xFF2D2D2D), Color(0xFF1E1E1E), darkLoggi(Color(scheme.darkPrimary)))
        else Palette(Color(scheme.lightPrimary), Color(0xFF1565C0), Color(0xFFF0F0F0), Color(0xFFFFFFFF), lightLoggi(Color(scheme.lightPrimary)))
    ColorScheme.BLUE ->
        if (dark) Palette(Color(scheme.darkPrimary), Color(0xFF4DD0E1), Color(0xFF223041), Color(0xFF161D26), darkLoggi(Color(scheme.darkPrimary)))
        else Palette(Color(scheme.lightPrimary), Color(0xFF00838F), Color(0xFFE7F1F9), Color(0xFFF7FAFD), lightLoggi(Color(scheme.lightPrimary)))
    ColorScheme.TEAL ->
        if (dark) Palette(Color(scheme.darkPrimary), Color(0xFF80CBC4), Color(0xFF20302D), Color(0xFF14211F), darkLoggi(Color(scheme.darkPrimary)))
        else Palette(Color(scheme.lightPrimary), Color(0xFF00897B), Color(0xFFE1F0ED), Color(0xFFF5FAF9), lightLoggi(Color(scheme.lightPrimary)))
    ColorScheme.GREEN ->
        if (dark) Palette(Color(scheme.darkPrimary), Color(0xFFAED581), Color(0xFF22301F), Color(0xFF161E15), darkLoggi(Color(scheme.darkPrimary)))
        else Palette(Color(scheme.lightPrimary), Color(0xFF33691E), Color(0xFFE9F2E7), Color(0xFFF7FAF5), lightLoggi(Color(scheme.lightPrimary)))
    ColorScheme.ORANGE ->
        if (dark) Palette(Color(scheme.darkPrimary), Color(0xFFFFD54F), Color(0xFF352A1B), Color(0xFF221B12), darkLoggi(Color(scheme.darkPrimary)))
        else Palette(Color(scheme.lightPrimary), Color(0xFFF57F17), Color(0xFFFAEDDF), Color(0xFFFDF9F3), lightLoggi(Color(scheme.lightPrimary)))
    ColorScheme.AMBER ->
        if (dark) Palette(Color(scheme.darkPrimary), Color(0xFFFFE082), Color(0xFF36301C), Color(0xFF211D12), darkLoggi(Color(scheme.darkPrimary)))
        else Palette(Color(scheme.lightPrimary), Color(0xFFB8860B), Color(0xFFF8F0D8), Color(0xFFFDFBF2), lightLoggi(Color(scheme.lightPrimary)))
    ColorScheme.ROSE ->
        if (dark) Palette(Color(scheme.darkPrimary), Color(0xFFF48FB1), Color(0xFF372222), Color(0xFF241818), darkLoggi(Color(scheme.darkPrimary)))
        else Palette(Color(scheme.lightPrimary), Color(0xFFAD1457), Color(0xFFF9E9E9), Color(0xFFFDF7F7), lightLoggi(Color(scheme.lightPrimary)))
    ColorScheme.SLATE ->
        if (dark) Palette(Color(scheme.darkPrimary), Color(0xFFB0BEC5), Color(0xFF2B3136), Color(0xFF191D20), darkLoggi(Color(scheme.darkPrimary)))
        else Palette(Color(scheme.lightPrimary), Color(0xFF78909C), Color(0xFFE9EDEF), Color(0xFFFAFBFB), lightLoggi(Color(scheme.lightPrimary)))
    ColorScheme.INDIGO ->
        if (dark) Palette(Color(scheme.darkPrimary), Color(0xFF7986CB), Color(0xFF2A2D45), Color(0xFF181A28), darkLoggi(Color(scheme.darkPrimary)))
        else Palette(Color(scheme.lightPrimary), Color(0xFF3949AB), Color(0xFFE8E9F7), Color(0xFFF9F9FD), lightLoggi(Color(scheme.lightPrimary)))
}

private fun materialScheme(palette: Palette, dark: Boolean) =
    if (dark) {
        darkColorScheme(
            primary = palette.primary,
            secondary = palette.secondary,
            background = palette.background,
            surface = palette.background,
            surfaceVariant = palette.surfaceVariant,
            onBackground = Color(0xFFE0E0E0),
            onSurface = Color(0xFFE0E0E0),
        )
    } else {
        lightColorScheme(
            primary = palette.primary,
            secondary = palette.secondary,
            background = palette.background,
            surface = palette.background,
            surfaceVariant = palette.surfaceVariant,
        )
    }

/**
 * Current OS dark-mode state. `isSystemInDarkTheme` seeds the value; a 2 s
 * poll of skiko's `currentSystemTheme` keeps follow-system mode live when the
 * OS theme flips (Compose desktop does not recompose on OS theme changes).
 */
@Composable
fun rememberSystemDark(): Boolean {
    val system = isSystemInDarkTheme()
    var dark by remember { mutableStateOf(system) }
    LaunchedEffect(Unit) {
        while (true) {
            dark = currentSystemTheme == SystemTheme.DARK
            delay(2_000)
        }
    }
    return dark
}

@Composable
fun LoggiTheme(
    mode: ThemeMode,
    colorScheme: ColorScheme = ColorScheme.VIOLET,
    uiFontFamily: FontFamily = FontFamily.Default,
    content: @Composable () -> Unit,
) {
    val systemDark = if (mode == ThemeMode.SYSTEM) rememberSystemDark() else false
    val dark = when (mode) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val palette = paletteFor(colorScheme, dark)
    // Every typography style carries the configured UI font family; the log
    // view itself overrides the family per-text with the log font.
    val base = Typography()
    val typography = remember(uiFontFamily) {
        Typography(
            displayLarge = base.displayLarge.copy(fontFamily = uiFontFamily),
            displayMedium = base.displayMedium.copy(fontFamily = uiFontFamily),
            displaySmall = base.displaySmall.copy(fontFamily = uiFontFamily),
            headlineLarge = base.headlineLarge.copy(fontFamily = uiFontFamily),
            headlineMedium = base.headlineMedium.copy(fontFamily = uiFontFamily),
            headlineSmall = base.headlineSmall.copy(fontFamily = uiFontFamily),
            titleLarge = base.titleLarge.copy(fontFamily = uiFontFamily),
            titleMedium = base.titleMedium.copy(fontFamily = uiFontFamily),
            titleSmall = base.titleSmall.copy(fontFamily = uiFontFamily),
            bodyLarge = base.bodyLarge.copy(fontFamily = uiFontFamily),
            bodyMedium = base.bodyMedium.copy(fontFamily = uiFontFamily),
            bodySmall = base.bodySmall.copy(fontFamily = uiFontFamily),
            labelLarge = base.labelLarge.copy(fontFamily = uiFontFamily),
            labelMedium = base.labelMedium.copy(fontFamily = uiFontFamily),
            labelSmall = base.labelSmall.copy(fontFamily = uiFontFamily),
        )
    }
    CompositionLocalProvider(LocalLoggiColors provides palette.loggi) {
        MaterialTheme(
            colorScheme = materialScheme(palette, dark),
            typography = typography,
            content = content,
        )
    }
}
