package com.punjabibooktranslator.core

data class BackupTranslation(val paraId: String, val text: String, val status: String, val userApproved: Boolean, val verifyState: String)
data class BackupTerm(val term: String, val translation: String, val type: String, val explanation: String?, val approved: Boolean)
data class BackupSummary(val chapterTitle: String, val chapterOrder: Int, val summary: String)

data class BackupBook(
    val title: String, val author: String?, val format: String, val fileHash: String,
    val sourceLang: String, val targetLang: String, val style: String,
    val translations: List<BackupTranslation>, val glossary: List<BackupTerm>, val summaries: List<BackupSummary>,
)

data class Backup(val version: Int, val createdAt: Long, val books: List<BackupBook>) {
    companion object { const val CURRENT = 1 }
}

/**
 * Backup holds only what the USER created or that cost effort to make (translations, glossary, summaries), keyed by the book's
 * file hash and paragraph ids. It never contains the books themselves, credentials or Google data. Restoring needs the same book
 * file to be imported first, because paragraph ids are only meaningful for that file.
 */
object BackupFormat {
    private fun q(s: String?) = if (s == null) "null" else Json.quote(s)

    fun write(b: Backup): String = buildString {
        append("{\"format\":\"punjabi-book-translator-backup\",\"version\":").append(b.version)
        append(",\"createdAt\":").append(b.createdAt).append(",\"books\":[")
        b.books.forEachIndexed { bi, k ->
            if (bi > 0) append(',')
            append("{\"title\":").append(q(k.title)).append(",\"author\":").append(q(k.author)).append(",\"bookFormat\":").append(q(k.format))
            append(",\"fileHash\":").append(q(k.fileHash)).append(",\"sourceLang\":").append(q(k.sourceLang))
            append(",\"targetLang\":").append(q(k.targetLang)).append(",\"style\":").append(q(k.style))
            append(",\"translations\":[")
            k.translations.forEachIndexed { i, t ->
                if (i > 0) append(',')
                append("{\"id\":").append(q(t.paraId)).append(",\"text\":").append(q(t.text)).append(",\"status\":").append(q(t.status))
                append(",\"approved\":").append(t.userApproved).append(",\"verify\":").append(q(t.verifyState)).append('}')
            }
            append("],\"glossary\":[")
            k.glossary.forEachIndexed { i, g ->
                if (i > 0) append(',')
                append("{\"term\":").append(q(g.term)).append(",\"translation\":").append(q(g.translation)).append(",\"type\":").append(q(g.type))
                append(",\"explanation\":").append(q(g.explanation)).append(",\"approved\":").append(g.approved).append('}')
            }
            append("],\"summaries\":[")
            k.summaries.forEachIndexed { i, s ->
                if (i > 0) append(',')
                append("{\"chapter\":").append(q(s.chapterTitle)).append(",\"order\":").append(s.chapterOrder).append(",\"summary\":").append(q(s.summary)).append('}')
            }
            append("]}")
        }
        append("]}")
    }

    class BackupException(message: String) : Exception(message)

    private fun J?.s(): String? = (this as? J.Str)?.v
    private fun J?.bool(): Boolean = (this as? J.Bool)?.v ?: false
    private fun J?.int(): Int = (this as? J.Num)?.raw?.toDoubleOrNull()?.toInt() ?: 0
    private fun J?.arr(): List<J.Obj> = (this as? J.Arr)?.items?.filterIsInstance<J.Obj>() ?: emptyList()
    private fun J.Obj.f(k: String): J? = map[k]

    /** Strict: a backup is data the user is trusting us with, so a damaged file is refused rather than half-read. */
    fun read(text: String): Backup {
        val root = try { JsonReader(text.trim()).parseTop() } catch (e: JsonParseException) { throw BackupException("This is not a readable backup file (${e.message}).") }
        if (root !is J.Obj || root.f("format").s() != "punjabi-book-translator-backup") throw BackupException("This file is not a Punjabi Book Translator backup.")
        val version = root.f("version").int()
        if (version < 1 || version > Backup.CURRENT) throw BackupException("This backup was made by a newer version of the app (format $version).")
        val books = root.f("books").arr().map { k ->
            val hash = k.f("fileHash").s() ?: throw BackupException("A book in this backup has no file fingerprint.")
            BackupBook(
                title = k.f("title").s().orEmpty(), author = k.f("author").s(), format = k.f("bookFormat").s().orEmpty(), fileHash = hash,
                sourceLang = k.f("sourceLang").s().orEmpty(), targetLang = k.f("targetLang").s().orEmpty(), style = k.f("style").s().orEmpty(),
                translations = k.f("translations").arr().mapNotNull { t ->
                    val id = t.f("id").s(); val tx = t.f("text").s()
                    if (id == null || tx.isNullOrBlank()) null
                    else BackupTranslation(id, tx, t.f("status").s() ?: "TRANSLATED", t.f("approved").bool(), t.f("verify").s() ?: VerifyStates.NOT_VERIFIED)
                },
                glossary = k.f("glossary").arr().mapNotNull { g ->
                    val term = g.f("term").s(); val tr = g.f("translation").s()
                    if (term.isNullOrBlank() || tr.isNullOrBlank()) null
                    else BackupTerm(term, tr, g.f("type").s() ?: "GENERAL", g.f("explanation").s(), g.f("approved").bool())
                },
                summaries = k.f("summaries").arr().mapNotNull { s ->
                    val ch = s.f("chapter").s(); val sm = s.f("summary").s()
                    if (ch == null || sm.isNullOrBlank()) null else BackupSummary(ch, s.f("order").int(), sm)
                },
            )
        }
        return Backup(version, ((root.f("createdAt") as? J.Num)?.raw?.toLongOrNull() ?: 0L), books)
    }
}

data class RestorePlan(val toAdd: List<BackupTranslation>, val identical: Int, val conflicts: List<BackupTranslation>, val unknownIds: List<String>)

object RestorePlanner {
    /**
     * Decides what a restore would do. Paragraphs with no translation are filled in. A paragraph that already has a DIFFERENT
     * translation is a conflict: it is kept as it is unless the user chooses to overwrite (the old text then goes to revision history).
     */
    fun plan(existing: Map<String, String?>, incoming: List<BackupTranslation>): RestorePlan {
        val add = ArrayList<BackupTranslation>(); val conflicts = ArrayList<BackupTranslation>(); val unknown = ArrayList<String>()
        var same = 0
        val seen = HashSet<String>()
        for (t in incoming) {
            if (!seen.add(t.paraId)) continue
            if (!existing.containsKey(t.paraId)) { unknown.add(t.paraId); continue }
            val cur = existing[t.paraId]
            when {
                cur.isNullOrBlank() -> add.add(t)
                TextUtils.normalizeTranslation(cur) == TextUtils.normalizeTranslation(t.text) -> same++
                else -> conflicts.add(t)
            }
        }
        return RestorePlan(add, same, conflicts, unknown)
    }
}
