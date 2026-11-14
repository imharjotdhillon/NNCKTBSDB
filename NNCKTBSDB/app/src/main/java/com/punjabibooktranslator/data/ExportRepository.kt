package com.punjabibooktranslator.data

import android.content.Context
import android.net.Uri
import com.punjabibooktranslator.R
import com.punjabibooktranslator.core.EpubWriter
import com.punjabibooktranslator.core.ExportBlock
import com.punjabibooktranslator.core.ExportChecker
import com.punjabibooktranslator.core.ExportAudit
import com.punjabibooktranslator.core.ExportDoc
import com.punjabibooktranslator.core.ExportMode
import com.punjabibooktranslator.core.ExportPlanner
import com.punjabibooktranslator.core.PlanResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class ExportRepository(private val db: AppDatabase, private val context: Context) {

    private suspend fun blocks(bookId: Long): List<ExportBlock> = db.readerDao().exportRows(bookId).map {
        ExportBlock(it.paraId, it.chapterId, it.chapterTitle, it.blockType, it.format, it.original, it.translation, it.extractionStatus, it.verifyState)
    }

    suspend fun audit(bookId: Long): ExportAudit = ExportChecker.check(blocks(bookId))

    suspend fun plan(book: BookEntity, mode: ExportMode, allowIncomplete: Boolean): PlanResult =
        ExportPlanner.plan(book.title, book.author, book.sourceLang, book.targetLang, blocks(book.id), mode, allowIncomplete)

    /** Writes to the file the user chose. Returns an error message, or null on success. */
    suspend fun writeEpub(book: BookEntity, doc: ExportDoc, uri: Uri): String? = withContext(Dispatchers.IO) {
        try {
            val font = context.resources.openRawResource(R.font.noto_sans_gurmukhi_regular).use { it.readBytes() }
            val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())
            val out = context.contentResolver.openOutputStream(uri, "wt") ?: return@withContext "Could not open the chosen file for writing."
            out.use { EpubWriter.write(doc, it, font, "urn:pbt:${book.fileHash}:${doc.targetLang}:${doc.mode.name}", iso) }
            null
        } catch (e: Exception) {
            "Export failed: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    suspend fun writePdf(doc: ExportDoc, uri: Uri): String? = withContext(Dispatchers.IO) {
        try {
            val out = context.contentResolver.openOutputStream(uri, "wt") ?: return@withContext "Could not open the chosen file for writing."
            out.use { PdfExporter(context).write(doc, it) }
            null
        } catch (e: Exception) {
            "Export failed: ${e.message ?: e.javaClass.simpleName}"
        }
    }
}
