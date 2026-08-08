package top.z7workbench.loggi

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Rule
import org.junit.Test
import java.nio.file.Files
import java.lang.reflect.Modifier
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import top.z7workbench.loggi.i18n.AppLocale
import top.z7workbench.loggi.i18n.DeStrings
import top.z7workbench.loggi.i18n.EnStrings
import top.z7workbench.loggi.i18n.FrStrings
import top.z7workbench.loggi.i18n.RuStrings
import top.z7workbench.loggi.i18n.Strings
import top.z7workbench.loggi.i18n.ZhHantStrings
import top.z7workbench.loggi.i18n.ZhStrings
import top.z7workbench.loggi.i18n.resolveLocale
import top.z7workbench.loggi.i18n.stringsFor
import top.z7workbench.loggi.settings.AppSettings
import top.z7workbench.loggi.settings.ColorScheme
import top.z7workbench.loggi.settings.HighlighterRule
import top.z7workbench.loggi.settings.LocaleSetting
import top.z7workbench.loggi.settings.SettingsStore
import top.z7workbench.loggi.settings.TabSession
import top.z7workbench.loggi.settings.ThemeMode
import top.z7workbench.loggi.theme.LocalLoggiColors
import top.z7workbench.loggi.theme.LoggiColors
import top.z7workbench.loggi.theme.LoggiTheme
import kotlin.test.assertNotEquals

class SettingsTest {
    @Test
    fun roundTrip() {
        val file = Files.createTempDirectory("loggi-settings-").resolve("loggi.conf")
        val store = SettingsStore(file)
        val settings = AppSettings(
            themeMode = ThemeMode.DARK,
            colorScheme = ColorScheme.GREEN,
            locale = LocaleSetting.ZH,
            fontSizeSp = 15f,
            highlighters = listOf(HighlighterRule("ERROR", 0x66FF5252)),
            sessionTabs = listOf(TabSession(path = "/tmp/a.log", topLine = 42, pins = listOf(7, 9))),
            activeTabIndex = 0,
        )
        store.save(settings)
        val loaded = store.load()
        assertEquals(settings, loaded)
    }

    @Test
    fun corruptFileFallsBackToDefaults() {
        val file = Files.createTempDirectory("loggi-settings-").resolve("loggi.conf")
        Files.writeString(file, "{ not json !!")
        assertEquals(AppSettings(), SettingsStore(file).load())
    }

    @Test
    fun unknownKeysAreTolerated() {
        val file = Files.createTempDirectory("loggi-settings-").resolve("loggi.conf")
        Files.writeString(file, """{"themeMode":"LIGHT","futureField":123}""")
        assertEquals(ThemeMode.LIGHT, SettingsStore(file).load().themeMode)
    }
}

class ThemeTest {
    @get:Rule
    val compose = createComposeRule()

    /**
     * M9 dark-mode regression: foreground text defaults to `Color.Black` when
     * not wrapped in a `Surface` (M3 does not auto-provide `LocalContentColor`
     * outside Surfaces). The fix is the top-level `Surface` in `App.kt`; this
     * test asserts the colour contract both schemes must satisfy.
     */
    @Test
    fun darkSchemeForegroundIsLight() {
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) { /* anchor */ }
        }
        val scheme = darkColorScheme()
        // Foreground channels must dominate the background channels in dark mode.
        assertTrue(
            luminance(scheme.onBackground) > luminance(scheme.background),
            "dark onBackground (${scheme.onBackground}) must be lighter than background (${scheme.background})",
        )
        assertTrue(
            luminance(scheme.onSurface) > luminance(scheme.surface),
            "dark onSurface (${scheme.onSurface}) must be lighter than surface (${scheme.surface})",
        )
    }

    @Test
    fun lightSchemeForegroundIsDark() {
        val scheme = lightColorScheme()
        assertTrue(
            luminance(scheme.onBackground) < luminance(scheme.background),
            "light onBackground (${scheme.onBackground}) must be darker than background (${scheme.background})",
        )
        assertTrue(
            luminance(scheme.onSurface) < luminance(scheme.surface),
            "light onSurface (${scheme.onSurface}) must be darker than surface (${scheme.surface})",
        )
    }

    @Test
    fun loggiThemeProvidesDistinctDarkAndLightPalettes() {
        var darkColors: LoggiColors? = null
        var lightColors: LoggiColors? = null
        compose.setContent {
            LoggiTheme(mode = ThemeMode.DARK) {
                darkColors = LocalLoggiColors.current
            }
        }
        compose.setContent {
            LoggiTheme(mode = ThemeMode.LIGHT) {
                lightColors = LocalLoggiColors.current
            }
        }
        // Gutter background and text must flip together; otherwise one of the
        // schemes ends up with light text on a light background (or vice
        // versa) and the line numbers vanish.
        val dark = requireNotNull(darkColors)
        val light = requireNotNull(lightColors)
        assertNotEquals(dark.gutterBackground, light.gutterBackground)
        assertNotEquals(dark.gutterText, light.gutterText)
    }

    private fun luminance(c: Color): Double =
        0.2126 * c.red + 0.7152 * c.green + 0.0722 * c.blue
}

class ColorSchemeTest {
    /** Every scheme must be unique and carry a distinct light + dark variant. */
    @Test
    fun schemesHaveDistinctPalettes() {
        val lightPrimaries = ColorScheme.entries.map { Color(it.lightPrimary) }
        val darkPrimaries = ColorScheme.entries.map { Color(it.darkPrimary) }
        assertEquals(lightPrimaries.size, lightPrimaries.distinct().size, "light primaries must be pairwise distinct")
        assertEquals(darkPrimaries.size, darkPrimaries.distinct().size, "dark primaries must be pairwise distinct")
        ColorScheme.entries.forEach { scheme ->
            assertTrue(
                Color(scheme.lightPrimary) != Color(scheme.darkPrimary),
                "${scheme} light and dark variants must differ",
            )
        }
    }
}

class StringsTest {
    /** Every member declared on [Strings] must be overridden by each locale. */
    @Test
    fun everyLocaleOverridesEveryString() {
        val localeClasses = listOf(
            EnStrings::class.java,
            ZhStrings::class.java,
            ZhHantStrings::class.java,
            FrStrings::class.java,
            DeStrings::class.java,
            RuStrings::class.java,
        )
        localeClasses.forEach { locale ->
            val missing = Strings::class.java.declaredMethods
                .filter { Modifier.isAbstract(it.modifiers) }
                .filter { method ->
                    val impl = runCatching {
                        locale.getMethod(method.name, *method.parameterTypes)
                    }.getOrNull()
                    impl == null || impl.declaringClass == Strings::class.java
                }
                .map { it.name }
            assertTrue(
                missing.isEmpty(),
                "${locale.simpleName} missing overrides for: $missing",
            )
        }
    }

    @Test
    fun localesProduceDifferentText() {
        val en = EnStrings()
        val locales = listOf(
            ZhStrings(),
            ZhHantStrings(),
            FrStrings(),
            DeStrings(),
            RuStrings(),
        )
        locales.forEach { locale ->
            assertTrue(locale.menuFile != en.menuFile, "${locale::class.simpleName} shares menuFile with English")
            assertTrue(locale.matchesCount(5) != en.matchesCount(5), "${locale::class.simpleName} shares matchesCount with English")
        }
    }

    @Test
    fun resolveLocaleMapsSystemLocales() {
        assertEquals(AppLocale.EN, resolveLocale(LocaleSetting.EN))
        assertEquals(AppLocale.ZH, resolveLocale(LocaleSetting.ZH))
        assertEquals(AppLocale.ZH_HANT, resolveLocale(LocaleSetting.ZH_HANT))
        assertEquals(AppLocale.FR, resolveLocale(LocaleSetting.FR))
        assertEquals(AppLocale.DE, resolveLocale(LocaleSetting.DE))
        assertEquals(AppLocale.RU, resolveLocale(LocaleSetting.RU))
        assertTrue(stringsFor(AppLocale.EN) is EnStrings)
        assertTrue(stringsFor(AppLocale.ZH) is ZhStrings)
        assertTrue(stringsFor(AppLocale.ZH_HANT) is ZhHantStrings)
        assertTrue(stringsFor(AppLocale.FR) is FrStrings)
        assertTrue(stringsFor(AppLocale.DE) is DeStrings)
        assertTrue(stringsFor(AppLocale.RU) is RuStrings)
    }
}
