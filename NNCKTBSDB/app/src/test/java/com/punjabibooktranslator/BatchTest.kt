package com.punjabibooktranslator

import com.punjabibooktranslator.core.BatchPlanner
import com.punjabibooktranslator.core.PlanBlock
import com.punjabibooktranslator.core.SizeEstimator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BatchTest {
    /** [perPage] paragraphs on each of [pages] pages, ids 1.., order 0.. */
    private fun pdf(pages: Int, perPage: Int, chars: Int = 100, translatedPages: Set<Int> = emptySet()): List<PlanBlock> {
        val out = ArrayList<PlanBlock>(); var n = 0
        for (p in 1..pages) for (i in 0 until perPage) {
            out.add(PlanBlock(n + 1L, "P%04d".format(n + 1), n, p, 1, "Doc", chars, p in translatedPages)); n++
        }
        return out
    }

    @Test fun pdfDefaultFivePageBatches() {
        val b = BatchPlanner.planPdf(pdf(23, 4), 5, 1, 23, "pa")
        assertEquals(5, b.size)                                   // 5+5+5+5+3 pages
        assertEquals(listOf("Pages 1–5", "Pages 6–10", "Pages 11–15", "Pages 16–20", "Pages 21–23"), b.map { it.label })
        assertEquals(20, b[0].blockIds.size); assertEquals(12, b[4].blockIds.size)
    }

    @Test fun everyParagraphInExactlyOneBatchInOrder() {
        val blocks = pdf(37, 3)
        for (size in listOf(1, 5, 10, 20, 7)) {
            val flat = BatchPlanner.planPdf(blocks, size, 1, 37, "pa").flatMap { it.blockIds }
            assertEquals(blocks.map { it.blockId }, flat)
            assertEquals(flat.size, flat.toSet().size)
        }
    }

    @Test fun batchSizesOneTenTwentyAndCustom() {
        val blocks = pdf(40, 2)
        assertEquals(40, BatchPlanner.planPdf(blocks, 1, 1, 40, "pa").size)
        assertEquals(4, BatchPlanner.planPdf(blocks, 10, 1, 40, "pa").size)
        assertEquals(2, BatchPlanner.planPdf(blocks, 20, 1, 40, "pa").size)
        assertEquals(6, BatchPlanner.planPdf(blocks, 7, 1, 40, "pa").size)       // custom size
        val range = BatchPlanner.planPdf(blocks, 5, 11, 20, "pa")                  // custom range
        assertEquals(2, range.size); assertEquals(11, range[0].firstPage); assertEquals(20, range[1].lastPage)
    }

    @Test fun translatedParagraphsAreSkippedAndEmptyWindowsDisappear() {
        val blocks = pdf(10, 2, translatedPages = (1..5).toSet())
        val b = BatchPlanner.planPdf(blocks, 5, 1, 10, "pa")
        assertEquals(1, b.size); assertEquals("Pages 6–10", b[0].label)
        assertEquals(2, BatchPlanner.planPdf(blocks, 5, 1, 10, "pa", skipTranslated = false).size)
    }

    @Test fun scannedPagesWithNoBlocksDoNotBreakWindows() {
        val blocks = pdf(10, 2).filter { it.page !in setOf(2, 3) }
        val b = BatchPlanner.planPdf(blocks, 5, 1, 10, "pa")
        assertEquals(2, b.size); assertEquals(6, b[0].blockIds.size)
    }

    @Test fun oversizedWindowSplitsIntoPartsWithoutLosingAnything() {
        val blocks = pdf(5, 10, chars = 3000)                       // 50 big paragraphs in one 5-page window
        val b = BatchPlanner.planPdf(blocks, 5, 1, 5, "pa")
        assertTrue(b.size > 1)
        assertTrue(b.all { it.partCount == b.size })
        assertEquals(blocks.map { it.blockId }, b.flatMap { it.blockIds })
        for (x in b) assertTrue(x.blockIds.size == 1 || x.chars * 1.2 / 1.4 <= SizeEstimator.MAX_OUTPUT_TOKENS_PER_REQUEST + 3000)
    }

    private fun epub(): List<PlanBlock> {
        val out = ArrayList<PlanBlock>(); var n = 0
        for (ch in 1..4) for (i in 0 until 10) { out.add(PlanBlock(n + 1L, "P%04d".format(n + 1), n, null, ch.toLong(), "Chapter $ch", 500, false)); n++ }
        return out
    }

    @Test fun epubVolumeBatchesEndOnParagraphBoundaryAndCoverEverything() {
        val blocks = epub()
        val b = BatchPlanner.planText(blocks, 2000, "pa")
        assertEquals(blocks.map { it.blockId }, b.flatMap { it.blockIds })
        for (x in b) assertTrue(x.chars <= 2000 || x.blockIds.size == 1)
    }

    @Test fun epubBatchClosesAtChapterBoundaryWhenMostlyFull() {
        val b = BatchPlanner.planText(epub(), 6000, "pa")            // chapter = 5000 chars, 83% of budget
        assertEquals(4, b.size)
        assertTrue(b[0].label.startsWith("Chapter 1"))
        assertTrue(b.all { it.blockIds.size == 10 })
    }

    @Test fun epubSmallChapterIsMergedWithNext() {
        val blocks = epub().map { if (it.chapterId == 2L) it.copy(chars = 50) else it }
        val b = BatchPlanner.planText(blocks, 10_000, "pa")
        assertTrue(b[0].label.contains("→"))                           // spans chapters 1 -> 2
    }

    @Test fun fromOrderAndSkipTranslated() {
        val blocks = epub().mapIndexed { i, p -> if (i < 5) p.copy(translated = true) else p }
        val b = BatchPlanner.planText(blocks, 100_000, "pa", fromOrder = 3)
        assertEquals(35, b.flatMap { it.blockIds }.size)               // orders 5..39
        assertFalse(b.flatMap { it.blockIds }.contains(1L))
    }

    @Test fun realisticFivePageWindowMayBeSplitIntoSeveralPrompts() {
        // ~1,800 characters per printed page, 6 paragraphs per page: a typical novel page.
        val b = BatchPlanner.planPdf(pdf(5, 6, chars = 300), 5, 1, 5, "pa")
        assertTrue(b.size >= 2)                                          // conservative limits split it
        assertTrue(b.all { it.partCount == b.size })
        assertEquals((1..30).map { it.toLong() }, b.flatMap { it.blockIds })   // nothing lost or reordered
        // with a much smaller page the same window is a single prompt
        assertEquals(1, BatchPlanner.planPdf(pdf(5, 6, chars = 100), 5, 1, 5, "pa").size)
    }
}
