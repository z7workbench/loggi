package top.z7workbench.loggi

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import top.z7workbench.loggi.engine.EngineFile
import top.z7workbench.loggi.engine.LogEncoding

/**
 * Smoke test of the full JNI bridge contract against a real file: open/info,
 * chunked reads, literal + regex search with streaming poll, cancel,
 * matchInLine, close. Requires the cdylib (Gradle wires `cargoBuildJni` into
 * the test runtime via the resources chain).
 */
class BridgeSmokeTest {
    private lateinit var dir: Path
    private lateinit var file: Path
    private var engine: EngineFile? = null

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("loggi-test-")
        file = dir.resolve("sample.log")
        val sb = StringBuilder()
        for (i in 0 until 5_000) {
            if (i % 100 == 0) {
                sb.append("2026-08-08 00:00:00 ERROR boom line $i\n")
            } else {
                sb.append("2026-08-08 00:00:00 INFO line $i hello\n")
            }
        }
        Files.writeString(file, sb.toString())
    }

    @AfterTest
    fun tearDown() {
        engine?.close()
        dir.toFile().deleteRecursively()
    }

    private fun open(): EngineFile {
        val e = EngineFile.open(file.toString())
        engine = e
        return e
    }

    @Test
    fun openInfoAndReadLines() {
        val e = open()
        assertEquals(5_000, e.info.lineCount)
        assertEquals(LogEncoding.UTF8, e.info.encoding)
        assertTrue(e.info.sizeBytes > 0)

        val chunk = e.readLines(0, 100)
        assertEquals(0, chunk.startLine)
        assertEquals(100, chunk.endLine)
        assertEquals("2026-08-08 00:00:00 ERROR boom line 0", e.decodeLine(chunk, 0))
        assertEquals("2026-08-08 00:00:00 INFO line 99 hello", e.decodeLine(chunk, 99))

        val tail = e.readLines(4_999, 10)
        assertEquals(5_000, tail.endLine)
        assertEquals("2026-08-08 00:00:00 INFO line 4999 hello", e.decodeLine(tail, 4_999))
    }

    @Test
    fun literalSearchStreamsAllMatches() {
        val e = open()
        val sid = e.searchStart("ERROR", ignoreCase = false, useRegex = false)
        val out = LongArray(1024)
        val found = ArrayList<Long>()
        var done = false
        var reported = 0L
        while (!done) {
            val poll = e.searchPoll(sid, out)
            for (i in 0 until poll.returned) found.add(out[i])
            reported = poll.matchesFound
            done = poll.done
        }
        assertEquals(50, found.size)
        assertEquals(50, reported)
        assertEquals((0L..4_900L step 100).toList(), found.sorted())
    }

    @Test
    fun regexAndIgnoreCaseSearch() {
        val e = open()
        val sid = e.searchStart("boom line 49\\d\\d", ignoreCase = false, useRegex = true)
        val out = LongArray(16)
        val found = ArrayList<Long>()
        var done = false
        while (!done) {
            val poll = e.searchPoll(sid, out)
            for (i in 0 until poll.returned) found.add(out[i])
            done = poll.done
        }
        assertEquals(listOf(4_900L), found.sorted())

        val sid2 = e.searchStart("error boom", ignoreCase = true, useRegex = false)
        var done2 = false
        var count = 0
        while (!done2) {
            val poll = e.searchPoll(sid2, out)
            count += poll.returned
            done2 = poll.done
        }
        assertEquals(50, count)
    }

    @Test
    fun cancelStopsSearchPromptly() {
        val e = open()
        val sid = e.searchStart("line", ignoreCase = false, useRegex = false)
        e.searchCancel(sid)
        // After cancel the session is gone; polling reports done without lines.
        val poll = e.searchPoll(sid, LongArray(16))
        assertTrue(poll.done)
    }

    @Test
    fun matchInLinePositions() {
        val e = open()
        val line = "xx ERROR yy ERROR".toByteArray(Charsets.UTF_8)
        val pos = e.matchPositions("ERROR", ignoreCase = false, useRegex = false, line)
        assertEquals(intArrayOf(3, 8, 12, 17).toList(), pos.toList())
    }

    @Test
    fun refreshWithoutChangeIsFalse() {
        val e = open()
        assertFalse(e.refresh())
    }
}
