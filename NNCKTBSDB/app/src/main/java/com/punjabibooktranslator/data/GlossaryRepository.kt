package com.punjabibooktranslator.data

import androidx.room.withTransaction
import com.punjabibooktranslator.core.GlossaryMatcher
import com.punjabibooktranslator.core.GlossaryTerm
import com.punjabibooktranslator.core.GlossaryType
import com.punjabibooktranslator.core.OccResult
import com.punjabibooktranslator.core.OccRow
import com.punjabibooktranslator.core.RelevantTerms
import com.punjabibooktranslator.core.SearchText
import com.punjabibooktranslator.core.TermCandidate
import com.punjabibooktranslator.core.TermCandidates
import com.punjabibooktranslator.core.TermChecker
import com.punjabibooktranslator.core.TermMatcher
import com.punjabibooktranslator.core.TextUtils
import com.punjabibooktranslator.core.VerifyStates
import kotlinx.coroutines.flow.Flow

enum class GlossaryResult { SAVED, DUPLICATE, INVALID }

fun GlossaryEntity.toTerm() = GlossaryTerm(id, term, translation, GlossaryType.fromKey(type), explanation, approved)

class GlossaryRepository(private val db: AppDatabase) {
    private val dao get() = db.glossaryDao()

    fun observe(bookId: Long): Flow<List<GlossaryEntity>> = dao.observe(bookId)

    suspend fun add(
        bookId: Long, term: String, translation: String, type: GlossaryType, explanation: String?, approved: Boolean,
    ): GlossaryResult = db.withTransaction {
        val t = TextUtils.normalizeTranslation(term)
        val tr = TextUtils.normalizeTranslation(translation)
        if (t.isEmpty() || tr.isEmpty()) return@withTransaction GlossaryResult.INVALID
        val key = TermMatcher.key(t)
        if (dao.findByKey(bookId, key) != null) return@withTransaction GlossaryResult.DUPLICATE
        val now = System.currentTimeMillis()
        dao.insert(
            GlossaryEntity(
                bookId = bookId, term = t, termKey = key, translation = tr, type = type.name,
                explanation = explanation?.trim()?.ifEmpty { null }, approved = approved,
                previousTranslation = null, createdAt = now, updatedAt = now,
            )
        )
        GlossaryResult.SAVED
    }

    /** Changing the rendering remembers the old one, so paragraphs that still use it can be found afterwards. */
    suspend fun update(
        id: Long, term: String, translation: String, type: GlossaryType, explanation: String?, approved: Boolean,
    ): GlossaryResult = db.withTransaction {
        val old = dao.get(id) ?: return@withTransaction GlossaryResult.INVALID
        val t = TextUtils.normalizeTranslation(term)
        val tr = TextUtils.normalizeTranslation(translation)
        if (t.isEmpty() || tr.isEmpty()) return@withTransaction GlossaryResult.INVALID
        val key = TermMatcher.key(t)
        val clash = dao.findByKey(old.bookId, key)
        if (clash != null && clash.id != id) return@withTransaction GlossaryResult.DUPLICATE
        dao.update(
            old.copy(
                term = t, termKey = key, translation = tr, type = type.name,
                explanation = explanation?.trim()?.ifEmpty { null }, approved = approved,
                previousTranslation = if (tr != old.translation) old.translation else old.previousTranslation,
                updatedAt = System.currentTimeMillis(),
            )
        )
        GlossaryResult.SAVED
    }

    suspend fun delete(id: Long) = dao.delete(id)

    /** Only the approved entries that occur in [texts]. Drafts are never sent to the AI. */
    suspend fun relevantFor(bookId: Long, texts: List<String>): RelevantTerms =
        GlossaryMatcher.relevant(dao.list(bookId).map { it.toTerm() }, texts)

    /** Every paragraph whose original contains the term, with whether its saved translation uses the approved rendering. */
    suspend fun occurrences(entity: GlossaryEntity): List<OccResult> {
        val rows = db.readerDao().search(entity.bookId, SearchText.pattern(entity.term), 2000)
            .map { OccRow(it.blockId, it.paraId, it.pdfPage, it.originalText, it.translatedText) }
        return TermChecker.check(rows, entity.term, entity.translation, entity.previousTranslation)
    }

    /** Marks paragraphs that do not use the approved rendering as needing review. The text itself is never touched. */
    suspend fun flagForReview(results: List<OccResult>): Int = db.withTransaction {
        val reader = db.readerDao()
        val targets = results.filter { TermChecker.needsReview(it.state) }
        val current = targets.map { it.row.blockId }.chunked(500).flatMap { reader.translationsFor(it) }.associateBy { it.textBlockId }
        var n = 0
        for (r in targets) {
            val tr = current[r.row.blockId] ?: continue
            val next = VerifyStates.flagged(tr.verifyState)
            if (next != tr.verifyState) { reader.setVerifyState(r.row.blockId, next); n++ }
        }
        n
    }

    /** Recurring capitalised names found in the book's Latin-script text. Suggestions only. */
    suspend fun suggest(bookId: Long): List<TermCandidate> {
        val existing = dao.list(bookId).map { it.termKey }.toSet()
        val texts = db.readerDao().allOriginalTexts(bookId)
        return TermCandidates.find(texts, existing)
    }
}
