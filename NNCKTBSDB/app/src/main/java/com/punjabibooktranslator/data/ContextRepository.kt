package com.punjabibooktranslator.data

import com.punjabibooktranslator.core.ExampleLimiter
import com.punjabibooktranslator.core.SearchText
import com.punjabibooktranslator.core.TextUtils
import kotlinx.coroutines.flow.Flow

/** Book-wide search, chapter summaries, and approved examples for prompts. */
class ContextRepository(private val db: AppDatabase) {
    suspend fun search(bookId: Long, query: String, limit: Int = 200): List<SearchRow> {
        val q = query.trim()
        if (q.length < 2) return emptyList()
        return db.readerDao().search(bookId, SearchText.pattern(q), limit)
    }

    fun observeSummaries(bookId: Long): Flow<List<ChapterSummaryRow>> = db.readerDao().observeChapterSummaries(bookId)

    /** Saving blank text clears the summary. */
    suspend fun setSummary(chapterId: Long, text: String) {
        db.readerDao().setChapterSummary(chapterId, TextUtils.normalizeTranslation(text).ifEmpty { null })
    }

    suspend fun chapterBlocks(bookId: Long, chapterId: Long): List<BlockRow> = db.readerDao().blocksByChapter(bookId, chapterId)

    /** Up to a few user-approved or checked translations that contain one of the given terms (names/terms only, never copied). */
    suspend fun approvedExamples(bookId: Long, terms: List<String>, maxCount: Int = 3): List<Pair<String, String>> {
        val found = ArrayList<Pair<String, String>>()
        for (t in terms.take(5)) {
            db.readerDao().approvedContaining(bookId, SearchText.pattern(t), 3).forEach { r ->
                r.translatedText?.let { found.add(r.originalText to it) }
            }
            if (found.size >= maxCount * 2) break
        }
        return ExampleLimiter.pick(found, maxCount)
    }
}
