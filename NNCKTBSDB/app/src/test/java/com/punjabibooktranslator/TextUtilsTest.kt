package com.punjabibooktranslator

import com.punjabibooktranslator.core.TextUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextUtilsTest {
    @Test fun detectsEnglishAndPunjabi() {
        assertEquals("en", TextUtils.detectLanguage("It was a bright cold day in April."))
        assertEquals("pa", TextUtils.detectLanguage("ਇਹ ਇੱਕ ਪੰਜਾਬੀ ਵਾਕ ਹੈ।"))
        assertEquals("en", TextUtils.detectLanguage("The word ਪੰਜਾਬ means five waters in the old tongue."))
        assertEquals("en", TextUtils.detectLanguage(""))
    }

    @Test fun cleansText() {
        assertEquals("a b c", TextUtils.cleanText("a\u00A0\u00A0b   c "))
        assertTrue(TextUtils.cleanText("a\u200Db").contains('\u200D'))
        assertEquals("\u00E9", TextUtils.cleanText("e\u0301"))
    }

    @Test fun splitsPdfParagraphs() {
        val page = "The first sentence runs across the whole line of the page\nand finishes here.\n\nSecond paragraph is a\nsingle block of text."
        val p = TextUtils.splitPdfParagraphs(page)
        assertEquals(2, p.size)
        assertEquals("The first sentence runs across the whole line of the page and finishes here.", p[0])
        assertEquals(listOf("This is an example of hyphenation across lines."),
            TextUtils.splitPdfParagraphs("This is an exam-\nple of hyphenation across lines."))
        assertTrue(TextUtils.splitPdfParagraphs("  \n\n \n").isEmpty())
        val pa = TextUtils.splitPdfParagraphs("ਇਹ ਪਹਿਲਾ ਵਾਕ ਹੈ।\n\nਇਹ ਦੂਜਾ ਵਾਕ ਹੈ।")
        assertEquals(2, pa.size)
    }

    @Test fun resolvesZipPaths() {
        assertEquals("OEBPS/ch1.xhtml", TextUtils.resolveZipPath("OEBPS", "ch1.xhtml"))
        assertEquals("OEBPS/images/c.jpg", TextUtils.resolveZipPath("OEBPS/text", "../images/c.jpg"))
        assertEquals("OEBPS/ch1.xhtml", TextUtils.resolveZipPath("OEBPS", "ch1.xhtml#sec2"))
        assertEquals("My Book/ch 1.xhtml", TextUtils.resolveZipPath("", "My%20Book/ch%201.xhtml"))
        assertEquals("a+b.xhtml", TextUtils.resolveZipPath("", "a+b.xhtml"))
    }

    @Test fun paragraphIds() {
        assertEquals("P0001", TextUtils.paraId(0))
        assertEquals("P10000", TextUtils.paraId(9999))
    }
}
