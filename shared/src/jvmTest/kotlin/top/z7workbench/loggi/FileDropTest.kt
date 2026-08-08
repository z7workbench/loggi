package top.z7workbench.loggi

import org.junit.Test
import java.io.File
import kotlin.test.assertEquals

/**
 * File drag & drop (M12): the AWT DropTarget hands us the transferred
 * `java.io.File` list; only regular files become tabs. Directories and
 * non-file entries are skipped, and nothing may be thrown for foreign data.
 */
class FileDropTest {
    private val tmp = File(System.getProperty("java.io.tmpdir"), "loggi-filedrop-test-${System.nanoTime()}")

    private fun setup(): Pair<File, File> {
        val dir = File(tmp, "sub").apply { mkdirs() }
        val file = File(tmp, "a.log").apply { writeText("hello") }
        return dir to file
    }

    @Test
    fun keepsRegularFilesOnly() {
        val (dir, file) = setup()
        try {
            assertEquals(listOf(file.absolutePath), droppedFilePaths(listOf(dir, file)))
        } finally {
            tmp.deleteRecursively()
        }
    }

    @Test
    fun emptyForNoFiles() {
        val (dir, file) = setup()
        try {
            assertEquals(emptyList<String>(), droppedFilePaths(listOf(dir)))
            assertEquals(emptyList<String>(), droppedFilePaths(emptyList<File>()))
            assertEquals(emptyList<String>(), droppedFilePaths(listOf("not a file")))
            assertEquals(emptyList<String>(), droppedFilePaths(null))
            assertEquals(emptyList<String>(), droppedFilePaths(file.absolutePath))
        } finally {
            tmp.deleteRecursively()
        }
    }
}
