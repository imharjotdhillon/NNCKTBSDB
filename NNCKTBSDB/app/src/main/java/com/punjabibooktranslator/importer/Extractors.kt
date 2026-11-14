package com.punjabibooktranslator.importer

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.punjabibooktranslator.core.TextUtils
import com.punjabibooktranslator.data.BlockStatus
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.io.File
import java.util.zip.ZipFile

data class ExtractedMeta(
    val title: String?,
    val author: String?,
    val coverPath: String?,
    val pageCount: Int,
    val noTextPages: Int,
    val metadataJson: String?,
)

class PdfExtractor(private val coversDir: File) {

    suspend fun extract(
        file: File,
        bookId: Long,
        writer: BlockWriter,
        onProgress: suspend (Int) -> Unit,
    ): ExtractedMeta {
        var title: String? = null
        var author: String? = null
        var pages = 0
        var noText = 0

        PDDocument.load(file, MemoryUsageSetting.setupTempFileOnly()).use { doc ->
            pages = doc.numberOfPages
            title = doc.documentInformation?.title?.takeIf { it.isNotBlank() }
            author = doc.documentInformation?.author?.takeIf { it.isNotBlank() }
            writer.startChapter(title ?: "Document", null)

            val stripper = PDFTextStripper()
            stripper.sortByPosition = true
            var lastPercent = -1
            for (p in 1..pages) {
                currentCoroutineContext().ensureActive()
                stripper.startPage = p
                stripper.endPage = p
                val raw = try { stripper.getText(doc) } catch (e: Exception) { "" }
                val paragraphs = TextUtils.splitPdfParagraphs(raw)
                if (paragraphs.sumOf { it.length } < 10) {
                    noText++
                    // Keep a placeholder so page numbers and ordering stay complete.
                    writer.add("", "PARAGRAPH", p, null, "page:$p", null, BlockStatus.NO_TEXT)
                } else {
                    paragraphs.forEachIndexed { i, t ->
                        writer.add(t, "PARAGRAPH", p, null, "page:$p#$i", null)
                    }
                }
                val percent = p * 100 / pages
                if (percent != lastPercent) { lastPercent = percent; onProgress(percent) }
            }
        }

        val json = JSONObject().apply {
            put("format", "PDF")
            put("pages", pages)
        }.toString()
        return ExtractedMeta(title, author, renderCover(file, bookId), pages, noText, json)
    }

    private fun renderCover(file: File, bookId: Long): String? = try {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            PdfRenderer(pfd).use { renderer ->
                renderer.openPage(0).use { page ->
                    val w = 400
                    val h = (w.toFloat() * page.height / page.width).toInt().coerceAtLeast(1)
                    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(Color.WHITE)
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    val out = File(coversDir, "$bookId.img")
                    out.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
                    out.absolutePath
                }
            }
        }
    } catch (e: Exception) {
        null
    }
}

class EpubExtractor(private val coversDir: File) {

    private data class Item(val id: String, val path: String, val mediaType: String, val properties: String)

    private val blockSelector = listOf(
        "p", "h1", "h2", "h3", "h4", "h5", "h6", "pre",
        "li:not(:has(p))", "td:not(:has(p))", "th:not(:has(p))",
        "blockquote:not(:has(p, li, h1, h2, h3, h4, h5, h6, div, pre))",
        "div:not(:has(p, h1, h2, h3, h4, h5, h6, li, pre, div, blockquote, table, ul, ol))",
    ).joinToString(", ")

    private fun ZipFile.readText(path: String): String? =
        getEntry(path)?.let { e -> getInputStream(e).use { it.readBytes().toString(Charsets.UTF_8) } }

    suspend fun extract(
        file: File,
        bookId: Long,
        writer: BlockWriter,
        onProgress: suspend (Int) -> Unit,
    ): ExtractedMeta {
        ZipFile(file).use { zip ->
            val container = zip.readText("META-INF/container.xml")
                ?: throw IllegalStateException("Not a valid EPUB (missing container.xml).")
            val opfPath = Jsoup.parse(container, "", Parser.xmlParser())
                .selectFirst("rootfile")?.attr("full-path")?.takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("Not a valid EPUB (no package document).")
            val opfDir = opfPath.substringBeforeLast('/', "")
            val opf = Jsoup.parse(
                zip.readText(opfPath) ?: throw IllegalStateException("Package document is missing."),
                "", Parser.xmlParser(),
            )

            val title = opf.getElementsByTag("dc:title").firstOrNull()?.text()?.trim()?.takeIf { it.isNotEmpty() }
            val author = opf.getElementsByTag("dc:creator").firstOrNull()?.text()?.trim()?.takeIf { it.isNotEmpty() }
            val language = opf.getElementsByTag("dc:language").firstOrNull()?.text()?.trim()

            val manifest = LinkedHashMap<String, Item>()
            for (el in opf.select("manifest > item")) {
                val id = el.attr("id")
                manifest[id] = Item(
                    id,
                    TextUtils.resolveZipPath(opfDir, el.attr("href")),
                    el.attr("media-type"),
                    el.attr("properties"),
                )
            }
            val spine = opf.select("spine > itemref").mapNotNull { manifest[it.attr("idref")] }
                .filter { it.mediaType.contains("html") || it.mediaType.contains("xml") }

            val coverPath = saveCover(zip, opf, manifest, bookId)
            val tocTitles = readToc(zip, manifest)

            var sectionNo = 0
            var lastPercent = -1
            spine.forEachIndexed { i, item ->
                currentCoroutineContext().ensureActive()
                val html = zip.readText(item.path)
                if (html != null) {
                    val doc = Jsoup.parse(html)
                    doc.select("script, style, nav, noscript").remove()
                    val blocks = doc.body().select(blockSelector).mapNotNull { el ->
                        val t = TextUtils.cleanText(el.text())
                        if (t.isEmpty()) null else el to t
                    }
                    if (blocks.isNotEmpty()) {
                        sectionNo++
                        val chapterTitle = tocTitles[item.path]
                            ?: doc.select("h1, h2, h3").firstOrNull()?.text()?.let(TextUtils::cleanText)?.takeIf { it.isNotEmpty() }
                            ?: "Section $sectionNo"
                        writer.startChapter(chapterTitle, item.path)
                        blocks.forEachIndexed { j, (el, text) ->
                            writer.add(text, typeOf(el), null, item.path, "${item.path}#$j", el.tagName())
                        }
                    }
                }
                val percent = (i + 1) * 100 / spine.size
                if (percent != lastPercent) { lastPercent = percent; onProgress(percent) }
            }

            val json = JSONObject().apply {
                put("format", "EPUB")
                put("spineItems", spine.size)
                if (language != null) put("language", language)
            }.toString()
            return ExtractedMeta(title, author, coverPath, 0, 0, json)
        }
    }

    private fun typeOf(el: Element): String = when {
        el.tagName().matches(Regex("h[1-6]")) -> "HEADING"
        el.tagName() == "li" -> "LIST_ITEM"
        el.tagName() == "pre" -> "PREFORMATTED"
        el.tagName() == "blockquote" || el.parents().any { it.tagName() == "blockquote" } -> "QUOTE"
        else -> "PARAGRAPH"
    }

    private fun saveCover(zip: ZipFile, opf: org.jsoup.nodes.Document, manifest: Map<String, Item>, bookId: Long): String? {
        val item = manifest.values.firstOrNull { "cover-image" in it.properties.split(' ') }
            ?: opf.select("meta[name=cover]").firstOrNull()?.attr("content")?.let { manifest[it] }
            ?: return null
        val entry = zip.getEntry(item.path) ?: return null
        return try {
            val out = File(coversDir, "$bookId.img")
            zip.getInputStream(entry).use { ins -> out.outputStream().use { ins.copyTo(it) } }
            out.absolutePath
        } catch (e: Exception) {
            null
        }
    }

    /** Maps spine resource path -> first table-of-contents title that points at it. */
    private fun readToc(zip: ZipFile, manifest: Map<String, Item>): Map<String, String> {
        val titles = LinkedHashMap<String, String>()
        try {
            manifest.values.firstOrNull { "nav" in it.properties.split(' ') }?.let { navItem ->
                zip.readText(navItem.path)?.let { text ->
                    val doc = Jsoup.parse(text)
                    val nav = doc.select("nav").firstOrNull { it.attr("epub:type").contains("toc") } ?: doc.selectFirst("nav")
                    val dir = navItem.path.substringBeforeLast('/', "")
                    nav?.select("a[href]")?.forEach { a ->
                        val p = TextUtils.resolveZipPath(dir, a.attr("href"))
                        val t = TextUtils.cleanText(a.text())
                        if (t.isNotEmpty() && p !in titles) titles[p] = t
                    }
                }
            }
            if (titles.isEmpty()) {
                manifest.values.firstOrNull { it.mediaType == "application/x-dtbncx+xml" }?.let { ncx ->
                    zip.readText(ncx.path)?.let { text ->
                        val doc = Jsoup.parse(text, "", Parser.xmlParser())
                        val dir = ncx.path.substringBeforeLast('/', "")
                        for (np in doc.select("navPoint")) {
                            val label = np.selectFirst("navLabel > text")?.text()?.let(TextUtils::cleanText).orEmpty()
                            val src = np.selectFirst("content")?.attr("src").orEmpty()
                            if (label.isNotEmpty() && src.isNotEmpty()) {
                                val p = TextUtils.resolveZipPath(dir, src)
                                if (p !in titles) titles[p] = label
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // A broken TOC must not fail the import; chapters fall back to headings.
        }
        return titles
    }
}
