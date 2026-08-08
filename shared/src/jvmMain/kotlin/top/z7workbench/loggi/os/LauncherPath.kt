package top.z7workbench.loggi.os

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Resolves the absolute path of the running launcher executable, used to
 * populate the Windows shell verb command (`"…\Loggi.exe" "%1"`). Falls back
 * to `java` for `gradlew :desktopApp:run` (dev mode).
 */
internal object LauncherPath {
    /** Best-effort absolute path to the launcher binary, or `null` if unknown. */
    fun resolve(): String? {
        // jpackage sets this to the launcher EXE inside the installed app image.
        System.getProperty("jpackage.app-path")?.takeIf { it.isNotBlank() }?.let { return it }

        // Fallback: derive from the running process.
        ProcessHandle.current().info().command().orElse(null)?.let { return it }

        // Last resort: derive from the JAR's location.
        return runCatching {
            val src = object {}.javaClass.protectionDomain.codeSource ?: return@runCatching null
            val loc = src.location ?: return@runCatching null
            val path = loc.toURI().let { Paths.get(it) }
            path.toAbsolutePath().toString()
        }.getOrNull()
    }

    /** Locate `reg.exe` on Windows; null on other OSes. */
    fun regExe(): String? {
        if (OsType.current() != OsType.WINDOWS) return null
        val candidates = listOf(
            "C:\\Windows\\System32\\reg.exe",
            "C:\\Windows\\reg.exe",
        )
        return candidates.firstOrNull { Files.exists(Path.of(it)) }
    }
}
