package com.punjabibooktranslator.data

import android.content.Context
import android.net.Uri
import com.punjabibooktranslator.core.Backup
import com.punjabibooktranslator.core.BackupBook
import com.punjabibooktranslator.core.BackupFormat
import com.punjabibooktranslator.core.BackupSummary
import com.punjabibooktranslator.core.BackupTerm
import com.punjabibooktranslator.core.BackupTranslation
import com.punjabibooktranslator.core.RestorePlanner
import com.punjabibooktranslator.core.TranslationStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class BackupRepository(private val db: AppDatabase, private val context: Context, private val translations: TranslationRepository, private val glossary: GlossaryRepository) {

    /** Collects translations, glossary and chapter summaries of every finished import. Never includes the book files. */
    suspend fun write(uri: Uri): String? = withContext(Dispatchers.IO) {
        try {
            val reader = db.readerDao()
            val books = db.bookDao().listReady().map { b ->
                BackupBook(
                    title = b.title, author = b.author, format = b.format, fileHash = b.fileHash, sourceLang = b.sourceLang,
                    targetLang = b.targetLang, style = b.translationStyle,
                    translations = reader.backupTranslations(b.id).map { BackupTranslation(it.paraId, it.translatedText, it.status, it.userApproved, it.verifyState) },
                    glossary = db.glossaryDao().list(b.id).map { BackupTerm(it.term, it.translation, it.type, it.explanation, it.approved) },
                    summaries = reader.chapterTitles(b.id).filter { !it.summary.isNullOrBlank() }.map { BackupSummary(it.title, it.chapterOrder, it.summary!!) },
                )
            }
            val json = BackupFormat.write(Backup(Backup.CURRENT, System.currentTimeMillis(), books))
            val out = context.contentResolver.openOutputStream(uri, "wt") ?: return@withContext "Could not open the chosen file for writing."
            out.use { it.write(json.toByteArray(Charsets.UTF_8)) }
            null
        } catch (e: Exception) {
            "Backup failed: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    /**
     * Restores into books that are already imported (matched by file fingerprint). Empty paragraphs are filled in; paragraphs that already
     * hold a DIFFERENT translation are kept unless [overwrite] is true (the old text then goes to revision history). Restored translations
     * are marked Not verified, because verification belongs to the text that was checked.
     */
    suspend fun restore(uri: Uri, overwrite: Boolean): List<String> = withContext(Dispatchers.IO) {
        val text = try {
            context.contentResolver.openInputStream(uri)?.use { String(it.readBytes(), Charsets.UTF_8) } ?: return@withContext listOf("Could not open the file.")
        } catch (e: Exception) { return@withContext listOf("Could not read the file: ${e.message}") }
        val backup = try { BackupFormat.read(text) } catch (e: BackupFormat.BackupException) { return@withContext listOf(e.message ?: "Unreadable backup.") }
        val report = ArrayList<String>()
        val reader = db.readerDao()
        for (bb in backup.books) {
            val book = db.bookDao().findByHash(bb.fileHash)
            if (book == null || book.importStatus != ImportStatus.READY) {
                report.add("“${bb.title}”: not restored. Import the same file first (${bb.translations.size} translations waiting).")
                continue
            }
            val ids = reader.paraBlockIds(book.id).associate { it.paraId to it.blockId }
            val existing = reader.exportRows(book.id).associate { it.paraId to it.translation }
            val plan = RestorePlanner.plan(existing, bb.translations)
            val chosen = plan.toAdd + if (overwrite) plan.conflicts else emptyList()
            val items = chosen.mapNotNull { t -> ids[t.paraId]?.let { SaveItem(it, t.text, flagged = false, edited = t.userApproved) } }
            val style = TranslationStyle.entries.firstOrNull { it.name == bb.style } ?: TranslationStyle.LITERARY
            val saved = translations.save(items, book.sourceLang, book.targetLang, style)
            var terms = 0
            for (g in bb.glossary) {
                if (glossary.add(book.id, g.term, g.translation, com.punjabibooktranslator.core.GlossaryType.fromKey(g.type), g.explanation, g.approved) == GlossaryResult.SAVED) terms++
            }
            var sums = 0
            val chapters = reader.chapterTitles(book.id)
            for (s in bb.summaries) {
                val ch = chapters.firstOrNull { it.title == s.chapterTitle && it.chapterOrder == s.chapterOrder } ?: chapters.firstOrNull { it.title == s.chapterTitle }
                if (ch != null && ch.summary.isNullOrBlank()) { reader.setChapterSummary(ch.id, s.summary); sums++ }
            }
            report.add(
                "“${book.title}”: ${saved.saved} translation(s) restored, ${plan.identical} already identical, " +
                    (if (overwrite) "${plan.conflicts.size} overwritten (old text kept in history)" else "${plan.conflicts.size} differ and were kept as they are") +
                    ", $terms glossary term(s), $sums summary(ies)" + (if (plan.unknownIds.isNotEmpty()) ", ${plan.unknownIds.size} paragraph id(s) not found in this import" else "") + "."
            )
        }
        if (report.isEmpty()) report.add("The backup contains no books.")
        report
    }
}
