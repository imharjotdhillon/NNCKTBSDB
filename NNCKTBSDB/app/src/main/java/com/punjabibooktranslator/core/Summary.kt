package com.punjabibooktranslator.core

object SummaryPromptBuilder {
    fun build(
        bookTitle: String,
        author: String?,
        chapterTitle: String?,
        sourceLang: String,
        paragraphs: List<String>,
        partIndex: Int,
        partCount: Int,
        previousSummary: String?,
    ): String {
        require(paragraphs.isNotEmpty()) { "Nothing to summarise." }
        val lang = if (sourceLang == "pa") "Punjabi (Gurmukhi script)" else "English"
        val sb = StringBuilder()
        sb.append("You are helping a literary translator keep context while translating a book. ")
        sb.append("Summarise the text below. The text is in $lang; write the summary in English.\n\n")
        sb.append("BOOK: ").append(bookTitle)
        author?.takeIf { it.isNotBlank() }?.let { sb.append(" — by ").append(it) }
        sb.append('\n')
        chapterTitle?.takeIf { it.isNotBlank() }?.let { sb.append("CHAPTER: ").append(it).append('\n') }
        if (partCount > 1) sb.append("PART: ${partIndex + 1} of $partCount of this chapter\n")
        sb.append('\n')
        sb.append("RULES\n")
        val rules = mutableListOf(
            "Write plain prose of at most 150 words.",
            "Name the characters and places exactly as they are spelled in the text, and state relationships between characters.",
            "Cover setting, key events and the emotional tone. Add nothing that is not in the text. Do not quote long passages.",
            "This summary is background for a translator only. It will never replace the text.",
        )
        if (!previousSummary.isNullOrBlank()) {
            rules.add("An existing summary of the EARLIER parts of this chapter is given below. Return ONE updated summary that covers the earlier parts and this part together, still within 150 words.")
        }
        rules.add("Reply with the JSON described under OUTPUT FORMAT and nothing else.")
        rules.forEachIndexed { i, r -> sb.append(i + 1).append(". ").append(r).append('\n') }
        sb.append('\n')
        if (!previousSummary.isNullOrBlank()) sb.append("EXISTING SUMMARY OF EARLIER PARTS:\n").append(previousSummary.trim()).append("\n\n")
        sb.append("TEXT (between the markers):\n<<<BEGIN TEXT\n")
        sb.append(paragraphs.joinToString("\n\n")).append("\nEND TEXT>>>\n\n")
        sb.append("OUTPUT FORMAT\nReturn one JSON object inside a single ```json code block: {\"summary\":\"<the summary>\"}\n")
        sb.append("Escape double quotes inside the text as \\\" and line breaks as \\n.\n")
        return sb.toString()
    }
}

data class SummaryParse(val text: String, val recovered: Boolean, val warnings: List<String>, val error: String?)

object SummaryParser {
    private fun unfence(t: String): Pair<String, Boolean> =
        Fences.blocks(t).firstOrNull()?.let { it.text to it.unclosed } ?: (t to false)

    fun parse(raw: String): SummaryParse {
        val t = raw.trim()
        if (t.isEmpty()) return SummaryParse("", false, emptyList(), "The pasted reply is empty.")
        val (body, unclosed) = unfence(t)
        val warnings = ArrayList<String>()
        fun done(text: String, recovered: Boolean): SummaryParse {
            val s = TextUtils.normalizeTranslation(text)
            if (s.isEmpty()) return SummaryParse("", false, warnings, "The reply contained no summary text.")
            if (s.length > 1500) warnings.add("This summary is long (${s.length} characters). Shorter summaries make better context; consider trimming it.")
            return SummaryParse(s, recovered, warnings, null)
        }
        try {
            val v = JsonReader(body.trim()).parseTop()
            if (v is J.Obj) {
                val k = v.map.keys.firstOrNull { it.equals("summary", true) }
                val s = (k?.let { v.map[it] } as? J.Str)?.v
                if (s != null) return done(s, false)
            }
        } catch (e: JsonParseException) {
            // fall through to salvage
        }
        val m = Regex("\"summary\"\\s*:\\s*\"", RegexOption.IGNORE_CASE).find(body)
        if (m != null) {
            try {
                val s = JsonReader(body.substring(m.range.last)).parseTop()
                if (s is J.Str) { warnings.add("The reply was not valid JSON; the summary text was recovered."); return done(s.v, true) }
            } catch (e: JsonParseException) {
                return SummaryParse("", false, warnings, "The reply looks cut off in the middle of the summary" + if (unclosed) "." else ", so it was not used.")
            }
        }
        val trimmed = body.trim()
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            return SummaryParse("", false, warnings, "Couldn't find a summary in this reply.")
        }
        warnings.add("The reply was not JSON; it was used as plain text. Please check it.")
        return done(trimmed.replace(Regex("(?i)^\\s*summary\\s*:\\s*"), ""), true)
    }
}

object SummaryPlanner {
    /** Groups paragraphs into parts of about [budget] characters. Never splits or drops a paragraph. */
    fun <T> split(items: List<T>, charsOf: (T) -> Int, budget: Int = 20_000): List<List<T>> {
        val parts = ArrayList<List<T>>()
        var cur = ArrayList<T>()
        var used = 0
        for (it in items) {
            val n = charsOf(it)
            if (cur.isNotEmpty() && used + n > budget) { parts.add(cur); cur = ArrayList(); used = 0 }
            cur.add(it); used += n
        }
        if (cur.isNotEmpty()) parts.add(cur)
        return parts
    }
}

data class Snippet(val text: String, val matchStart: Int, val matchEnd: Int)

object SearchText {
    fun escapeLike(s: String): String = buildString {
        for (c in s) { if (c == '\\' || c == '%' || c == '_') append('\\'); append(c) }
    }

    /** SQL LIKE pattern for use with ESCAPE '\'. */
    fun pattern(query: String): String = "%" + escapeLike(TextUtils.normalize(query.trim())) + "%"

    fun indexOfIgnoreCase(text: String, q: String, from: Int = 0): Int {
        if (q.isEmpty()) return -1
        var i = from.coerceAtLeast(0)
        while (i <= text.length - q.length) {
            if (text.regionMatches(i, q, 0, q.length, ignoreCase = true)) return i
            i++
        }
        return -1
    }
}

object Snippets {
    fun around(text: String, query: String, radius: Int = 60): Snippet {
        val q = TextUtils.normalize(query.trim())
        val flat = text.replace('\n', ' ')
        val i = SearchText.indexOfIgnoreCase(flat, q)
        if (i < 0) return Snippet(if (flat.length > radius * 2) flat.take(radius * 2) + "…" else flat, -1, -1)
        val start = (i - radius).coerceAtLeast(0)
        val end = (i + q.length + radius).coerceAtMost(flat.length)
        val prefix = if (start > 0) "…" else ""
        val suffix = if (end < flat.length) "…" else ""
        return Snippet(prefix + flat.substring(start, end) + suffix, prefix.length + (i - start), prefix.length + (i - start) + q.length)
    }
}
