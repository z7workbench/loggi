package top.z7workbench.loggi

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import top.z7workbench.loggi.i18n.EnStrings
import top.z7workbench.loggi.i18n.Strings
import top.z7workbench.loggi.i18n.ZhStrings
import top.z7workbench.loggi.settings.AppSettings
import top.z7workbench.loggi.settings.HighlighterRule
import top.z7workbench.loggi.settings.LocaleSetting
import top.z7workbench.loggi.settings.SettingsStore
import top.z7workbench.loggi.settings.TabSession
import top.z7workbench.loggi.settings.ThemeMode

class SettingsTest {
    @Test
    fun roundTrip() {
        val file = Files.createTempDirectory("loggi-settings-").resolve("loggi.conf")
        val store = SettingsStore(file)
        val settings = AppSettings(
            themeMode = ThemeMode.DARK,
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

class StringsTest {
    /** Every member declared on [Strings] must be overridden by [ZhStrings]. */
    @Test
    fun zhOverridesEveryString() {
        val missing = Strings::class.java.declaredMethods
            .filter { it.name !in setOf("wait", "equals", "hashCode", "toString", "getClass", "notify", "notifyAll") }
            .filter { method ->
                val impl = runCatching {
                    ZhStrings::class.java.getMethod(method.name, *method.parameterTypes)
                }.getOrNull()
                impl == null || impl.declaringClass == Strings::class.java
            }
            .map { it.name }
        assertTrue(missing.isEmpty(), "zh-Hans missing overrides for: $missing")
    }

    @Test
    fun enAndZhProduceDifferentText() {
        val en = EnStrings()
        val zh = ZhStrings()
        assertTrue(en.menuFile != zh.menuFile)
        assertTrue(en.matchesCount(5) != zh.matchesCount(5))
    }
}
