package com.punjabibooktranslator.data

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import com.punjabibooktranslator.core.UnitAgg
import kotlinx.coroutines.flow.Flow

private const val BLOCK_SELECT = """
    SELECT t.*, tr.translatedText AS translatedText, c.title AS chapterTitle, tr.id AS translationId, tr.verifyState AS verifyState
    FROM text_blocks t
    LEFT JOIN translations tr ON tr.textBlockId = t.id
    LEFT JOIN chapters c ON c.id = t.chapterId
"""

@Dao
interface BookDao {
    @Insert
    suspend fun insert(book: BookEntity): Long

    @Update
    suspend fun update(book: BookEntity)

    @Query("SELECT * FROM books WHERE id = :id")
    suspend fun getById(id: Long): BookEntity?

    @Query("SELECT * FROM books WHERE id = :id")
    fun observeBook(id: Long): Flow<BookEntity?>

    @Query("SELECT * FROM books WHERE importStatus = 'READY'")
    suspend fun listReady(): List<BookEntity>

    @Query("SELECT * FROM books WHERE fileHash = :hash LIMIT 1")
    suspend fun findByHash(hash: String): BookEntity?

    @Query("UPDATE books SET localPath = :path WHERE id = :id")
    suspend fun setLocalPath(id: Long, path: String)

    @Query("UPDATE books SET importStatus = :status, importError = :error WHERE id = :id")
    suspend fun setStatus(id: Long, status: String, error: String?)

    @Query("UPDATE books SET importProgress = :progress WHERE id = :id")
    suspend fun setProgress(id: Long, progress: Int)

    /** Called on app start: imports cannot survive process death, so mark them recoverable-failed. */
    @Query(
        "UPDATE books SET importStatus = 'FAILED', importError = 'Import was interrupted (app closed). Retry to continue.' " +
            "WHERE importStatus = 'IMPORTING'"
    )
    suspend fun markInterruptedImports()

    @Query("UPDATE books SET translationStyle = :style WHERE id = :id")
    suspend fun setStyle(id: Long, style: String)

    @Query("UPDATE books SET sourceLang = :source, targetLang = :target WHERE id = :id")
    suspend fun setDirection(id: Long, source: String, target: String)

    @Query("DELETE FROM books WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query(
        """
        SELECT b.*,
          (SELECT COUNT(*) FROM text_blocks t WHERE t.bookId = b.id AND t.extractionStatus = 'OK') AS totalBlocks,
          (SELECT COUNT(*) FROM translations tr JOIN text_blocks t2 ON t2.id = tr.textBlockId
             WHERE t2.bookId = b.id AND tr.status IN ('TRANSLATED','VERIFIED','NEEDS_REVIEW')) AS translatedBlocks,
          (SELECT COUNT(*) FROM text_blocks t3 WHERE t3.bookId = b.id) AS allBlocks,
          p.lastBlockIndex AS lastBlockIndex,
          p.lastOpenedAt AS lastOpenedAt
        FROM books b LEFT JOIN reading_progress p ON p.bookId = b.id
        """
    )
    fun observeLibrary(): Flow<List<LibraryRow>>
}

@Dao
interface ReaderDao {
    // ---- import ----
    @Insert
    suspend fun insertChapter(chapter: ChapterEntity): Long

    @Insert
    suspend fun insertBlocks(blocks: List<TextBlockEntity>)

    @Query("DELETE FROM text_blocks WHERE bookId = :bookId")
    suspend fun deleteBlocks(bookId: Long)

    @Query("DELETE FROM chapters WHERE bookId = :bookId")
    suspend fun deleteChapters(bookId: Long)

    @Query("SELECT originalText FROM text_blocks WHERE bookId = :bookId AND extractionStatus = 'OK' ORDER BY textOrder LIMIT 60")
    suspend fun sampleText(bookId: Long): List<String>

    // ---- reading ----
    @Query(
        """
        SELECT t.*, tr.translatedText AS translatedText, c.title AS chapterTitle, tr.id AS translationId, tr.verifyState AS verifyState
        FROM text_blocks t
        LEFT JOIN translations tr ON tr.textBlockId = t.id
        LEFT JOIN chapters c ON c.id = t.chapterId
        WHERE t.bookId = :bookId
        ORDER BY t.textOrder
        """
    )
    fun pagingSource(bookId: Long): PagingSource<Int, BlockRow>

    @Query("SELECT originalText FROM text_blocks WHERE bookId = :bookId AND extractionStatus = 'OK' ORDER BY textOrder")
    suspend fun allOriginalTexts(bookId: Long): List<String>

    @Query("SELECT COUNT(*) FROM text_blocks WHERE bookId = :bookId")
    fun observeBlockCount(bookId: Long): Flow<Int>

    @Query(
        """
        SELECT c.id AS id, c.title AS title, c.chapterOrder AS chapterOrder,
               (SELECT MIN(t.textOrder) FROM text_blocks t WHERE t.chapterId = c.id) AS startIndex
        FROM chapters c WHERE c.bookId = :bookId ORDER BY c.chapterOrder
        """
    )
    fun observeChapters(bookId: Long): Flow<List<ChapterRow>>

    @Query("SELECT MIN(textOrder) FROM text_blocks WHERE bookId = :bookId AND pdfPage = :page")
    suspend fun firstBlockIndexForPage(bookId: Long, page: Int): Int?

    // ---- translation workspace ----
    @Query("$BLOCK_SELECT WHERE t.bookId = :bookId AND t.textOrder BETWEEN :from AND :to ORDER BY t.textOrder")
    suspend fun blocksByOrder(bookId: Long, from: Int, to: Int): List<BlockRow>

    @Query("$BLOCK_SELECT WHERE t.bookId = :bookId AND t.pdfPage BETWEEN :from AND :to ORDER BY t.textOrder")
    suspend fun blocksByPages(bookId: Long, from: Int, to: Int): List<BlockRow>

    @Query("$BLOCK_SELECT WHERE t.bookId = :bookId AND t.chapterId = :chapterId ORDER BY t.textOrder")
    suspend fun blocksByChapter(bookId: Long, chapterId: Long): List<BlockRow>

    @Query("SELECT chapterId FROM text_blocks WHERE bookId = :bookId AND textOrder = :order")
    suspend fun chapterIdAt(bookId: Long, order: Int): Long?

    @Query("SELECT textOrder FROM text_blocks WHERE bookId = :bookId AND paraId = :paraId")
    suspend fun orderOfParaId(bookId: Long, paraId: String): Int?

    @Query("SELECT summary FROM chapters WHERE id = :chapterId")
    suspend fun chapterSummary(chapterId: Long): String?

    @Query("UPDATE chapters SET summary = :summary WHERE id = :chapterId")
    suspend fun setChapterSummary(chapterId: Long, summary: String?)

    @Query(
        """
        SELECT c.id AS id, c.title AS title, c.chapterOrder AS chapterOrder, c.summary AS summary,
               (SELECT COUNT(*) FROM text_blocks t WHERE t.chapterId = c.id AND t.extractionStatus = 'OK') AS paraCount
        FROM chapters c WHERE c.bookId = :bookId ORDER BY c.chapterOrder
        """
    )
    fun observeChapterSummaries(bookId: Long): Flow<List<ChapterSummaryRow>>

    // ---- export / backup ----
    @Query(
        """
        SELECT t.paraId AS paraId, t.chapterId AS chapterId, c.title AS chapterTitle, t.blockType AS blockType, t.format AS format,
               t.originalText AS original, tr.translatedText AS translation, t.extractionStatus AS extractionStatus,
               COALESCE(tr.verifyState, 'NOT_VERIFIED') AS verifyState
        FROM text_blocks t
        LEFT JOIN translations tr ON tr.textBlockId = t.id
        LEFT JOIN chapters c ON c.id = t.chapterId
        WHERE t.bookId = :bookId ORDER BY t.textOrder
        """
    )
    suspend fun exportRows(bookId: Long): List<ExportRow>

    @Query(
        """
        SELECT t.paraId AS paraId, tr.translatedText AS translatedText, tr.status AS status, tr.userApproved AS userApproved, tr.verifyState AS verifyState
        FROM translations tr JOIN text_blocks t ON t.id = tr.textBlockId WHERE t.bookId = :bookId ORDER BY t.textOrder
        """
    )
    suspend fun backupTranslations(bookId: Long): List<BackupTranslationRow>

    @Query("SELECT paraId AS paraId, id AS blockId FROM text_blocks WHERE bookId = :bookId")
    suspend fun paraBlockIds(bookId: Long): List<ParaBlockRow>

    @Query("SELECT id AS id, title AS title, chapterOrder AS chapterOrder, summary AS summary FROM chapters WHERE bookId = :bookId ORDER BY chapterOrder")
    suspend fun chapterTitles(bookId: Long): List<ChapterTitleRow>

    // ---- search ----
    // LIKE is case-insensitive for ASCII only, which suits English; Gurmukhi has no case. The pattern must come from SearchText.pattern.
    @Query(
        """
        SELECT t.id AS blockId, t.paraId AS paraId, t.textOrder AS textOrder, t.pdfPage AS pdfPage,
               t.originalText AS originalText, tr.translatedText AS translatedText
        FROM text_blocks t LEFT JOIN translations tr ON tr.textBlockId = t.id
        WHERE t.bookId = :bookId AND (t.originalText LIKE :pattern ESCAPE '\' OR tr.translatedText LIKE :pattern ESCAPE '\')
        ORDER BY t.textOrder LIMIT :limit
        """
    )
    suspend fun search(bookId: Long, pattern: String, limit: Int): List<SearchRow>

    /** Paragraphs the user approved or that were checked, whose original contains the pattern: examples for the prompt. */
    @Query(
        """
        SELECT t.id AS blockId, t.paraId AS paraId, t.textOrder AS textOrder, t.pdfPage AS pdfPage,
               t.originalText AS originalText, tr.translatedText AS translatedText
        FROM text_blocks t JOIN translations tr ON tr.textBlockId = t.id
        WHERE t.bookId = :bookId AND (tr.userApproved = 1 OR tr.verifyState = 'VERIFIED')
          AND t.originalText LIKE :pattern ESCAPE '\'
        ORDER BY t.textOrder LIMIT :limit
        """
    )
    suspend fun approvedContaining(bookId: Long, pattern: String, limit: Int): List<SearchRow>

    @Query("SELECT * FROM translations WHERE textBlockId IN (:blockIds)")
    suspend fun translationsFor(blockIds: List<Long>): List<TranslationEntity>

    // Explicit insert/update instead of REPLACE: REPLACE deletes the row first, which would cascade-delete its revisions.
    @Insert
    suspend fun insertTranslation(translation: TranslationEntity): Long

    @Update
    suspend fun updateTranslation(translation: TranslationEntity)

    @Query("UPDATE translations SET verifyState = :state WHERE textBlockId = :blockId")
    suspend fun setVerifyState(blockId: Long, state: String)

    @Query("$BLOCK_SELECT WHERE t.id IN (:ids) ORDER BY t.textOrder")
    suspend fun blocksByIds(ids: List<Long>): List<BlockRow>

    @Query(
        """
        SELECT t.id AS blockId, t.paraId AS paraId, t.textOrder AS ord, t.pdfPage AS page, t.chapterId AS chapterId,
               c.title AS chapterTitle, LENGTH(t.originalText) AS chars, (tr.id IS NOT NULL) AS translated
        FROM text_blocks t
        LEFT JOIN translations tr ON tr.textBlockId = t.id
        LEFT JOIN chapters c ON c.id = t.chapterId
        WHERE t.bookId = :bookId AND t.extractionStatus = 'OK'
        ORDER BY t.textOrder
        """
    )
    suspend fun planRows(bookId: Long): List<PlanRow>

    // Progress per unit. Only translatable blocks count, so scanned/no-text pages are not "untranslated pages".
    @Query(
        """
        SELECT t.pdfPage AS unitId, 'Page ' || t.pdfPage AS label, MIN(t.textOrder) AS firstOrder, COUNT(*) AS total,
               SUM(CASE WHEN tr.id IS NOT NULL THEN 1 ELSE 0 END) AS translated,
               SUM(CASE WHEN tr.status = 'NEEDS_REVIEW' OR tr.verifyState IN ('NEEDS_REVIEW','MINOR_ISSUES','MAJOR_ISSUES') THEN 1 ELSE 0 END) AS needsReview,
               SUM(CASE WHEN tr.verifyState = 'VERIFIED' THEN 1 ELSE 0 END) AS verified
        FROM text_blocks t LEFT JOIN translations tr ON tr.textBlockId = t.id
        WHERE t.bookId = :bookId AND t.extractionStatus = 'OK' AND t.pdfPage IS NOT NULL
        GROUP BY t.pdfPage ORDER BY t.pdfPage
        """
    )
    fun observePageUnits(bookId: Long): Flow<List<UnitAgg>>

    @Query(
        """
        SELECT c.id AS unitId, c.title AS label, MIN(t.textOrder) AS firstOrder, COUNT(*) AS total,
               SUM(CASE WHEN tr.id IS NOT NULL THEN 1 ELSE 0 END) AS translated,
               SUM(CASE WHEN tr.status = 'NEEDS_REVIEW' OR tr.verifyState IN ('NEEDS_REVIEW','MINOR_ISSUES','MAJOR_ISSUES') THEN 1 ELSE 0 END) AS needsReview,
               SUM(CASE WHEN tr.verifyState = 'VERIFIED' THEN 1 ELSE 0 END) AS verified
        FROM text_blocks t
        JOIN chapters c ON c.id = t.chapterId
        LEFT JOIN translations tr ON tr.textBlockId = t.id
        WHERE t.bookId = :bookId AND t.extractionStatus = 'OK'
        GROUP BY c.id ORDER BY MIN(t.textOrder)
        """
    )
    fun observeChapterUnits(bookId: Long): Flow<List<UnitAgg>>

    // ---- progress ----
    @Query("SELECT * FROM reading_progress WHERE bookId = :bookId")
    suspend fun getProgress(bookId: Long): ReadingProgressEntity?

    @Upsert
    suspend fun upsertProgress(progress: ReadingProgressEntity)

    // ---- bookmarks ----
    @Insert
    suspend fun insertBookmark(bookmark: BookmarkEntity)

    @Query("DELETE FROM bookmarks WHERE id = :id")
    suspend fun deleteBookmark(id: Long)

    @Query("SELECT * FROM bookmarks WHERE bookId = :bookId ORDER BY blockIndex")
    fun observeBookmarks(bookId: Long): Flow<List<BookmarkEntity>>
}

@Dao
interface JobDao {
    @Insert
    suspend fun insertAll(jobs: List<TranslationJobEntity>): List<Long>

    @Update
    suspend fun update(job: TranslationJobEntity)

    @Query("SELECT * FROM translation_jobs WHERE id = :id")
    suspend fun get(id: Long): TranslationJobEntity?

    @Query("SELECT * FROM translation_jobs WHERE bookId = :bookId ORDER BY sortKey, id")
    fun observeJobs(bookId: Long): Flow<List<TranslationJobEntity>>

    @Query("SELECT * FROM translation_jobs WHERE bookId = :bookId ORDER BY sortKey, id")
    suspend fun listJobs(bookId: Long): List<TranslationJobEntity>

    @Query("SELECT COALESCE(MAX(sortKey), 0) FROM translation_jobs WHERE bookId = :bookId")
    suspend fun maxSortKey(bookId: Long): Int

    @Query("DELETE FROM translation_jobs WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM translation_jobs WHERE bookId = :bookId AND status IN ('PENDING','PAUSED')")
    suspend fun deletePlanned(bookId: Long): Int

    @Query("UPDATE translation_jobs SET status = :to, updatedAt = :now WHERE bookId = :bookId AND status = :from")
    suspend fun moveAll(bookId: Long, from: String, to: String, now: Long): Int

    @Query("SELECT COUNT(*) FROM translations WHERE textBlockId IN (:ids)")
    suspend fun countTranslated(ids: List<Long>): Int

    @Query(
        """
        SELECT * FROM translation_jobs
        WHERE bookId = :bookId AND sortKey > :afterSortKey AND status IN ('PENDING','PREPARED','AWAITING_RESPONSE','REVIEW','INCOMPLETE','FAILED')
        ORDER BY sortKey, id LIMIT 1
        """
    )
    suspend fun nextOpen(bookId: Long, afterSortKey: Int): TranslationJobEntity?

    @Query(
        """
        SELECT bookId, COUNT(*) AS open,
               SUM(CASE WHEN status IN ('REVIEW','AWAITING_RESPONSE','FAILED','INCOMPLETE') THEN 1 ELSE 0 END) AS attention
        FROM translation_jobs WHERE status NOT IN ('COMPLETED','CANCELLED') GROUP BY bookId
        """
    )
    fun observeOpenCounts(): Flow<List<OpenJobCount>>
}

@Dao
interface GlossaryDao {
    @Query("SELECT * FROM glossary_terms WHERE bookId = :bookId ORDER BY termKey")
    fun observe(bookId: Long): Flow<List<GlossaryEntity>>

    @Query("SELECT * FROM glossary_terms WHERE bookId = :bookId")
    suspend fun list(bookId: Long): List<GlossaryEntity>

    @Query("SELECT * FROM glossary_terms WHERE id = :id")
    suspend fun get(id: Long): GlossaryEntity?

    @Query("SELECT * FROM glossary_terms WHERE bookId = :bookId AND termKey = :key LIMIT 1")
    suspend fun findByKey(bookId: Long, key: String): GlossaryEntity?

    @Insert
    suspend fun insert(term: GlossaryEntity): Long

    @Update
    suspend fun update(term: GlossaryEntity)

    @Query("DELETE FROM glossary_terms WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface RevisionDao {
    @Insert
    suspend fun insert(revision: RevisionEntity): Long

    @Query("SELECT * FROM translation_revisions WHERE textBlockId = :blockId ORDER BY id DESC")
    suspend fun forBlock(blockId: Long): List<RevisionEntity>

    @Query("SELECT * FROM translation_revisions WHERE id = :id")
    suspend fun get(id: Long): RevisionEntity?
}

@Dao
interface FindingDao {
    @Insert
    suspend fun insert(finding: FindingEntity): Long

    @Query("SELECT * FROM verification_findings WHERE id = :id")
    suspend fun get(id: Long): FindingEntity?

    @Query("UPDATE verification_findings SET state = :state, resolvedAt = :now WHERE id = :id")
    suspend fun setState(id: Long, state: String, now: Long)

    /** Findings that described a text which has since changed (or been re-checked) no longer apply. */
    @Query("UPDATE verification_findings SET state = 'SUPERSEDED', resolvedAt = :now WHERE textBlockId = :blockId AND state = 'OPEN'")
    suspend fun supersedeOpen(blockId: Long, now: Long)

    @Query("SELECT severity FROM verification_findings WHERE textBlockId = :blockId AND state = 'OPEN'")
    suspend fun openSeverities(blockId: Long): List<String>

    @Query("SELECT COUNT(*) FROM verification_findings WHERE bookId = :bookId AND state = 'OPEN'")
    fun observeOpenCount(bookId: Long): Flow<Int>

    @Query(
        """
        SELECT f.*, t.paraId AS paraId, t.originalText AS originalText, tr.translatedText AS translatedText,
               t.pdfPage AS pdfPage, t.textOrder AS textOrder
        FROM verification_findings f
        JOIN text_blocks t ON t.id = f.textBlockId
        JOIN translations tr ON tr.textBlockId = t.id
        WHERE f.bookId = :bookId AND f.state = 'OPEN'
        ORDER BY t.textOrder
        """
    )
    fun observeOpen(bookId: Long): Flow<List<FindingRow>>
}
