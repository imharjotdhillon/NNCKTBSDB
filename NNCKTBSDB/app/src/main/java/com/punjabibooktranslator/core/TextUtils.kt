package com.punjabibooktranslator.core

import java.net.URLDecoder
import java.text.Normalizer

/** Pure Kotlin helpers (no Android dependencies) so they can be unit-tested on the JVM. */
object TextUtils {

    /** NFC-normalise and tidy whitespace without touching ZWJ/ZWNJ (needed for Indic shaping). */
    fun cleanText(raw: String): String =
        Normalizer.normalize(raw, Normalizer.Form.NFC)
            .replace('\u00A0', ' ')
            .replace(Regex("[ \\t\\u2009\\u200A\\u202F]+"), " ")
            .trim()

    /** Unicode NFC (keeps ZWJ/ZWNJ and line breaks). Gurmukhi letters such as U+0A36 normalise to base + nukta. */
    fun normalize(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFC)

    /** Normalised and trimmed, but inner line breaks are kept: used for saved translations and glossary text. */
    fun normalizeTranslation(s: String): String = normalize(s).trim()

    /** "pa" if Gurmukhi dominates the letters in the sample, otherwise "en". */
    fun detectLanguage(sample: String): String {
        var gurmukhi = 0
        var latin = 0
        for (ch in sample) {
            when {
                ch.code in 0x0A00..0x0A7F -> gurmukhi++
                ch in 'a'..'z' || ch in 'A'..'Z' -> latin++
            }
        }
        val total = gurmukhi + latin
        return if (total == 0) "en" else if (gurmukhi.toDouble() / total > 0.3) "pa" else "en"
    }

    private val sentenceEnders = setOf('.', '!', '?', '"', '\u201D', '\u2019', ':', '\u0964', '\u0965')

    /**
     * Turns the raw text of one PDF page into paragraphs. PDF text has no real paragraph markup, so this is a
     * heuristic: blank lines split paragraphs, and a short line that ends a sentence also ends a paragraph.
     * Hyphenated line breaks ("exam-\nple") are rejoined.
     */
    fun splitPdfParagraphs(raw: String): List<String> {
        val lines = raw.replace("\r", "").split("\n").map { it.trim() }
        val maxLen = lines.maxOfOrNull { it.length } ?: 0
        val groups = ArrayList<List<String>>()
        var cur = ArrayList<String>()
        for (l in lines) {
            if (l.isEmpty()) {
                if (cur.isNotEmpty()) { groups.add(cur); cur = ArrayList() }
            } else cur.add(l)
        }
        if (cur.isNotEmpty()) groups.add(cur)

        val out = ArrayList<String>()
        for (g in groups) {
            val para = StringBuilder()
            g.forEachIndexed { i, line ->
                if (para.isNotEmpty()) {
                    if (para.endsWith("-") && line.first().isLowerCase()) para.setLength(para.length - 1)
                    else para.append(' ')
                }
                para.append(line)
                val short = line.length < maxLen * 0.6
                if (short && line.last() in sentenceEnders && i < g.lastIndex) {
                    out.add(cleanText(para.toString()))
                    para.setLength(0)
                }
            }
            if (para.isNotBlank()) out.add(cleanText(para.toString()))
        }
        return out.filter { it.isNotEmpty() }
    }

    /** Resolves an EPUB-internal href against a base directory, normalising "." / ".." and %-escapes. */
    fun resolveZipPath(baseDir: String, href: String): String {
        val clean = href.substringBefore('#').substringBefore('?')
        val decoded = try {
            URLDecoder.decode(clean.replace("+", "%2B"), "UTF-8")
        } catch (e: IllegalArgumentException) {
            clean
        }
        val full = when {
            decoded.startsWith("/") -> decoded.removePrefix("/")
            baseDir.isEmpty() -> decoded
            else -> "$baseDir/$decoded"
        }
        val parts = ArrayList<String>()
        for (seg in full.split('/')) {
            when (seg) {
                "", "." -> {}
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
                else -> parts.add(seg)
            }
        }
        return parts.joinToString("/")
    }

    fun paraId(order: Int): String = "P" + (order + 1).toString().padStart(4, '0')

    fun languageName(code: String): String = when (code) {
        "pa" -> "Punjabi"
        "en" -> "English"
        else -> code
    }
}
