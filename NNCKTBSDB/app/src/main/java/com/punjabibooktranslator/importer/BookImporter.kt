package com.punjabibooktranslator.importer

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.punjabibooktranslator.core.TextUtils
import com.punjabibooktranslator.data.AppDatabase
import com.punjabibooktranslator.data.BookEntity
import com.punjabibooktranslator.data.ImportStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.zip.ZipFile

/**
 * Copies a user-picked PDF/EPUB into app storage (so it keeps working even if the original URI is revoked),
 * prevents duplicates by content hash, then extracts structured text blocks. Runs in the application scope so it
 * survives screen changes. Interrupted imports are marked FAILED on next launch and can be retried.
 */
class BookImporter(
    private val context: Context,
    private val db: AppDatabase,
    private val scope: CoroutineScope,
) {
    private val mutex = Mutex()
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private val booksDir = File(context.filesDir, "books").apply { mkdirs() }
    private val coversDir = File(context.filesDir, "covers").apply { mkdirs() }

    fun enqueueImport(uri: Uri) { scope.launch { mutex.withLock { importInternal(uri) } } }
    fun enqueueRetry(bookId: Long) { scope.launch { mutex.withLock { extract(bookId) } } }
    fun enqueueDelete(bookId: Long) { scope.launch { mutex.withLock { deleteInternal(bookId) } } }

    private fun displayName(uri: Uri): String? = try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    } catch (e: Exception) {
        null
    }

    private fun sniffFormat(file: File): String? {
        val head = ByteArray(5)
        val n = file.inputStream().use { it.read(head) }
        if (n >= 5 && String(head, Charsets.ISO_8859_1) == "%PDF-") return "PDF"
        if (n >= 2 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()) {
            return try {
                ZipFile(file).use { if (it.getEntry("META-INF/container.xml") != null) "EPUB" else null }
            } catch (e: Exception) {
                null
            }
        }
        return null
    }

    private suspend fun importInternal(uri: Uri) {
        val name = displayName(uri)
        val tmp = File(booksDir, "incoming-${System.nanoTime()}.tmp")
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            withContext(Dispatchers.IO) {
                val input = context.contentResolver.openInputStream(uri) ?: throw java.io.IOException("Cannot open the file.")
                DigestInputStream(input, digest).use { d -> tmp.outputStream().use { o -> d.copyTo(o) } }
            }
        } catch (e: CancellationException) {
            tmp.delete(); throw e
        } catch (e: Exception) {
            tmp.delete()
            _messages.emit("Couldn't read that file: ${e.message ?: e.javaClass.simpleName}")
            return
        }

        val hash = digest.digest().joinToString("") { "%02x".format(it) }
        val dao = db.bookDao()
        dao.findByHash(hash)?.let {
            tmp.delete()
            _messages.emit("“${it.title}” is already in your library.")
            return
        }

        val format = withContext(Dispatchers.IO) { sniffFormat(tmp) }
        if (format == null) {
            tmp.delete()
            _messages.emit("Only PDF and EPUB files are supported.")
            return
        }

        val id = dao.insert(
            BookEntity(
                title = name?.substringBeforeLast('.') ?: "Untitled",
                format = format,
                sourceUri = uri.toString(),
                localPath = "",
                fileHash = hash,
                fileSize = tmp.length(),
                sourceLang = "en",
                targetLang = "pa",
                importedAt = System.currentTimeMillis(),
                importStatus = ImportStatus.IMPORTING,
            )
        )
        val dest = File(booksDir, "$id.${format.lowercase()}")
        withContext(Dispatchers.IO) {
            if (!tmp.renameTo(dest)) { tmp.copyTo(dest, overwrite = true); tmp.delete() }
        }
        dao.setLocalPath(id, dest.absolutePath)
        extract(id)
    }

    private suspend fun extract(bookId: Long) {
        val dao = db.bookDao()
        val rdao = db.readerDao()
        val book = dao.getById(bookId) ?: return
        val file = File(book.localPath)
        if (!file.exists()) {
            dao.setStatus(bookId, ImportStatus.FAILED, "The stored copy of this book is missing. Remove it and import again.")
            return
        }
        rdao.deleteBlocks(bookId)
        rdao.deleteChapters(bookId)
        dao.setStatus(bookId, ImportStatus.IMPORTING, null)
        dao.setProgress(bookId, 0)

        val writer = BlockWriter(db, bookId)
        try {
            val meta = withContext(Dispatchers.IO) {
                val progress: suspend (Int) -> Unit = { dao.setProgress(bookId, it) }
                if (book.format == "PDF") PdfExtractor(coversDir).extract(file, bookId, writer, progress)
                else EpubExtractor(coversDir).extract(file, bookId, writer, progress)
            }
            writer.flush()
            if (writer.blockCount == 0) throw IllegalStateException("No readable text was found in this book.")

            val lang = TextUtils.detectLanguage(rdao.sampleText(bookId).joinToString(" "))
            val fresh = dao.getById(bookId) ?: return
            dao.update(
                fresh.copy(
                    title = meta.title ?: fresh.title,
                    author = meta.author,
                    coverPath = meta.coverPath,
                    pageCount = meta.pageCount,
                    noTextPages = meta.noTextPages,
                    sourceLang = lang,
                    targetLang = if (lang == "pa") "en" else "pa",
                    metadataJson = meta.metadataJson,
                    importStatus = ImportStatus.READY,
                    importError = null,
                    importProgress = 100,
                )
            )
            if (meta.pageCount > 0 && meta.noTextPages > 0) {
                _messages.emit("${meta.noTextPages} page(s) have no selectable text (scanned?). They can be read in Pages view; OCR comes in a later phase.")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            rdao.deleteBlocks(bookId)
            rdao.deleteChapters(bookId)
            val msg = when {
                e.javaClass.simpleName.contains("InvalidPassword") -> "This PDF is password-protected, which isn't supported yet."
                else -> e.message ?: e.javaClass.simpleName
            }
            dao.setStatus(bookId, ImportStatus.FAILED, msg)
            _messages.emit("Import failed: $msg")
        }
    }

    private suspend fun deleteInternal(bookId: Long) {
        val dao = db.bookDao()
        val book = dao.getById(bookId) ?: return
        dao.deleteById(bookId) // cascades to chapters, blocks, translations, progress, bookmarks
        withContext(Dispatchers.IO) {
            File(book.localPath).delete()
            book.coverPath?.let { File(it).delete() }
        }
        _messages.emit("Deleted “${book.title}”.")
    }
}
