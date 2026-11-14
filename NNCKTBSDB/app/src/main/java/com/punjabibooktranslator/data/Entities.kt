package com.punjabibooktranslator.data

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

object ImportStatus {
    const val IMPORTING = "IMPORTING"
    const val READY = "READY"
    const val FAILED = "FAILED"
}

object BlockStatus {
    const val OK = "OK"
    const val NO_TEXT = "NO_TEXT"
}

object TranslationStatus {
    const val TRANSLATED = "TRANSLATED"
    const val NEEDS_REVIEW = "NEEDS_REVIEW"
    const val VERIFIED = "VERIFIED"
}

@Entity(tableName = "books", indices = [Index(value = ["fileHash"], unique = true)])
data class BookEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val author: String? = null,
    /** "PDF" or "EPUB". */
    val format: String,
    /** The URI the user picked. Informational only; the app reads its own private copy. */
    val sourceUri: String?,
    /** Absolute path of the private copy inside app storage. */
    val localPath: String,
    /** SHA-256 of the file, used to prevent duplicate imports. */
    val fileHash: String,
    val fileSize: Long,
    /** ISO-ish codes: "en" or "pa" (Gurmukhi). */
    val sourceLang: String,
    val targetLang: String,
    val importedAt: Long,
    val coverPath: String? = null,
    /** PDF page count; 0 for EPUB. */
    val pageCount: Int = 0,
    /** PDF pages with no selectable text (candidates for OCR in a later phase). */
    val noTextPages: Int = 0,
    val importStatus: String = ImportStatus.IMPORTING,
    val importError: String? = null,
    val importProgress: Int = 0,
    val metadataJson: String? = null,
    /** [com.punjabibooktranslator.core.TranslationStyle] name. Added in schema v2. */
    @ColumnInfo(defaultValue = "LITERARY") val translationStyle: String = "LITERARY",
)

@Entity(
    tableName = "chapters",
    foreignKeys = [ForeignKey(BookEntity::class, ["id"], ["bookId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("bookId")],
)
data class ChapterEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val title: String,
    val chapterOrder: Int,
    /** EPUB spine resource path inside the archive, when applicable. */
    val resourceHref: String? = null,
    /** Reserved for Phase 4 (user-editable chapter summary). */
    val summary: String? = null,
)

/**
 * Original extracted text. Rows are never updated after import: corrections will live in
 * separate revision rows (later phases) so the source record stays intact.
 */
@Entity(
    tableName = "text_blocks",
    foreignKeys = [
        ForeignKey(BookEntity::class, ["id"], ["bookId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(ChapterEntity::class, ["id"], ["chapterId"], onDelete = ForeignKey.SET_NULL),
    ],
    indices = [
        Index("bookId", "textOrder", unique = true),
        Index("bookId", "paraId", unique = true),
        Index("chapterId"),
        Index("bookId", "pdfPage"),
    ],
)
data class TextBlockEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val chapterId: Long?,
    /** Stable, human-readable paragraph ID within the book, e.g. "P0001". */
    val paraId: String,
    val originalText: String,
    /** 0-based reading order across the whole book; contiguous. */
    val textOrder: Int,
    /** 1-based PDF page number, null for EPUB. */
    val pdfPage: Int?,
    /** EPUB resource path, null for PDF. */
    val resourceHref: String?,
    /** Source location: "page:12#3" for PDF, "OEBPS/ch1.xhtml#7" for EPUB. */
    val locator: String?,
    /** PARAGRAPH, HEADING, LIST_ITEM, QUOTE or PREFORMATTED. */
    val blockType: String,
    /** Source markup hint, e.g. the HTML tag name ("h2"). */
    val format: String?,
    /** [BlockStatus.OK] or [BlockStatus.NO_TEXT]. */
    @ColumnInfo(defaultValue = "OK") val extractionStatus: String = BlockStatus.OK,
)

/** Phase 1 only reads this table; the translation workflow (Phase 2) writes to it. */
@Entity(
    tableName = "translations",
    foreignKeys = [ForeignKey(TextBlockEntity::class, ["id"], ["textBlockId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("textBlockId", unique = true)],
)
data class TranslationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val textBlockId: Long,
    val translatedText: String,
    val sourceLang: String,
    val targetLang: String,
    val style: String,
    val status: String,
    val createdAt: Long,
    val updatedAt: Long,
    val revision: Int = 1,
    val userApproved: Boolean = false,
    /** [com.punjabibooktranslator.core.VerifyStates]. Added in schema v4. Never "verified" unless a check or the user said so. */
    @ColumnInfo(defaultValue = "NOT_VERIFIED") val verifyState: String = "NOT_VERIFIED",
)

@Entity(
    tableName = "reading_progress",
    foreignKeys = [ForeignKey(BookEntity::class, ["id"], ["bookId"], onDelete = ForeignKey.CASCADE)],
)
data class ReadingProgressEntity(
    @PrimaryKey val bookId: Long,
    val lastBlockIndex: Int,
    /** 1-based. */
    val lastPdfPage: Int,
    /** "ORIGINAL" or "TRANSLATED". */
    val displayMode: String,
    /** "TEXT" or "PAGES" (PDF only). */
    val viewMode: String,
    val lastOpenedAt: Long,
)

@Entity(
    tableName = "bookmarks",
    foreignKeys = [ForeignKey(BookEntity::class, ["id"], ["bookId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("bookId")],
)
data class BookmarkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val blockIndex: Int,
    val pdfPage: Int?,
    val label: String,
    val note: String? = null,
    val createdAt: Long,
)

/**
 * One locally tracked batch. The queue only records work the APP prepared and replies the USER pasted back;
 * no status ever means "The AI is running". Saved translations live in [TranslationEntity] and survive any job change.
 */
@Entity(
    tableName = "translation_jobs",
    foreignKeys = [ForeignKey(BookEntity::class, ["id"], ["bookId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("bookId", "status")],
)
data class TranslationJobEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val label: String,
    /** "PDF_PAGES" or "TEXT". */
    val mode: String,
    /** Comma-separated text_blocks ids, in reading order. */
    val blockIds: String,
    val paraCount: Int,
    val chars: Int,
    val firstOrder: Int,
    val lastOrder: Int,
    val firstPage: Int?,
    val lastPage: Int?,
    val partIndex: Int,
    val partCount: Int,
    /** Pages per batch (PDF) or character budget (text) that produced this job. */
    val batchSize: Int,
    val sourceLang: String,
    val targetLang: String,
    val style: String,
    val status: String,
    /** The prompt text last generated for this job. */
    val prompt: String?,
    /** Ids of the paragraphs that prompt asked for (a retry may ask for fewer than [blockIds]). */
    val promptBlockIds: String?,
    /** The raw reply the user pasted. Kept so an interrupted review can be restored and nothing is discarded. */
    val responseText: String?,
    val lastError: String?,
    val sortKey: Int,
    val createdAt: Long,
    val updatedAt: Long,
)


/** Book glossary. Only [approved] entries are ever put into a prompt. Added in schema v4. */
@Entity(
    tableName = "glossary_terms",
    foreignKeys = [ForeignKey(BookEntity::class, ["id"], ["bookId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("bookId", "termKey", unique = true)],
)
data class GlossaryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val term: String,
    /** Lower-cased, whitespace-collapsed form used to prevent duplicates. */
    val termKey: String,
    val translation: String,
    /** [com.punjabibooktranslator.core.GlossaryType] name. */
    val type: String,
    val explanation: String?,
    val approved: Boolean,
    /** The rendering before the last change, so older paragraphs can be found and reviewed. */
    val previousTranslation: String?,
    val createdAt: Long,
    val updatedAt: Long,
)

/** Every text a translation had before it was replaced. Nothing is overwritten without a copy being kept here. */
@Entity(
    tableName = "translation_revisions",
    foreignKeys = [ForeignKey(TextBlockEntity::class, ["id"], ["textBlockId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("textBlockId")],
)
data class RevisionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val textBlockId: Long,
    val translatedText: String,
    /** The revision number this text had while it was current. */
    val revision: Int,
    /** "EDIT", "ACCEPTED_SUGGESTION", "REPLACED_BY_IMPORT" or "REVERT". */
    val reason: String,
    val createdAt: Long,
)

/** One issue the AI reported for one paragraph. Created only from a reply the user pasted. */
@Entity(
    tableName = "verification_findings",
    foreignKeys = [ForeignKey(TextBlockEntity::class, ["id"], ["textBlockId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("textBlockId"), Index("bookId", "state")],
)
data class FindingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val textBlockId: Long,
    /** [com.punjabibooktranslator.core.Severity] name. */
    val severity: String,
    val issueText: String,
    val explanation: String?,
    val suggestion: String?,
    /** [com.punjabibooktranslator.core.FindingStates]. */
    val state: String,
    val createdAt: Long,
    val resolvedAt: Long?,
)

// ---- Query result models (not tables) ----

data class LibraryRow(
    @Embedded val book: BookEntity,
    val totalBlocks: Int,
    val translatedBlocks: Int,
    val allBlocks: Int,
    val lastBlockIndex: Int?,
    val lastOpenedAt: Long?,
)

data class BlockRow(
    @Embedded val block: TextBlockEntity,
    val translatedText: String?,
    val chapterTitle: String?,
    val translationId: Long?,
    val verifyState: String?,
)

data class SearchRow(
    val blockId: Long,
    val paraId: String,
    val textOrder: Int,
    val pdfPage: Int?,
    val originalText: String,
    val translatedText: String?,
)

data class FindingRow(
    @Embedded val finding: FindingEntity,
    val paraId: String,
    val originalText: String,
    val translatedText: String,
    val pdfPage: Int?,
    val textOrder: Int,
)

data class ChapterSummaryRow(
    val id: Long,
    val title: String,
    val chapterOrder: Int,
    val summary: String?,
    val paraCount: Int,
)

data class ChapterRow(
    val id: Long,
    val title: String,
    val chapterOrder: Int,
    val startIndex: Int?,
)

/** Lightweight paragraph row used to plan batches without loading text. */
data class PlanRow(
    val blockId: Long,
    val paraId: String,
    val ord: Int,
    val page: Int?,
    val chapterId: Long?,
    val chapterTitle: String?,
    val chars: Int,
    val translated: Boolean,
)

data class ExportRow(
    val paraId: String,
    val chapterId: Long?,
    val chapterTitle: String?,
    val blockType: String,
    val format: String?,
    val original: String,
    val translation: String?,
    val extractionStatus: String,
    val verifyState: String,
)

data class BackupTranslationRow(val paraId: String, val translatedText: String, val status: String, val userApproved: Boolean, val verifyState: String)
data class ParaBlockRow(val paraId: String, val blockId: Long)
data class ChapterTitleRow(val id: Long, val title: String, val chapterOrder: Int, val summary: String?)

data class OpenJobCount(val bookId: Long, val open: Int, val attention: Int)
