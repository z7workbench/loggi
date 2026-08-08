package top.z7workbench.loggi.jni

import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import kotlin.io.path.absolutePathString
import kotlin.io.path.exists

/**
 * JNI contract implemented by `crates/engine-jni` (thin marshalling only — no
 * engine logic crosses this boundary).
 *
 * All long operations (indexing, searching) run on native threads and are
 * polled from Kotlin; no native thread ever calls back into the JVM, so no
 * GlobalRef lifecycle issues can arise. Handles and search ids are plain
 * primitives.
 */
internal class LoggiBridge private constructor() {
    companion object {
        init {
            NativeLoader.load()
        }

        /** Engine version string. */
        @JvmStatic
        external fun version(): String

        /** Open a file and start indexing on a native thread; returns the handle id. */
        @JvmStatic
        external fun openFile(path: String): Long

        /** Poll indexing progress: `[done, total, ready]`. */
        @JvmStatic
        external fun indexProgress(id: Long): LongArray

        /** Indexing failure message, or "" when ready / not failed. */
        @JvmStatic
        external fun indexError(id: Long): String

        /**
         * File info: `[size, lineCount, maxLineLen, indexBytes, indexTimeMillis,
         * encoding(0..5), lineFeedWidth]`.
         */
        @JvmStatic
        external fun fileInfo(id: Long): LongArray

        /** Charset name for non-Unicode encodings ("" for the Unicode family). */
        @JvmStatic
        external fun encodingName(id: Long): String

        /** Re-check the file for append/truncate; true when the index changed. */
        @JvmStatic
        external fun refresh(id: Long): Boolean

        /** Close a file: cancel and join every in-flight search, drop the engine. */
        @JvmStatic
        external fun closeFile(id: Long)

        /**
         * Read lines `[start, start+count)` into the direct [buf]; fills [offsets]
         * (size count+1) with per-line content-start offsets relative to the buffer
         * plus the final content end. Returns `[endLine, byteStart, byteLen]`.
         * `endLine < start+count` when the engine byte budget (8 MiB) was hit.
         */
        @JvmStatic
        external fun readLines(id: Long, start: Long, count: Long, buf: ByteBuffer, offsets: IntArray): LongArray

        /** Start a search on a native thread; returns a search id. */
        @JvmStatic
        external fun searchStart(id: Long, pattern: String, ignoreCase: Boolean, useRegex: Boolean): Long

        /**
         * Drain up to `out.size` matched line numbers into [out]. Returns
         * `[matchesFound, processedLines, totalLines, done, cancelled, linesReturned]`.
         */
        @JvmStatic
        external fun searchPoll(id: Long, searchId: Long, out: LongArray): LongArray

        /** Cancel a search: set the stop flag and join the native search thread. */
        @JvmStatic
        external fun searchCancel(id: Long, searchId: Long)

        /**
         * Match positions of [pattern] within one decoded (UTF-8) [line];
         * returns flattened `[start0, end0, start1, end1, ...]` UTF-8 byte offsets.
         */
        @JvmStatic
        external fun matchInLine(
            id: Long,
            pattern: String,
            ignoreCase: Boolean,
            useRegex: Boolean,
            line: ByteArray,
        ): IntArray
    }
}

/** Locates and loads the `loggi_engine_jni` cdylib. */
internal object NativeLoader {
    private var loaded = false

    @Synchronized
    fun load() {
        if (loaded) return
        val failure = runCatching { loadBest() }
        if (failure.isFailure) {
            loaded = false
            throw UnsatisfiedLinkError(
                "loggi_engine_jni not found. Build it with `cargo build -p loggi-engine-jni` " +
                    "(repo root), or set -Dloggi.jni.path=/path/to/${libFileName()}. " +
                    "Cause: ${failure.exceptionOrNull()?.message}",
            )
        }
        loaded = true
    }

    private fun loadBest() {
        // 1. Explicit override.
        System.getProperty("loggi.jni.path")?.takeIf { it.isNotBlank() }?.let { p ->
            System.load(Path.of(p).absolutePathString())
            return
        }
        // 2. Development builds at the repo root (walk up from user.dir).
        devBuildPath()?.let { dev ->
            System.load(dev)
            return
        }
        // 3. Bundled jar resource: natives/<os>-<arch>/<lib>, then natives/<os>/<lib>.
        val resource = resourcePath()?.let { path ->
            NativeLoader::class.java.getResourceAsStream("/$path")?.use { input ->
                val tmp = Files.createTempDirectory("loggi-jni-")
                val target = tmp.resolve(libFileName())
                Files.copy(input, target)
                target.toFile().deleteOnExit()
                tmp.toFile().deleteOnExit()
                target.absolutePathString()
            }
        }
        if (resource != null) {
            System.load(resource)
            return
        }
        throw UnsatisfiedLinkError("no loadable loggi_engine_jni found")
    }

    private fun devBuildPath(): String? {
        var dir = Path.of(System.getProperty("user.dir")).toAbsolutePath()
        repeat(5) {
            for (profile in listOf("debug", "release")) {
                val candidate = dir.resolve("target").resolve(profile).resolve(libFileName())
                if (candidate.exists()) return candidate.absolutePathString()
            }
            dir = dir.parent ?: return null
        }
        return null
    }

    private fun resourcePath(): String? {
        val os = osPart()
        val exact = "natives/$os-${archPart()}/${libFileName()}"
        val fallback = "natives/$os/${libFileName()}"
        return when {
            NativeLoader::class.java.getResource("/$exact") != null -> exact
            NativeLoader::class.java.getResource("/$fallback") != null -> fallback
            else -> null
        }
    }

    private fun osPart(): String {
        val os = System.getProperty("os.name").lowercase(Locale.ROOT)
        return when {
            os.contains("win") -> "windows"
            os.contains("mac") -> "macos"
            else -> "linux"
        }
    }

    private fun archPart(): String {
        val arch = System.getProperty("os.arch").lowercase(Locale.ROOT)
        return when {
            arch.contains("aarch64") || arch.contains("arm") -> "aarch64"
            else -> "x86_64"
        }
    }

    private fun libFileName(): String = when (osPart()) {
        "windows" -> "loggi_engine_jni.dll"
        "macos" -> "libloggi_engine_jni.dylib"
        else -> "libloggi_engine_jni.so"
    }
}
