package top.z7workbench.loggi.os

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

// Per-user `.desktop` file for "Open with Loggi" on Linux. The jpackage
// installer ships a system-wide `.desktop` already, but it requires the file
// manager to pick up a freshly-installed MIME cache; writing a per-user copy
// in `XDG_DATA_HOME/applications/` makes the verb visible in every file
// manager without root or a relogin.
//
// MimeType is intentionally broad: text slash star, plus octet-stream and
// a few common log MIME types so the verb shows up for any file extension
// (PLAN.md §3 M11).
internal object LinuxFileAssociation {
    private const val DESKTOP_FILE = "loggi-user.desktop"

    fun ensure(verbDisplayName: String): Boolean {
        val launcher = LauncherPath.resolve() ?: return false
        val target = applicationsDir().resolve(DESKTOP_FILE)
        val contents = buildString {
            appendLine("[Desktop Entry]")
            appendLine("Type=Application")
            appendLine("Name=$verbDisplayName")
            appendLine("Name[zh_CN]=使用 Loggi 打开")
            appendLine("Name[zh_TW]=使用 Loggi 開啟")
            appendLine("Name[fr]=Ouvrir avec Loggi")
            appendLine("Name[de]=Mit Loggi öffnen")
            appendLine("Name[ru]=Открыть с помощью Loggi")
            appendLine("GenericName=Log Viewer")
            appendLine("GenericName[zh_CN]=日志查看器")
            appendLine("GenericName[zh_TW]=日誌檢視器")
            appendLine("GenericName[fr]=Visionneur de journaux")
            appendLine("GenericName[de]=Log-Viewer")
            appendLine("GenericName[ru]=Просмотрщик журналов")
            appendLine("Comment=Open this file with Loggi")
            appendLine("Comment[zh_CN]=使用 Loggi 打开此文件")
            appendLine("Comment[zh_TW]=使用 Loggi 開啟此檔案")
            appendLine("Comment[fr]=Ouvrir ce fichier avec Loggi")
            appendLine("Comment[de]=Diese Datei mit Loggi öffnen")
            appendLine("Comment[ru]=Открыть этот файл с помощью Loggi")
            appendLine("Exec=\"$launcher\" %f")
            appendLine("Terminal=false")
            appendLine("Categories=Utility;TextEditor;")
            appendLine("MimeType=text/plain;text/x-log;application/x-log;text/*;application/octet-stream;")
            appendLine("StartupNotify=true")
        }
        return runCatching {
            Files.createDirectories(target.parent)
            // Atomic write: tmp + move, so a half-written file never breaks
            // the file manager's MIME cache.
            val tmp = target.resolveSibling("${DESKTOP_FILE}.tmp")
            Files.writeString(tmp, contents, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)
            Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
        }.isSuccess
    }

    fun remove(): Boolean = runCatching {
        Files.deleteIfExists(applicationsDir().resolve(DESKTOP_FILE))
    }.let { it.isSuccess || it.getOrNull() == true }

    private fun applicationsDir(): Path {
        val xdg = System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() }
        val base = xdg?.let { Path.of(it) } ?: Path.of(System.getProperty("user.home"), ".local", "share")
        return base.resolve("applications")
    }
}
