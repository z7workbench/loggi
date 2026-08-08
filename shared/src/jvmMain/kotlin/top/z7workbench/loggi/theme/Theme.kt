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

private val LightLoggiColors = LoggiColors(
    gutterBackground = Color(0xFFF5F5F5),
    gutterText = Color(0xFF9E9E9E),
    pinIndicator = Color(0xFFE53935),
    overviewTick = Color(0xFFFFA000),
    overviewViewport = Color(0x332196F3),
    splitter = Color(0xFFBDBDBD),
    currentLineBackground = Color(0x1A1565C0),
)

private val DarkLoggiColors = LoggiColors(
    gutterBackground = Color(0xFF252526),
    gutterText = Color(0xFF6E6E6E),
    pinIndicator = Color(0xFFEF5350),
    overviewTick = Color(0xFFFFB300),
    overviewViewport = Color(0x3364B5F6),
    splitter = Color(0xFF4A4A4A),
    currentLineBackground = Color(0x1F90CAF9),
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF1565C0),
    secondary = Color(0xFF5E35B1),
    background = Color(0xFFFFFFFF),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFF0F0F0),
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF90CAF9),
    secondary = Color(0xFFB39DDB),
    background = Color(0xFF1E1E1E),
    surface = Color(0xFF1E1E1E),
    surfaceVariant = Color(0xFF2D2D2D),
    onBackground = Color(0xFFE0E0E0),
    onSurface = Color(0xFFE0E0E0),
)

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
    uiFontFamily: FontFamily = FontFamily.Default,
    content: @Composable () -> Unit,
) {
    val systemDark = if (mode == ThemeMode.SYSTEM) rememberSystemDark() else false
    val dark = when (mode) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
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
    CompositionLocalProvider(LocalLoggiColors provides if (dark) DarkLoggiColors else LightLoggiColors) {
        MaterialTheme(
            colorScheme = if (dark) DarkScheme else LightScheme,
            typography = typography,
            content = content,
        )
    }
}
