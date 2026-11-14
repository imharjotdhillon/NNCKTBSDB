package com.punjabibooktranslator.core

/** Lightweight view of one translatable paragraph: enough to plan batches without loading text. */
data class PlanBlock(
    val blockId: Long,
    val paraId: String,
    val order: Int,
    val page: Int?,
    val chapterId: Long?,
    val chapterTitle: String?,
    val chars: Int,
    val translated: Boolean,
)

data class PlannedBatch(
    val label: String,
    val blockIds: List<Long>,
    val firstOrder: Int,
    val lastOrder: Int,
    val firstPage: Int?,
    val lastPage: Int?,
    val chars: Int,
    val partIndex: Int,
    val partCount: Int,
)

object BatchPlanner {
    private fun finish(groups: List<List<PlanBlock>>, targetLang: String, label: (List<PlanBlock>) -> String): List<PlannedBatch> {
        val out = ArrayList<PlannedBatch>()
        for (g in groups) {
            // A window too big for one AI reply becomes several parts; nothing is dropped or truncated.
            val parts = SizeEstimator.splitBy(g, { it.chars }, targetLang)
            parts.forEachIndexed { i, p ->
                val pages = p.mapNotNull { it.page }
                out.add(
                    PlannedBatch(
                        label(p), p.map { it.blockId }, p.first().order, p.last().order,
                        pages.minOrNull(), pages.maxOrNull(), p.sumOf { it.chars }, i, parts.size,
                    )
                )
            }
        }
        return out
    }

    private fun pagesLabel(p: List<PlanBlock>): String {
        val pages = p.mapNotNull { it.page }
        val a = pages.min()
        val b = pages.max()
        return if (a == b) "Page $a" else "Pages $a–$b"
    }

    /**
     * PDF batches: fixed windows of [pagesPerBatch] pages counted from [startPage] (5 is the UI default).
     * Windows with nothing left to translate are skipped; already-translated paragraphs are left out when
     * [skipTranslated] is true. Every eligible paragraph lands in exactly one batch, in reading order.
     */
    fun planPdf(
        blocks: List<PlanBlock>,
        pagesPerBatch: Int,
        startPage: Int,
        endPage: Int,
        targetLang: String,
        skipTranslated: Boolean = true,
    ): List<PlannedBatch> {
        require(pagesPerBatch >= 1) { "pagesPerBatch must be at least 1" }
        val eligible = blocks
            .filter { it.page != null && it.page in startPage..endPage && (!skipTranslated || !it.translated) }
            .sortedBy { it.order }
        val groups = eligible.groupBy { (it.page!! - startPage) / pagesPerBatch }.toSortedMap().values.toList()
        return finish(groups, targetLang, ::pagesLabel)
    }

    /**
     * EPUB / text-volume batches: fills a batch to about [charBudget] characters, always ending on a paragraph
     * boundary. If the next paragraph starts a new chapter and the batch is already at least 60% full, the batch is
     * closed first so batches tend to follow chapter boundaries.
     */
    fun planText(
        blocks: List<PlanBlock>,
        charBudget: Int,
        targetLang: String,
        skipTranslated: Boolean = true,
        fromOrder: Int = 0,
        toOrder: Int = Int.MAX_VALUE,
    ): List<PlannedBatch> {
        require(charBudget >= 1) { "charBudget must be positive" }
        val eligible = blocks
            .filter { it.order in fromOrder..toOrder && (!skipTranslated || !it.translated) }
            .sortedBy { it.order }
        val groups = ArrayList<List<PlanBlock>>()
        var cur = ArrayList<PlanBlock>()
        var chars = 0
        for (b in eligible) {
            if (cur.isNotEmpty()) {
                val newChapter = b.chapterId != cur.last().chapterId
                if (chars + b.chars > charBudget || (newChapter && chars >= charBudget * 0.6)) {
                    groups.add(cur); cur = ArrayList(); chars = 0
                }
            }
            cur.add(b); chars += b.chars
        }
        if (cur.isNotEmpty()) groups.add(cur)
        return finish(groups, targetLang) { p ->
            val titles = p.mapNotNull { it.chapterTitle }.distinct()
            val range = "${p.first().paraId}–${p.last().paraId}"
            when {
                titles.isEmpty() -> "Paragraphs $range"
                titles.size == 1 -> "${titles.first()} ($range)"
                else -> "${titles.first()} → ${titles.last()} ($range)"
            }
        }
    }
}

/** Per page (PDF) or chapter (EPUB) aggregate of translatable paragraphs. */
data class UnitAgg(
    val unitId: Long,
    val label: String,
    val firstOrder: Int,
    val total: Int,
    /** Paragraphs with any saved translation (includes needs-review and verified). */
    val translated: Int,
    val needsReview: Int,
    val verified: Int,
)

enum class UnitState { UNTRANSLATED, PARTIAL, TRANSLATED }

data class BookProgress(
    val totalBlocks: Int,
    val translatedBlocks: Int,
    val needsReviewBlocks: Int,
    val verifiedBlocks: Int,
    val missingBlocks: Int,
    val totalUnits: Int,
    val fullUnits: Int,
    val partialUnits: Int,
    val untranslatedUnits: Int,
    val unitsAwaitingReview: Int,
    val unitsFullyVerified: Int,
    /** Floor of translated/total; shows 100 only when every paragraph is translated. */
    val percent: Int,
    val incompleteUnits: List<UnitAgg>,
    val reviewUnits: List<UnitAgg>,
)

object ProgressCalc {
    fun percent(translated: Int, total: Int): Int = when {
        total <= 0 -> 0
        translated >= total -> 100
        else -> (translated.toLong() * 100 / total).toInt().coerceAtMost(99)
    }

    fun state(u: UnitAgg): UnitState = when {
        u.translated <= 0 -> UnitState.UNTRANSLATED
        u.translated >= u.total -> UnitState.TRANSLATED
        else -> UnitState.PARTIAL
    }

    fun compute(units: List<UnitAgg>): BookProgress {
        val total = units.sumOf { it.total }
        val translated = units.sumOf { it.translated }
        return BookProgress(
            totalBlocks = total,
            translatedBlocks = translated,
            needsReviewBlocks = units.sumOf { it.needsReview },
            verifiedBlocks = units.sumOf { it.verified },
            missingBlocks = total - translated,
            totalUnits = units.size,
            fullUnits = units.count { state(it) == UnitState.TRANSLATED },
            partialUnits = units.count { state(it) == UnitState.PARTIAL },
            untranslatedUnits = units.count { state(it) == UnitState.UNTRANSLATED },
            unitsAwaitingReview = units.count { it.needsReview > 0 },
            unitsFullyVerified = units.count { it.total > 0 && it.verified >= it.total },
            percent = percent(translated, total),
            incompleteUnits = units.filter { state(it) != UnitState.TRANSLATED }.sortedBy { it.firstOrder },
            reviewUnits = units.filter { it.needsReview > 0 }.sortedBy { it.firstOrder },
        )
    }
}
