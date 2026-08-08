package top.z7workbench.loggi.os

import java.util.concurrent.TimeUnit

/**
 * Per-user "Open with Loggi" shell verb for `*` (any file extension).
 *
 * Writes to `HKCU\Software\Classes\*\shell\Loggi`. HKCU needs no admin, which
 * keeps the install (jpackage MSI) free of per-user custom-action fragility
 * (PLAN.md §3 M11).
 *
 * The verb's `MUIVerb` (display name) is set to the supplied localized
 * string, so re-running with a different locale updates what the Explorer
 * context menu shows without requiring a reinstall.
 */
internal object WindowsFileAssociation {
    private const val VERB_KEY = "HKCU\\Software\\Classes\\*\\shell\\Loggi"
    private const val COMMAND_KEY = "HKCU\\Software\\Classes\\*\\shell\\Loggi\\command"

    /**
     * Register (or update) the verb. Returns `true` on success.
     *
     * @param verbDisplayName localized verb name (EN "Open with Loggi",
     *   zh-Hans "使用 Loggi 打开")
     */
    fun register(verbDisplayName: String): Boolean {
        val reg = LauncherPath.regExe() ?: return false
        val launcher = LauncherPath.resolve() ?: return false
        // Quote the launcher path so spaces are tolerated.
        val command = "\"$launcher\" \"%1\""
        val commands = listOf(
            // Verb + display name.
            listOf("add", VERB_KEY, "/ve", "/d", verbDisplayName, "/f"),
            // Icon: reuse the launcher's own icon.
            listOf("add", VERB_KEY, "/v", "Icon", "/d", launcher, "/f"),
            // Command.
            listOf("add", COMMAND_KEY, "/ve", "/d", command, "/f"),
        )
        return commands.all { runReg(reg, it) }
    }

    /** Remove the verb (used by uninstall cleanup). */
    fun unregister(): Boolean {
        val reg = LauncherPath.regExe() ?: return false
        return runReg(reg, listOf("delete", "HKCU\\Software\\Classes\\*\\shell\\Loggi", "/f"))
    }

    private fun runReg(reg: String, args: List<String>): Boolean = runCatching {
        val pb = ProcessBuilder(listOf(reg) + args)
            .redirectErrorStream(true)
        val p = pb.start()
        if (!p.waitFor(10, TimeUnit.SECONDS)) {
            p.destroyForcibly()
            return@runCatching false
        }
        p.exitValue() == 0
    }.getOrDefault(false)
}
