package top.z7workbench.loggi.os

/**
 * Top-level OS integration entry point. macOS needs no runtime work — its
 * `CFBundleDocumentTypes` is baked into the packaged `Info.plist` by
 * jpackage. Linux and Windows register on first run and re-register on
 * locale change so the verb display name stays in sync.
 *
 * All methods are best-effort: failures are logged at debug level and
 * swallowed. The app must launch even when the verb cannot be registered
 * (sandbox, locked-down user account, etc.).
 */
object FileAssociation {
    /** Register (or update) the OS-level "Open with Loggi" verb. */
    fun ensure(verbDisplayName: String) {
        runCatching {
            when (OsType.current()) {
                OsType.WINDOWS -> WindowsFileAssociation.register(verbDisplayName)
                OsType.LINUX -> LinuxFileAssociation.ensure(verbDisplayName)
                OsType.MAC, OsType.OTHER -> true
            }
        }
    }

    /** Remove the OS-level verb (called from uninstall hooks when present). */
    fun remove() {
        runCatching {
            when (OsType.current()) {
                OsType.WINDOWS -> WindowsFileAssociation.unregister()
                OsType.LINUX -> LinuxFileAssociation.remove()
                OsType.MAC, OsType.OTHER -> true
            }
        }
    }
}
