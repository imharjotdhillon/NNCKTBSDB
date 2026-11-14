package com.punjabibooktranslator

import com.punjabibooktranslator.core.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

class ExportTest {
    private fun b(id: String, tr: String?, ch: Long? = 1, title: String? = "One", type: String = "PARAGRAPH", st: String = "OK", ver: String = VerifyStates.NOT_VERIFIED, orig: String = "orig $id") =
        ExportBlock(id, ch, title, type, null, orig, tr, st, ver)

    private fun ready(r: PlanResult) = (r as PlanResult.Ready).doc

    @Test fun auditCountsOnlyExtractableBlocks() {
        val a = ExportChecker.check(listOf(b("P1", "x"), b("P2", null), b("P3", "  "), b("P4", null, st = "NO_TEXT"), b("P5", "y", ver = VerifyStates.MAJOR_ISSUES)))
        assertEquals(4, a.total); assertEquals(2, a.translated); assertEquals(listOf("P2", "P3"), a.missing)
        assertEquals(1, a.noTextBlocks); assertEquals(1, a.unresolved); assertEquals(1, a.unverified); assertFalse(a.isComplete)
        assertEquals(50, a.percent)
    }

    @Test fun incompleteIsRefusedUnlessAllowed() {
        val blocks = listOf(b("P1", "x"), b("P2", null))
        val r = ExportPlanner.plan("T", null, "en", "pa", blocks, ExportMode.TRANSLATED, allowIncomplete = false)
        assertTrue(r is PlanResult.Refused)
        val doc = ready(ExportPlanner.plan("T", null, "en", "pa", blocks, ExportMode.TRANSLATED, allowIncomplete = true))
        val items = doc.chapters.single().items
        assertTrue(items[1].missing); assertEquals(ExportPlanner.MISSING_MARKER, items[1].translation)
        assertFalse(items[0].missing)
    }

    @Test fun emptyAndUntranslatedBooksAreRefused() {
        assertTrue(ExportPlanner.plan("T", null, "en", "pa", emptyList(), ExportMode.TRANSLATED, true) is PlanResult.Refused)
        assertTrue(ExportPlanner.plan("T", null, "en", "pa", listOf(b("P1", null)), ExportMode.TRANSLATED, true) is PlanResult.Refused)
    }

    @Test fun neverFillsTranslationFromOriginal() {
        val doc = ready(ExportPlanner.plan("T", null, "en", "pa", listOf(b("P1", "tr"), b("P2", null, orig = "SECRET ORIGINAL")), ExportMode.TRANSLATED, true))
        val all = doc.chapters.flatMap { it.items }
        assertTrue(all.none { it.translation?.contains("SECRET") == true }); assertTrue(all.all { it.original == null })
    }

    @Test fun bilingualKeepsOriginalAndChaptersSplit() {
        val doc = ready(ExportPlanner.plan("T", "A", "en", "pa",
            listOf(b("P1", "t1", ch = 1, title = "One"), b("P2", "t2", ch = 2, title = "Two"), b("P3", "t3", ch = 2, title = "Two"), b("P4", null, st = "NO_TEXT", ch = 2)),
            ExportMode.BILINGUAL, false))
        assertEquals(listOf("One", "Two"), doc.chapters.map { it.title }); assertEquals(2, doc.chapters[1].items.size)
        assertEquals("orig P1", doc.chapters[0].items[0].original)
    }

    private fun entries(bytes: ByteArray): Map<String, ByteArray> {
        val m = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { z -> var e: ZipEntry? = z.nextEntry; while (e != null) { m[e.name] = z.readBytes(); e = z.nextEntry } }
        return m
    }

    private fun wellFormed(xml: ByteArray) {
        val f = DocumentBuilderFactory.newInstance(); f.isNamespaceAware = true
        f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        f.newDocumentBuilder().parse(ByteArrayInputStream(xml))
    }

    private val sample get() = ready(ExportPlanner.plan("Ti & <tle>", "Au\"thor", "en", "pa", listOf(
        b("P1", "ਸਤ ਸ੍ਰੀ ਅਕਾਲ & <b>", title = "ਅਧਿਆਇ 1", type = "HEADING"), b("P2", "line1\nline2 \u0001", orig = "Hello <x>"), b("P3", null), b("P4", "q", ch = 2, title = null, type = "QUOTE")),
        ExportMode.BILINGUAL, true))

    @Test fun epubStructureIsValid() {
        val bytes = EpubWriter.toBytes(sample, byteArrayOf(1, 2, 3), "urn:test:1", "2026-11-10T12:00:00Z")
        val first = ZipInputStream(ByteArrayInputStream(bytes)).use { it.nextEntry }!!
        assertEquals("mimetype", first.name); assertEquals(ZipEntry.STORED, first.method)
        val e = entries(bytes)
        assertEquals("application/epub+zip", String(e["mimetype"]!!))
        for ((n, d) in e) if (n.endsWith(".xhtml") || n.endsWith(".opf") || n.endsWith(".xml")) wellFormed(d)
        assertTrue(e.containsKey("OEBPS/fonts/NotoSansGurmukhi.ttf")); assertTrue(String(e["OEBPS/style.css"]!!).contains("@font-face"))
        val opf = String(e["OEBPS/content.opf"]!!)
        assertTrue(opf.contains("<dc:language>pa</dc:language>")); assertTrue(opf.contains("Ti &amp; &lt;tle&gt;")); assertTrue(opf.contains("Au&quot;thor"))
        assertEquals(2, Regex("<itemref ").findAll(opf).count())
    }

    @Test fun epubEscapesAndKeepsGurmukhiAndMarksMissing() {
        val e = entries(EpubWriter.toBytes(sample, null, "id", "2026-11-10T12:00:00Z"))
        val ch1 = String(e["OEBPS/ch0001.xhtml"]!!, Charsets.UTF_8)
        assertTrue(ch1.contains("ਸਤ ਸ੍ਰੀ ਅਕਾਲ &amp; &lt;b&gt;")); assertTrue(ch1.contains("Hello &lt;x&gt;")); assertTrue(ch1.contains("line1<br/>line2"))
        assertFalse(ch1.contains("\u0001")); assertTrue(ch1.contains("class=\"tr missing\"")); assertTrue(ch1.contains(ExportPlanner.MISSING_MARKER))
        assertFalse(e.containsKey("OEBPS/fonts/NotoSansGurmukhi.ttf")); assertFalse(String(e["OEBPS/style.css"]!!).contains("@font-face"))
        assertTrue(String(e["OEBPS/ch0002.xhtml"]!!).contains("<title>Part 2</title>"))
    }

    // ---- backup
    private val book = BackupBook("T \"x\"", null, "PDF", "abc123", "en", "pa", "LITERARY",
        listOf(BackupTranslation("P0001", "ਸਤ\nਸ੍ਰੀ \"q\" \\", "TRANSLATED", true, VerifyStates.VERIFIED)),
        listOf(BackupTerm("Amrit", "ਅੰਮ੍ਰਿਤ", "CHARACTER", "brother", true)), listOf(BackupSummary("Ch 1", 0, "Summary.")))

    @Test fun backupRoundTrips() {
        val back = BackupFormat.read(BackupFormat.write(Backup(1, 1234567890123, listOf(book))))
        assertEquals(listOf(book), back.books); assertEquals(1234567890123, back.createdAt)
    }

    @Test fun backupRejectsDamagedWrongAndNewer() {
        fun fails(t: String) = try { BackupFormat.read(t); false } catch (e: BackupFormat.BackupException) { true }
        val good = BackupFormat.write(Backup(1, 1, listOf(book)))
        assertTrue(fails(good.dropLast(10))); assertTrue(fails("{\"a\":1}")); assertTrue(fails(""))
        assertTrue(fails(good.replace("\"version\":1", "\"version\":9")))
        assertTrue(fails("{\"format\":\"punjabi-book-translator-backup\",\"version\":1,\"books\":[{\"title\":\"x\"}]}"))
    }

    @Test fun restorePlannerFillsGapsAndProtectsExistingWork() {
        val inc = listOf(BackupTranslation("P1", "new", "T", false, "x"), BackupTranslation("P2", "same", "T", false, "x"),
            BackupTranslation("P3", "different", "T", false, "x"), BackupTranslation("P9", "nowhere", "T", false, "x"), BackupTranslation("P1", "dup", "T", false, "x"))
        val p = RestorePlanner.plan(mapOf("P1" to null, "P2" to " same ", "P3" to "mine", "P4" to "untouched"), inc)
        assertEquals(listOf("P1"), p.toAdd.map { it.paraId }); assertEquals("new", p.toAdd[0].text)
        assertEquals(1, p.identical); assertEquals(listOf("P3"), p.conflicts.map { it.paraId }); assertEquals(listOf("P9"), p.unknownIds)
    }
}
