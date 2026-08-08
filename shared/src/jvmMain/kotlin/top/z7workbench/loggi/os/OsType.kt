package top.z7workbench.loggi.os

/** Coarse host classification for OS integration paths (M11). */
enum class OsType {
    WINDOWS,
    MAC,
    LINUX,
    OTHER;

    companion object {
        fun current(): OsType {
            val os = System.getProperty("os.name")?.lowercase() ?: return OTHER
            return when {
                os.contains("win") -> WINDOWS
                os.contains("mac") || os.contains("darwin") -> MAC
                os.contains("nix") || os.contains("nux") || os.contains("aix") -> LINUX
                else -> OTHER
            }
        }
    }
}
