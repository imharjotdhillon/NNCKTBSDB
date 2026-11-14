package com.punjabibooktranslator.core

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

enum class ExportMode(val label: String) { TRANSLATED("Translation only"), BILINGUAL("Original and translation") }

data class ExportBlock(
    val paraId: String,
    val chapterId: Long?,
    val chapterTitle: String?,
    val blockType: String,
    val format: String?,
    val original: String,
    val translation: String?,
    /** "OK" or "NO_TEXT" (scanned page with no extractable text). */
    val extractionStatus: String,
    val verifyState: String = VerifyStates.NOT_VERIFIED,
)

data class ExportAudit(
    val total: Int,
    val translated: Int,
    val missing: List<String>,
    val noTextBlocks: Int,
    val unresolved: Int,
    val unverified: Int,
) {
    /** Complete means every extractable paragraph has a saved translation. Verification is reported, not required. */
    val isComplete get() = missing.isEmpty()
    val percent get() = ProgressCalc.percent(translated, total)
}

object ExportChecker {
    fun check(blocks: List<ExportBlock>): ExportAudit {
        val ok = blocks.filter { it.extractionStatus == "OK" }
        val missing = ok.filter { it.translation.isNullOrBlank() }.map { it.paraId }
        val done = ok.filter { !it.translation.isNullOrBlank() }
        return ExportAudit(
            total = ok.size, translated = done.size, missing = missing,
            noTextBlocks = blocks.size - ok.size,
            unresolved = done.count { VerifyStates.isUnresolved(it.verifyState) },
            unverified = done.count { it.verifyState == VerifyStates.NOT_VERIFIED },
        )
    }
}

enum class ItemKind { PARAGRAPH, HEADING, LIST_ITEM, QUOTE, PREFORMATTED }

data class ExportItem(
    val kind: ItemKind,
    val headingLevel: Int,
    val original: String?,
    val translation: String?,
    /** True when no translation exists and the export was allowed to continue anyway. */
    val missing: Boolean,
)

data class ExportChapter(val title: String?, val items: List<ExportItem>)

data class ExportDoc(
    val title: String,
    val author: String?,
    val targetLang: String,
    val sourceLang: String,
    val mode: ExportMode,
    val chapters: List<ExportChapter>,
    val audit: ExportAudit,
)

sealed class PlanResult {
    data class Ready(val doc: ExportDoc) : PlanResult()
    data class Refused(val audit: ExportAudit, val reason: String) : PlanResult()
}

object ExportPlanner {
    const val MISSING_MARKER = "[Not translated yet]"

    private fun kind(t: String) = when (t) {
        "HEADING" -> ItemKind.HEADING
        "LIST_ITEM" -> ItemKind.LIST_ITEM
        "QUOTE" -> ItemKind.QUOTE
        "PREFORMATTED" -> ItemKind.PREFORMATTED
        else -> ItemKind.PARAGRAPH
    }

    private fun level(f: String?) = when (f) { "h1" -> 1; "h2" -> 2; else -> 3 }

    /**
     * Builds the document to export. An incomplete book is REFUSED unless [allowIncomplete] is true; then every untranslated
     * paragraph is shown with a visible marker. Translation text is never invented or filled in from the original.
     * Scanned pages without text are left out (they are counted in the audit).
     */
    fun plan(
        title: String, author: String?, sourceLang: String, targetLang: String,
        blocks: List<ExportBlock>, mode: ExportMode, allowIncomplete: Boolean,
    ): PlanResult {
        val audit = ExportChecker.check(blocks)
        if (audit.total == 0) return PlanResult.Refused(audit, "This book has no extractable text to export.")
        if (audit.translated == 0) return PlanResult.Refused(audit, "Nothing has been translated yet.")
        if (!audit.isComplete && !allowIncomplete) {
            return PlanResult.Refused(audit, "${audit.missing.size} paragraph(s) are not translated yet.")
        }
        val chapters = ArrayList<ExportChapter>()
        var curId: Long? = Long.MIN_VALUE
        var curTitle: String? = null
        var items = ArrayList<ExportItem>()
        fun flush() { if (items.isNotEmpty()) chapters.add(ExportChapter(curTitle, items)); items = ArrayList() }
        for (b in blocks.filter { it.extractionStatus == "OK" }) {
            if (b.chapterId != curId) { flush(); curId = b.chapterId; curTitle = b.chapterTitle }
            val tr = b.translation?.takeIf { it.isNotBlank() }
            val missing = tr == null
            val shownTr = tr ?: ExportPlanner.MISSING_MARKER
            items.add(
                ExportItem(
                    kind(b.blockType), level(b.format),
                    if (mode == ExportMode.BILINGUAL) b.original else null,
                    shownTr, missing,
                )
            )
        }
        flush()
        return PlanResult.Ready(ExportDoc(title, author, targetLang, sourceLang, mode, chapters, audit))
    }
}

object Xml {
    fun escape(s: String): String = buildString(s.length + 16) {
        for (c in s) when {
            c == '&' -> append("&amp;"); c == '<' -> append("&lt;"); c == '>' -> append("&gt;")
            c == '"' -> append("&quot;"); c == '\'' -> append("&apos;")
            c.code < 0x20 && c != '\n' && c != '\t' && c != '\r' -> {} // not allowed in XML 1.0
            else -> append(c)
        }
    }
}

/** Writes a valid EPUB 3 (single zip, mimetype first and stored). Pure JVM so it can be tested here. */
object EpubWriter {
    private const val CSS = """
body { font-family: "NotoSansGurmukhi", sans-serif; line-height: 1.6; margin: 5%; }
h1, h2, h3 { line-height: 1.3; }
p { margin: 0 0 0.9em 0; }
.orig { color: #555; font-size: 0.92em; margin-bottom: 0.2em; }
.tr { margin-top: 0; }
.missing { color: #a00; font-style: italic; }
blockquote { margin-left: 1.2em; font-style: italic; }
pre { white-space: pre-wrap; }
"""

    private fun itemHtml(i: ExportItem, mode: ExportMode): String {
        val tr = Xml.escape(i.translation.orEmpty()).replace("\n", "<br/>")
        val trCls = if (i.missing) "tr missing" else "tr"
        fun pair(tag: String, extraAttr: String = ""): String =
            if (mode == ExportMode.BILINGUAL && i.original != null)
                "<div class=\"pair\"><$tag class=\"orig\"$extraAttr>${Xml.escape(i.original).replace("\n", "<br/>")}</$tag><$tag class=\"$trCls\">$tr</$tag></div>"
            else "<$tag class=\"$trCls\">$tr</$tag>"
        return when (i.kind) {
            ItemKind.HEADING -> {
                val h = "h${i.headingLevel.coerceIn(1, 3) + 1}" // h1 is the chapter title
                if (mode == ExportMode.BILINGUAL && i.original != null) "<$h>$tr</$h><p class=\"orig\">${Xml.escape(i.original)}</p>" else "<$h>$tr</$h>"
            }
            ItemKind.LIST_ITEM -> pair("p").replaceFirst("<p class=\"$trCls\">", "<p class=\"$trCls\">• ")
            ItemKind.QUOTE -> pair("blockquote")
            ItemKind.PREFORMATTED -> "<pre class=\"$trCls\">${Xml.escape(i.translation.orEmpty())}</pre>"
            ItemKind.PARAGRAPH -> pair("p")
        }
    }

    fun chapterXhtml(doc: ExportDoc, ch: ExportChapter, fallbackTitle: String): String {
        val lang = doc.targetLang
        val t = Xml.escape(ch.title?.takeIf { it.isNotBlank() } ?: fallbackTitle)
        val body = ch.items.joinToString("\n") { itemHtml(it, doc.mode) }
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<!DOCTYPE html>\n" +
            "<html xmlns=\"http://www.w3.org/1999/xhtml\" xmlns:epub=\"http://www.idpf.org/2007/ops\" lang=\"$lang\" xml:lang=\"$lang\">\n" +
            "<head><meta charset=\"utf-8\"/><title>$t</title><link rel=\"stylesheet\" type=\"text/css\" href=\"style.css\"/></head>\n" +
            "<body><h1>$t</h1>\n$body\n</body></html>\n"
    }

    /**
     * [fontBytes] is embedded as a font file so Gurmukhi renders without the reader's own font. [identifier] must be stable
     * per book so e-readers can tell versions apart; [modifiedIso] is "yyyy-MM-ddTHH:mm:ssZ".
     */
    fun write(doc: ExportDoc, out: OutputStream, fontBytes: ByteArray?, identifier: String, modifiedIso: String) {
        val chapters = doc.chapters
        val names = chapters.indices.map { "ch%04d.xhtml".format(it + 1) }
        val zip = ZipOutputStream(out)
        fun stored(name: String, data: ByteArray) {
            val e = ZipEntry(name); e.method = ZipEntry.STORED; e.size = data.size.toLong(); e.compressedSize = data.size.toLong()
            val crc = CRC32(); crc.update(data); e.crc = crc.value
            zip.putNextEntry(e); zip.write(data); zip.closeEntry()
        }
        fun deflated(name: String, text: String) {
            zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray(Charsets.UTF_8)); zip.closeEntry()
        }
        stored("mimetype", "application/epub+zip".toByteArray(Charsets.US_ASCII))
        deflated(
            "META-INF/container.xml",
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<container version=\"1.0\" xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\">" +
                "<rootfiles><rootfile full-path=\"OEBPS/content.opf\" media-type=\"application/oebps-package+xml\"/></rootfiles></container>\n",
        )
        val fontFace = if (fontBytes != null) "@font-face { font-family: \"NotoSansGurmukhi\"; src: url(\"fonts/NotoSansGurmukhi.ttf\"); }\n" else ""
        deflated("OEBPS/style.css", fontFace + CSS)
        if (fontBytes != null) { zip.putNextEntry(ZipEntry("OEBPS/fonts/NotoSansGurmukhi.ttf")); zip.write(fontBytes); zip.closeEntry() }
        chapters.forEachIndexed { i, ch -> deflated("OEBPS/${names[i]}", chapterXhtml(doc, ch, "Part ${i + 1}")) }

        val navItems = chapters.mapIndexed { i, ch ->
            "<li><a href=\"${names[i]}\">${Xml.escape(ch.title?.takeIf { it.isNotBlank() } ?: "Part ${i + 1}")}</a></li>"
        }.joinToString("\n")
        deflated(
            "OEBPS/nav.xhtml",
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<!DOCTYPE html>\n<html xmlns=\"http://www.w3.org/1999/xhtml\" xmlns:epub=\"http://www.idpf.org/2007/ops\" lang=\"${doc.targetLang}\" xml:lang=\"${doc.targetLang}\">" +
                "<head><meta charset=\"utf-8\"/><title>Contents</title></head><body><nav epub:type=\"toc\" id=\"toc\"><h1>Contents</h1><ol>\n$navItems\n</ol></nav></body></html>\n",
        )
        val manifest = buildString {
            append("<item id=\"nav\" href=\"nav.xhtml\" media-type=\"application/xhtml+xml\" properties=\"nav\"/>\n")
            append("<item id=\"css\" href=\"style.css\" media-type=\"text/css\"/>\n")
            if (fontBytes != null) append("<item id=\"font\" href=\"fonts/NotoSansGurmukhi.ttf\" media-type=\"font/ttf\"/>\n")
            names.forEachIndexed { i, n -> append("<item id=\"c$i\" href=\"$n\" media-type=\"application/xhtml+xml\"/>\n") }
        }
        val spine = names.indices.joinToString("\n") { "<itemref idref=\"c$it\"/>" }
        val author = doc.author?.takeIf { it.isNotBlank() }?.let { "<dc:creator>${Xml.escape(it)}</dc:creator>\n" }.orEmpty()
        deflated(
            "OEBPS/content.opf",
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<package xmlns=\"http://www.idpf.org/2007/opf\" version=\"3.0\" unique-identifier=\"bookid\" xml:lang=\"${doc.targetLang}\">\n" +
                "<metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\">\n<dc:identifier id=\"bookid\">${Xml.escape(identifier)}</dc:identifier>\n" +
                "<dc:title>${Xml.escape(doc.title)}</dc:title>\n$author<dc:language>${doc.targetLang}</dc:language>\n" +
                "<meta property=\"dcterms:modified\">$modifiedIso</meta>\n</metadata>\n<manifest>\n$manifest</manifest>\n<spine>\n$spine\n</spine>\n</package>\n",
        )
        zip.finish()
        zip.flush()
    }

    fun toBytes(doc: ExportDoc, fontBytes: ByteArray?, identifier: String, modifiedIso: String): ByteArray {
        val bo = ByteArrayOutputStream(); write(doc, bo, fontBytes, identifier, modifiedIso); return bo.toByteArray()
    }
}
