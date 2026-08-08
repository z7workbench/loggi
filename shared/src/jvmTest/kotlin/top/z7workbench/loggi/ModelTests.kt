package top.z7workbench.loggi

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import top.z7workbench.loggi.model.ResultsModel

class ResultsModelTest {
    @Test
    fun batchesMergeSortedEvenWhenOutOfOrder() {
        val m = ResultsModel()
        m.addMatchBatch(longArrayOf(10, 20, 30), 3)
        m.addMatchBatch(longArrayOf(5, 15), 2)
        m.addMatchBatch(longArrayOf(1, 2, 3, 100), 4)
        assertEquals(9, m.size)
        assertEquals(listOf(1L, 2, 3, 5, 10, 15, 20, 30, 100), (0 until m.size).map { m.lineAt(it) })
    }

    @Test
    fun pinsMergeWithMatchesAndDedup() {
        val m = ResultsModel()
        m.addMatchBatch(longArrayOf(2, 4, 6, 8), 4)
        m.setPinned(5, true)
        m.setPinned(4, true) // also a match → shown once
        m.setPinned(1, true)
        assertEquals(3, m.pinCount)
        assertEquals(6, m.size)
        assertEquals(listOf(1L, 2, 4, 5, 6, 8), (0 until m.size).map { m.lineAt(it) })
        assertTrue(m.isPinned(5))
        m.setPinned(4, false)
        assertEquals(2, m.pinCount)
        assertEquals(listOf(1L, 2, 4, 5, 6, 8), (0 until m.size).map { m.lineAt(it) })
    }

    @Test
    fun pinsSurviveMatchChanges() {
        val m = ResultsModel()
        m.addMatchBatch(longArrayOf(7, 9), 2)
        m.setPinned(3, true)
        m.clearMatches()
        assertEquals(1, m.size)
        assertEquals(3L, m.lineAt(0))
        m.addMatchBatch(longArrayOf(1, 2, 4), 3)
        assertEquals(listOf(1L, 2, 3, 4), (0 until m.size).map { m.lineAt(it) })
    }

    @Test
    fun pinWithoutMatchesAppearsImmediately() {
        // M8.5: a pin must become visible (union size + version bump) with no search run.
        val m = ResultsModel()
        val v0 = m.version
        m.setPinned(42, true)
        assertTrue(m.version > v0)
        assertEquals(1, m.size)
        assertEquals(42L, m.lineAt(0))
        assertEquals(1, m.pinCount)
    }

    @Test
    fun clearPinsDropsAllPins() {
        val m = ResultsModel()
        m.addMatchBatch(longArrayOf(2, 4), 2)
        m.setPinned(1, true)
        m.setPinned(4, true)
        assertEquals(2, m.pinCount)
        m.clearPins()
        assertEquals(0, m.pinCount)
        assertEquals(2, m.size)
        assertEquals(listOf(2L, 4L), (0 until m.size).map { m.lineAt(it) })
    }

    @Test
    fun restorePinsReplacesAndCountsDups() {
        val m = ResultsModel()
        m.addMatchBatch(longArrayOf(1, 2, 3), 3)
        m.restorePins(listOf(3L, 2L, 9L, 9L))
        assertEquals(3, m.pinCount)
        assertEquals(4, m.size) // 2 and 3 dedup
        assertEquals(listOf(1L, 2, 3, 9), (0 until m.size).map { m.lineAt(it) })
    }

    @Test
    fun sampleMatchesStrides() {
        val m = ResultsModel()
        val lines = LongArray(10_000) { it.toLong() * 2 }
        m.addMatchBatch(lines, lines.size)
        val sample = m.sampleMatches(100)
        assertTrue(sample.size in 99..101)
        assertEquals(0L, sample.first())
        assertTrue(sample.last() <= 19_998L)
        assertEquals(10_000, m.size)
    }

    @Test
    fun emptyModel() {
        val m = ResultsModel()
        assertEquals(0, m.size)
        assertFalse(m.isPinned(42))
    }
}

class TextTransformsTest {
    @Test
    fun expandTabsRemapsSpans() {
        val (text, spans) = top.z7workbench.loggi.model.expandTabsRemap(
            "a\tb",
            4,
            listOf(top.z7workbench.loggi.model.LineSpan(2, 3)),
        )
        assertEquals("a   b", text)
        assertEquals(top.z7workbench.loggi.model.LineSpan(4, 5), spans.single())
    }

    @Test
    fun expandTabsDisabledKeepsIdentity() {
        val (text, spans) = top.z7workbench.loggi.model.expandTabsRemap(
            "a\tb",
            0,
            listOf(top.z7workbench.loggi.model.LineSpan(1, 2)),
        )
        assertEquals("a\tb", text)
        assertEquals(top.z7workbench.loggi.model.LineSpan(1, 2), spans.single())
    }

    @Test
    fun byteSpansOnAsciiAreIdentity() {
        val spans = top.z7workbench.loggi.model.byteSpansToCharSpans("hello error", intArrayOf(6, 11))
        assertEquals(listOf(top.z7workbench.loggi.model.LineSpan(6, 11)), spans)
    }

    @Test
    fun byteSpansRespectMultibyteChars() {
        // "héllo": é is 2 UTF-8 bytes; match "llo" at byte 3..6 → chars 2..5.
        val spans = top.z7workbench.loggi.model.byteSpansToCharSpans("héllo", intArrayOf(3, 6))
        assertEquals(listOf(top.z7workbench.loggi.model.LineSpan(2, 5)), spans)
    }

    @Test
    fun byteSpansRespectSurrogatePairs() {
        // "😀x": 😀 is 4 UTF-8 bytes / 2 UTF-16 chars; "x" at byte 4..5 → char 2..3.
        val spans = top.z7workbench.loggi.model.byteSpansToCharSpans("😀x", intArrayOf(4, 5))
        assertEquals(listOf(top.z7workbench.loggi.model.LineSpan(2, 3)), spans)
    }
}
