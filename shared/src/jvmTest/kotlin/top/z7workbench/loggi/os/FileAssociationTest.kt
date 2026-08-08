package top.z7workbench.loggi.os

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class OsTypeTest {
    @Test
    fun classifiesCurrentOs() {
        val t = OsType.current()
        // Anything in the spec is acceptable; the point is the host is
        // classifiable and a valid member of the enum.
        assertTrue(t in OsType.entries.toTypedArray())
    }
}

class LauncherPathTest {
    @Test
    fun resolvesSomePath() {
        // The resolver falls back to the running JVM, the process handle, or
        // the JAR's location — any of these is acceptable. The test is
        // "does not blow up and returns something non-empty", which is the
        // only contract callers rely on.
        val p = LauncherPath.resolve()
        assertNotNull(p)
        assertTrue(p.isNotBlank())
    }
}

class FileAssociationTest {
    @Test
    fun ensureIsBestEffort() {
        // FileAssociation.ensure must never throw. On every OS it returns
        // quickly: macOS is a no-op; Linux writes a per-user .desktop file
        // (which the CI sandbox may or may not allow); Windows tries reg.exe
        // (only present on Windows hosts). The "must not throw" contract is
        // what the app relies on for first-run locale-aware registration.
        FileAssociation.ensure("Open with Loggi")
        // Cleanup the Linux .desktop if it was written, so the test is
        // hermetic on developer machines.
        if (OsType.current() == OsType.LINUX) {
            FileAssociation.remove()
        }
    }

    @Test
    fun removeIsBestEffort() {
        FileAssociation.remove()
    }

    @Test
    fun stringsExposeOpenWithLoggi() {
        // Sanity-check the verb display name strings are wired through both
        // languages (the test would have failed to compile otherwise, this
        // is just a runtime smoke).
        val en = top.z7workbench.loggi.i18n.EnStrings()
        val zh = top.z7workbench.loggi.i18n.ZhStrings()
        assertEquals("Open with Loggi", en.openWithLoggi)
        assertEquals("使用 Loggi 打开", zh.openWithLoggi)
    }
}
